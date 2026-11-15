package com.fintech.settlement_service.service;

import com.fintech.settlement_service.model.MerchantPayout;
import com.fintech.settlement_service.repository.MerchantPayoutRepository;
import com.fintech.settlement_service.repository.MerchantWalletRepository;
import com.fintech.settlement_service.repository.SettlementLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private final MerchantPayoutRepository payoutRepository;
    private final MerchantWalletRepository walletRepository;
    private final SettlementLedgerRepository ledgerRepository;
    private final TransactionTemplate txTemplate;

    public PayoutService(
            MerchantPayoutRepository payoutRepository,
            MerchantWalletRepository walletRepository,
            SettlementLedgerRepository ledgerRepository,
            TransactionTemplate txTemplate) {
        this.payoutRepository = payoutRepository;
        this.walletRepository = walletRepository;
        this.ledgerRepository = ledgerRepository;
        this.txTemplate = txTemplate;
    }

    public record PayoutRequest(
            String payoutReference,
            String merchantId,
            String destinationBankCode,
            String destinationAccountNumber,
            Long amountKobo
    ) {}

    public record PayoutResult(
            int httpStatus,
            String status,
            String payoutReference,
            String nibssSessionId,
            Long remainingBalanceKobo,
            String message
    ) {}

    private static class InsufficientBalanceException extends RuntimeException {
        InsufficientBalanceException(String msg) { super(msg); }
    }

    public PayoutResult executeDisbursement(PayoutRequest req) {
        if (req.payoutReference() == null || req.payoutReference().isBlank()
                || req.merchantId() == null || req.merchantId().isBlank()
                || req.destinationBankCode() == null || req.destinationBankCode().isBlank()
                || req.destinationAccountNumber() == null || req.destinationAccountNumber().isBlank()
                || req.amountKobo() == null || req.amountKobo() <= 0) {
            return new PayoutResult(400, "rejected", req.payoutReference(), null, null,
                    "Invalid payout request fields or non-positive amountKobo");
        }

        String ref = req.payoutReference();
        String accountTag = "BANK:" + req.destinationBankCode() + ":" + req.destinationAccountNumber();

        // PHASE 1: Atomic Reservation, TOCTOU Wallet Debit & Immutable Ledger Debit
        Boolean reserved;
        try {
            reserved = txTemplate.execute(status -> {
                int inserted = payoutRepository.insertPayoutIdempotently(
                        ref, req.merchantId(), req.destinationBankCode(),
                        req.destinationAccountNumber(), req.amountKobo()
                );
                if (inserted == 0) {
                    return false;
                }

                int debited = walletRepository.debitWalletIfSufficient(req.merchantId(), req.amountKobo());
                if (debited == 0) {
                    throw new InsufficientBalanceException("Insufficient wallet balance for merchant " + req.merchantId());
                }

                ledgerRepository.insertLedgerEntryIdempotently(
                        ref,
                        accountTag,
                        req.merchantId(),
                        -req.amountKobo(),
                        0L,
                        -req.amountKobo(),
                        "PAYOUT_DEBIT"
                );
                return true;
            });
        } catch (InsufficientBalanceException ex) {
            Long currentBalance = walletRepository.getAvailableBalanceKobo(req.merchantId());
            log.warn("[PAYOUT REJECTED] {} | Merchant {} balance {} kobo < requested {} kobo",
                    ref, req.merchantId(), currentBalance, req.amountKobo());
            return new PayoutResult(422, "rejected", ref, null, currentBalance, ex.getMessage());
        }

        if (Boolean.FALSE.equals(reserved)) {
            MerchantPayout existing = payoutRepository.findByPayoutReference(ref).orElse(null);
            Long currentBalance = walletRepository.getAvailableBalanceKobo(req.merchantId());
            log.warn("[PAYOUT IDEMPOTENCY] Duplicate payout {} absorbed (current status: {})",
                    ref, existing != null ? existing.getStatus() : "UNKNOWN");
            return new PayoutResult(200, "ignored", ref,
                    existing != null ? existing.getNibssSessionId() : null,
                    currentBalance,
                    "Duplicate payout reference absorbed (status: " + (existing != null ? existing.getStatus() : "UNKNOWN") + ")");
        }

        log.info("[PAYOUT PHASE 1 COMMITTED] Reserved & debited {} kobo from {} for ref {}",
                req.amountKobo(), req.merchantId(), ref);

        // PHASE 2: External Interbank Switch Call (No DB lock held during network I/O)
        boolean bankSwitchSuccess = callSimulatedNipSwitch(req);

        if (bankSwitchSuccess) {
            String nibssSessionId = "NIP-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
            txTemplate.executeWithoutResult(status -> payoutRepository.markSuccess(ref, nibssSessionId));
            Long newBalance = walletRepository.getAvailableBalanceKobo(req.merchantId());

            log.info("[PAYOUT PHASE 2 SUCCESS] Ref {} settled via {} | Remaining wallet: {} kobo",
                    ref, nibssSessionId, newBalance);
            return new PayoutResult(200, "settled", ref, nibssSessionId, newBalance,
                    "Disbursement settled successfully");
        } else {
            // Compensating Rollback Transaction: Append PAYOUT_REFUND + Restore Wallet Balance
            String failureReason = "NIBSS NIP Switch Timeout / Destination Bank Unreachable (Code 91)";
            String refundRef = ref + "-REV";

            txTemplate.executeWithoutResult(status -> {
                ledgerRepository.insertLedgerEntryIdempotently(
                        refundRef,
                        accountTag,
                        req.merchantId(),
                        req.amountKobo(),
                        0L,
                        req.amountKobo(),
                        "PAYOUT_REFUND"
                );
                walletRepository.creditWalletAtomically(req.merchantId(), req.amountKobo());
                payoutRepository.markFailedRefunded(ref, failureReason);
            });

            Long restoredBalance = walletRepository.getAvailableBalanceKobo(req.merchantId());
            log.error("[PAYOUT PHASE 2 FAILED -> REFUNDED] Ref {} failed ({}). Re-credited {} kobo to {}. Restored balance: {} kobo",
                    ref, failureReason, req.amountKobo(), req.merchantId(), restoredBalance);

            return new PayoutResult(502, "failed_refunded", ref, null, restoredBalance,
                    "Outbound bank transfer failed; compensating refund credited atomically");
        }
    }

    private boolean callSimulatedNipSwitch(PayoutRequest req) {
        // Bank code "999" or account "0000000000" triggers deterministic switch failure for testing
        return !"999".equals(req.destinationBankCode()) && !"0000000000".equals(req.destinationAccountNumber());
    }
}