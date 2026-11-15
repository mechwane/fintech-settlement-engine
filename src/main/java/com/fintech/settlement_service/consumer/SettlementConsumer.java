package com.fintech.settlement_service.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.settlement_service.dto.SettlementEvent;
import com.fintech.settlement_service.model.DeadLetterEvent;
import com.fintech.settlement_service.repository.DeadLetterEventRepository;
import com.fintech.settlement_service.repository.MerchantWalletRepository;
import com.fintech.settlement_service.repository.SettlementLedgerRepository;
import com.fintech.settlement_service.service.VirtualAccountRegistryService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Component
public class SettlementConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementConsumer.class);

    private final SettlementLedgerRepository ledgerRepository;
    private final MerchantWalletRepository walletRepository;
    private final DeadLetterEventRepository dltRepository;
    private final VirtualAccountRegistryService registryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SettlementConsumer(
            SettlementLedgerRepository ledgerRepository,
            MerchantWalletRepository walletRepository,
            DeadLetterEventRepository dltRepository,
            VirtualAccountRegistryService registryService) {
        this.ledgerRepository = ledgerRepository;
        this.walletRepository = walletRepository;
        this.dltRepository = dltRepository;
        this.registryService = registryService;
    }

    @Transactional
    @KafkaListener(topics = "virtual-account-settlements", groupId = "settlement-final-group")
    public void consumeSettlement(SettlementEvent event) {
        String ref = event.transactionReference();
        log.info(">>>>>> CONSUMER PROCESSING TXN: {} | VA: {} <<<<<<", ref, event.virtualAccountNumber());

        if (event.merchantId() == null || event.merchantId().isBlank()
                || "POISON".equalsIgnoreCase(event.merchantId())
                || event.amountKobo() <= 0) {
            throw new IllegalArgumentException(
                    "Invalid settlement payload for ref " + ref + ": merchantId=" + event.merchantId() + ", amountKobo=" + event.amountKobo()
            );
        }

        VirtualAccountRegistryService.AccountTier tier =
                registryService.resolveAccountTier(event.virtualAccountNumber(), event.merchantId());

        if (!tier.active()) {
            throw new IllegalStateException("Virtual account " + tier.accountNumber() + " is suspended");
        }

        String resolvedMerchantId = tier.merchantId();
        long platformFeeKobo = registryService.calculateFeeKobo(event.amountKobo(), tier);
        long merchantPayoutKobo = event.amountKobo() - platformFeeKobo;

        int rowsInserted = ledgerRepository.insertLedgerEntryIdempotently(
                ref,
                event.virtualAccountNumber(),
                resolvedMerchantId,
                event.amountKobo(),
                platformFeeKobo,
                merchantPayoutKobo,
                "SETTLED"
        );

        if (rowsInserted == 0) {
            log.warn("[POSTGRES ON-CONFLICT IDEMPOTENCY] Txn {} already settled. Zero-exception skip.", ref);
            return;
        }

        walletRepository.creditWalletAtomically(resolvedMerchantId, merchantPayoutKobo);

        log.info("""
                ========================================
                [ATOMIC LEDGER + WALLET COMMITTED]
                Transaction Ref : {}
                Virtual Account : {} ({})
                Merchant ID     : {}
                Applied Tier    : {} bps (Cap: {} kobo)
                Total Credited  : {} kobo (NGN {})
                Platform Cut    : {} kobo (NGN {})
                Merchant Payout : {} kobo (NGN {})
                Status          : SETTLED & WALLET CREDITED
                ========================================
                """,
                ref,
                event.virtualAccountNumber(), tier.merchantName(),
                resolvedMerchantId,
                tier.feeBasisPoints(), tier.feeCapKobo(),
                event.amountKobo(), event.amountKobo() / 100.0,
                platformFeeKobo, platformFeeKobo / 100.0,
                merchantPayoutKobo, merchantPayoutKobo / 100.0
        );
    }

    @KafkaListener(topics = "virtual-account-settlements.DLT", groupId = "settlement-dlt-audit-group")
    public void consumeDeadLetter(
            ConsumerRecord<String, Object> record,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) byte[] exceptionMsgBytes) {
        try {
            String errorMsg = exceptionMsgBytes != null
                    ? new String(exceptionMsgBytes, StandardCharsets.UTF_8)
                    : "Unrecoverable consumer failure";
            String payloadJson = record.value() instanceof String s
                    ? s
                    : objectMapper.writeValueAsString(record.value());

            DeadLetterEvent dltRow = new DeadLetterEvent(
                    "virtual-account-settlements",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    payloadJson,
                    errorMsg
            );
            dltRepository.save(dltRow);
            log.warn("[DLT QUARANTINE SAVED] Stored failed event key '{}' in dead_letter_events (ID: {})",
                    record.key(), dltRow.getId());
        } catch (Exception e) {
            log.error("[DLT AUDIT ERROR] Failed to persist DLT record: {}", e.getMessage());
        }
    }
}