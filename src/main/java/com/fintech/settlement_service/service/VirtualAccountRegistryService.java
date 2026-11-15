package com.fintech.settlement_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.settlement_service.model.VirtualAccount;
import com.fintech.settlement_service.repository.VirtualAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class VirtualAccountRegistryService {

    private static final Logger log = LoggerFactory.getLogger(VirtualAccountRegistryService.class);
    private static final String CACHE_PREFIX = "va:tier:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private static final int DEFAULT_FEE_BPS = 150;       // 1.50%
    private static final long DEFAULT_FEE_CAP_KOBO = 200000L; // NGN 2,000.00

    private final VirtualAccountRepository vaRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VirtualAccountRegistryService(
            VirtualAccountRepository vaRepository,
            StringRedisTemplate redisTemplate) {
        this.vaRepository = vaRepository;
        this.redisTemplate = redisTemplate;
    }

    public record AccountTier(
            String accountNumber,
            String merchantId,
            String merchantName,
            int feeBasisPoints,
            long feeCapKobo,
            boolean active
    ) {}

    @EventListener(ApplicationReadyEvent.class)
    public void seedDefaultVirtualAccounts() {
        if (!vaRepository.existsById("0123456789")) {
            VirtualAccount defaultAccount = new VirtualAccount(
                    "0123456789",
                    "MCH-8821",
                    "Standard Merchant Core",
                    DEFAULT_FEE_BPS,
                    DEFAULT_FEE_CAP_KOBO,
                    true
            );
            vaRepository.save(defaultAccount);
            log.info("[VA SEEDED] Provisioned default virtual account 0123456789 -> MCH-8821 (150 bps, cap 200000 kobo)");
        }
    }

    public VirtualAccount registerAccount(
            String accountNumber,
            String merchantId,
            String merchantName,
            int feeBasisPoints,
            long feeCapKobo) {
        VirtualAccount entity = new VirtualAccount(
                accountNumber, merchantId, merchantName, feeBasisPoints, feeCapKobo, true
        );
        VirtualAccount saved = vaRepository.save(entity);

        AccountTier tier = new AccountTier(
                saved.getAccountNumber(),
                saved.getMerchantId(),
                saved.getMerchantName(),
                saved.getFeeBasisPoints(),
                saved.getFeeCapKobo(),
                saved.getActive()
        );
        writeToRedisSafely(accountNumber, tier);
        log.info("[VA PROVISIONED] Account {} -> Merchant '{}' ({}) | Tier: {} bps, Cap: {} kobo",
                accountNumber, merchantName, merchantId, feeBasisPoints, feeCapKobo);
        return saved;
    }

    public List<VirtualAccount> listAllAccounts() {
        return vaRepository.findAll();
    }

    public AccountTier resolveAccountTier(String accountNumber, String fallbackMerchantId) {
        if (accountNumber != null && !accountNumber.isBlank()) {
            // 1. Fast-Path Redis Cache Lookup (Fail-Open)
            try {
                String cachedJson = redisTemplate.opsForValue().get(CACHE_PREFIX + accountNumber);
                if (cachedJson != null) {
                    AccountTier hit = objectMapper.readValue(cachedJson, AccountTier.class);
                    log.info("[VA CACHE HIT] Account {} -> Merchant {} ({} bps, cap {} kobo)",
                            accountNumber, hit.merchantId(), hit.feeBasisPoints(), hit.feeCapKobo());
                    return hit;
                }
            } catch (Exception e) {
                log.warn("[VA CACHE BYPASS] Redis read failed for {}: {}. Falling back to PostgreSQL.",
                        accountNumber, e.getMessage());
            }

            // 2. Cache Miss -> Load from PostgreSQL & Populate Redis
            Optional<VirtualAccount> dbOpt = vaRepository.findById(accountNumber);
            if (dbOpt.isPresent()) {
                VirtualAccount va = dbOpt.get();
                AccountTier loaded = new AccountTier(
                        va.getAccountNumber(),
                        va.getMerchantId(),
                        va.getMerchantName(),
                        va.getFeeBasisPoints(),
                        va.getFeeCapKobo(),
                        va.getActive()
                );
                writeToRedisSafely(accountNumber, loaded);
                log.info("[VA CACHE MISS -> DB LOADED] Account {} -> Merchant {} ({} bps, cap {} kobo)",
                        accountNumber, loaded.merchantId(), loaded.feeBasisPoints(), loaded.feeCapKobo());
                return loaded;
            }
        }

        // 3. Fallback Standard Tier for Unregistered Test Accounts
        return new AccountTier(
                accountNumber != null ? accountNumber : "UNREGISTERED",
                fallbackMerchantId,
                "Unregistered Standard Tier",
                DEFAULT_FEE_BPS,
                DEFAULT_FEE_CAP_KOBO,
                true
        );
    }

    public long calculateFeeKobo(long amountKobo, AccountTier tier) {
        BigDecimal gross = BigDecimal.valueOf(amountKobo);
        BigDecimal bps = BigDecimal.valueOf(tier.feeBasisPoints());
        BigDecimal rawFee = gross.multiply(bps)
                .divide(BigDecimal.valueOf(10_000L), 0, RoundingMode.HALF_UP);
        return Math.min(rawFee.longValue(), tier.feeCapKobo());
    }

    private void writeToRedisSafely(String accountNumber, AccountTier tier) {
        try {
            String json = objectMapper.writeValueAsString(tier);
            redisTemplate.opsForValue().set(CACHE_PREFIX + accountNumber, json, CACHE_TTL);
        } catch (Exception e) {
            log.warn("[VA CACHE WRITE SKIPPED] Could not cache {} in Redis: {}", accountNumber, e.getMessage());
        }
    }
}