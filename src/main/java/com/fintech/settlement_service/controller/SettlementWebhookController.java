package com.fintech.settlement_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.settlement_service.dto.SettlementEvent;
import com.fintech.settlement_service.model.SettlementLedger;
import com.fintech.settlement_service.repository.MerchantWalletRepository;
import com.fintech.settlement_service.repository.OutboxEventRepository;
import com.fintech.settlement_service.repository.SettlementLedgerRepository;
import com.fintech.settlement_service.service.WebhookSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/settlements")
public class SettlementWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SettlementWebhookController.class);
    private static final String TOPIC = "virtual-account-settlements";

    private final StringRedisTemplate redisTemplate;
    private final OutboxEventRepository outboxRepository;
    private final SettlementLedgerRepository ledgerRepository;
    private final MerchantWalletRepository walletRepository;
    private final WebhookSecurityService securityService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SettlementWebhookController(
            StringRedisTemplate redisTemplate,
            OutboxEventRepository outboxRepository,
            SettlementLedgerRepository ledgerRepository,
            MerchantWalletRepository walletRepository,
            WebhookSecurityService securityService) {
        this.redisTemplate = redisTemplate;
        this.outboxRepository = outboxRepository;
        this.ledgerRepository = ledgerRepository;
        this.walletRepository = walletRepository;
        this.securityService = securityService;
    }

    public record WebhookResponse(String status, String reference, String message) {}
    public record ReversalRequest(String reversalReference, String originalTransactionReference) {}

    @Transactional
    @PostMapping("/webhook")
    public ResponseEntity<WebhookResponse> handleSettlementWebhook(
            @RequestHeader(name = "X-Webhook-Signature", required = false) String signature,
            @RequestBody String rawJson) {

        if (!securityService.isValidSignature(rawJson, signature)) {
            log.warn("Unauthorized webhook rejected: Signature verification failed or missing.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new WebhookResponse("rejected", null, "Invalid or missing HMAC signature"));
        }

        SettlementEvent payload;
        try {
            payload = objectMapper.readValue(rawJson, SettlementEvent.class);
        } catch (Exception e) {
            log.error("Failed to parse settlement JSON payload: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new WebhookResponse("rejected", null, "Malformed JSON payload"));
        }

        String ref = payload.transactionReference();
        String idempotencyKey = "idempotency:settlement:" + ref;

        // 1. Fail-open Redis rate shield
        try {
            Boolean isFirstSeen = this.redisTemplate.opsForValue()
                    .setIfAbsent(idempotencyKey, "OUTBOXED", Duration.ofMinutes(10));

            if (Boolean.FALSE.equals(isFirstSeen)) {
                log.warn("[REDIS RATE SHIELD] Duplicate webhook absorbed for reference: {}", ref);
                return ResponseEntity.ok(new WebhookResponse("ignored", ref, "Duplicate transaction reference absorbed"));
            }
        } catch (Exception redisEx) {
            log.warn("[REDIS FAIL-OPEN] Redis unavailable ({}). Falling through to PostgreSQL Outbox.", redisEx.getMessage());
        }

        // 2. Durable Transactional Outbox write in PostgreSQL (zero dual-write gap)
        int inserted = outboxRepository.insertOutboxEvent(
                payload.merchantId(),
                ref,
                TOPIC,
                rawJson
        );

        if (inserted == 0) {
            log.warn("[OUTBOX IDEMPOTENCY] Event {} already persisted in outbox.", ref);
            return ResponseEntity.ok(new WebhookResponse("ignored", ref, "Duplicate event already durably queued"));
        }

        log.info("[OUTBOX COMMITTED] Stored txn {} in PostgreSQL outbox for merchant {}", ref, payload.merchantId());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new WebhookResponse("queued", ref, "Settlement event durably accepted into outbox"));
    }

    @Transactional
    @PostMapping("/reversal")
    public ResponseEntity<WebhookResponse> handleReversal(
            @RequestHeader(name = "X-Webhook-Signature", required = false) String signature,
            @RequestBody String rawJson) {

        if (!securityService.isValidSignature(rawJson, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new WebhookResponse("rejected", null, "Invalid or missing HMAC signature"));
        }

        ReversalRequest req;
        try {
            req = objectMapper.readValue(rawJson, ReversalRequest.class);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new WebhookResponse("rejected", null, "Malformed JSON payload"));
        }

        SettlementLedger original = ledgerRepository.findByTransactionReference(req.originalTransactionReference())
                .orElse(null);

        if (original == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new WebhookResponse("rejected", req.reversalReference(), "Original transaction not found"));
        }

        // 1. Insert compensating negative ledger row (immutable append-only audit trail)
        int inserted = ledgerRepository.insertLedgerEntryIdempotently(
                req.reversalReference(),
                original.getVirtualAccountNumber(),
                original.getMerchantId(),
                -original.getTotalCreditedKobo(),
                -original.getPlatformFeeKobo(),
                -original.getMerchantPayoutKobo(),
                "REVERSED"
        );

        if (inserted == 0) {
            return ResponseEntity.ok(new WebhookResponse("ignored", req.reversalReference(), "Reversal already processed"));
        }

        // 2. Atomically debit merchant wallet (fails safely if merchant already withdrew funds)
        int debited = walletRepository.debitWalletIfSufficient(
                original.getMerchantId(),
                original.getMerchantPayoutKobo()
        );

        if (debited == 0) {
            throw new IllegalStateException("Insufficient merchant wallet balance for reversal " + req.reversalReference());
        }

        log.warn("[REVERSAL COMMITTED] Reversal {} reversed original txn {} | Debited {} kobo from merchant {}",
                req.reversalReference(), req.originalTransactionReference(),
                original.getMerchantPayoutKobo(), original.getMerchantId());

        return ResponseEntity.ok(new WebhookResponse("reversed", req.reversalReference(), "Compensating reversal applied"));
    }
}