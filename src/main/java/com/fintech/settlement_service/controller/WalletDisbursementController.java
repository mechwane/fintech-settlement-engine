package com.fintech.settlement_service.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletDisbursementController {

    private static final Logger log = LoggerFactory.getLogger(WalletDisbursementController.class);
    private final StringRedisTemplate redisTemplate;

    public WalletDisbursementController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public record DisbursementRequest(String accountNumber, long amountKobo) {}

    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seedBalance(
            @RequestParam(defaultValue = "0123456789") String accountNumber,
            @RequestParam(defaultValue = "1000000") long amountKobo) {
        String balanceKey = "wallet:balance:" + accountNumber;
        redisTemplate.opsForValue().set(balanceKey, String.valueOf(amountKobo));
        return ResponseEntity.ok(Map.of(
                "status", "seeded",
                "accountNumber", accountNumber,
                "balanceKobo", amountKobo,
                "balanceNaira", amountKobo / 100.0
        ));
    }

    @PostMapping("/disburse-vulnerable")
    public ResponseEntity<Map<String, Object>> disburseVulnerable(@RequestBody DisbursementRequest request) {
        String balanceKey = "wallet:balance:" + request.accountNumber();
        String currentStr = redisTemplate.opsForValue().get(balanceKey);
        long currentBalance = (currentStr != null) ? Long.parseLong(currentStr) : 0L;

        if (currentBalance < request.amountKobo()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("status", "failed", "reason", "Insufficient balance", "balanceKobo", currentBalance));
        }

        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        long newBalance = currentBalance - request.amountKobo();
        redisTemplate.opsForValue().set(balanceKey, String.valueOf(newBalance));

        log.warn("[TOCTOU DISBURSEMENT] Account: {} | Deducted: {} kobo | New Balance: {} kobo",
                request.accountNumber(), request.amountKobo(), newBalance);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "deductedKobo", request.amountKobo(),
                "remainingBalanceKobo", newBalance,
                "remainingBalanceNaira", newBalance / 100.0
        ));
    }

    @PostMapping("/disburse-secure")
     public ResponseEntity<Map<String, Object>> disburseSecure(@RequestBody DisbursementRequest request) {
        String balanceKey = "wallet:balance:" + request.accountNumber();
        Long remaining = redisTemplate.opsForValue().decrement(balanceKey, request.amountKobo());

        if (remaining != null && remaining < 0) {
            redisTemplate.opsForValue().increment(balanceKey, request.amountKobo());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("status", "failed", "reason", "Insufficient balance"));
        }

        log.info("[SECURE DISBURSEMENT] Account: {} | Deducted: {} kobo | Remaining Balance: {} kobo",
                request.accountNumber(), request.amountKobo(), remaining);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "deductedKobo", request.amountKobo(),
                "remainingBalanceKobo", remaining != null ? remaining : 0,
                "remainingBalanceNoira", (remaining != null ? remaining : 0) / 100.0
        ));
    }
}