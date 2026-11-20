package com.fintech.settlement_service.service;

import com.fintech.settlement_service.model.MerchantPayout;
import com.fintech.settlement_service.repository.MerchantPayoutRepository;
import com.fintech.settlement_service.repository.MerchantWalletRepository;
import com.fintech.settlement_service.repository.SettlementLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
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

    private enum SwitchOutcome { SUCCESS, DEFINITIVE_FAILURE, TIMEOUT_PENDING_TSQ } private static class LedgerCollisionException extends RuntimeException { LedgerCollisionException(String m){super(m);} } private static class InsufficientBalanceException extends RuntimeException {
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

                int ins=ledgerRepository.insertLedgerEntryIdempotently(ref,accountTag,req.merchantId(),-req.amountKobo(),0L,-req.amountKobo(),"PAYOUT_DEBIT");if(ins==0)throw new LedgerCollisionException("Ledger collision for "+ref);return true;});}catch(LedgerCollisionException ce){return new PayoutResult(409,"rejected",ref,null,walletRepository.getAvailableBalanceKobo(req.merchantId()),ce.getMessage());
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
        SwitchOutcome out=callSimulatedNipSwitch(req);if(out==SwitchOutcome.SUCCESS){String sid="NIP-"+UUID.randomUUID().toString().substring(0,12).toUpperCase();txTemplate.executeWithoutResult(s->payoutRepository.markSuccess(ref,sid));return new PayoutResult(200,"settled",ref,sid,walletRepository.getAvailableBalanceKobo(req.merchantId()),"Disbursement settled");}if(out==SwitchOutcome.TIMEOUT_PENDING_TSQ){txTemplate.executeWithoutResult(s->payoutRepository.markPendingTsq(ref,"Switch Timeout (Code 97)"));return new PayoutResult(202,"pending_tsq",ref,null,walletRepository.getAvailableBalanceKobo(req.merchantId()),"Switch timeout; queued for TSQ");}refundPayoutAtomically(ref,accountTag,req.merchantId(),req.amountKobo(),"Switch Rejected (Code 91)");return new PayoutResult(502,"failed_refunded",ref,null,walletRepository.getAvailableBalanceKobo(req.merchantId()),"Bank transfer failed; refunded");}private void refundPayoutAtomically(String ref,String tag,String mch,long amt,String reason){txTemplate.executeWithoutResult(s->{if(ledgerRepository.insertLedgerEntryIdempotently(ref+"-REV",tag,mch,amt,0L,amt,"PAYOUT_REFUND")==1)walletRepository.creditWalletAtomically(mch,amt);payoutRepository.markFailedRefunded(ref,reason);});}@Scheduled(fixedDelay=15000)public void reconcileStrandedPayouts(){for(MerchantPayout p:payoutRepository.findStrandedPayouts()){String tag="BANK:"+p.getDestinationBankCode()+":"+p.getDestinationAccountNumber();if("998".equals(p.getDestinationBankCode()))refundPayoutAtomically(p.getPayoutReference(),tag,p.getMerchantId(),p.getAmountKobo(),"TSQ Confirmed Unsettled");else txTemplate.executeWithoutResult(s->payoutRepository.markSuccess(p.getPayoutReference(),"NIP-TSQ-"+UUID.randomUUID().toString().substring(0,8).toUpperCase()));}}private SwitchOutcome callSimulatedNipSwitch(PayoutRequest req){if("999".equals(req.destinationBankCode())||"0000000000".equals(req.destinationAccountNumber()))return SwitchOutcome.DEFINITIVE_FAILURE;if("998".equals(req.destinationBankCode())||"1111111111".equals(req.destinationAccountNumber()))return SwitchOutcome.TIMEOUT_PENDING_TSQ;return SwitchOutcome.SUCCESS;}}