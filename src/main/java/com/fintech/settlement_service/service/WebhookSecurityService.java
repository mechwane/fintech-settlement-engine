package com.fintech.settlement_service.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class WebhookSecurityService {

    private static final Logger log = LoggerFactory.getLogger(WebhookSecurityService.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    @Value("${security.webhook.secret:nibss-super-secret-key-2026}")
    private String secretKey;

    @Value("${security.webhook.enforce:true}")
    private boolean enforceSignature;

    public boolean isValidSignature(String rawPayload, String signatureHeader) {
        if (!enforceSignature) {
            log.warn("[LAB WARNING] Webhook signature enforcement is DISABLED. Allowing payload without verification.");
            return true;
        }

        if (signatureHeader == null || signatureHeader.isBlank() || rawPayload == null) {
            log.error("Signature check failed: header or payload is null/empty");
            return false;
        }

        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);

            byte[] hmacBytes = mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = HexFormat.of().formatHex(hmacBytes);

            // Constant-time comparison to prevent side-channel timing attacks
            return MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.trim().toLowerCase().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Error calculating webhook HMAC signature", e);
            return false;
        }
    }

    public String computeSignature(String rawPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate test signature", e);
        }
    }
}