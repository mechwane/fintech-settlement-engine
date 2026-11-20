package com.fintech.settlement_service.controller;

import com.fintech.settlement_service.model.VirtualAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.settlement_service.service.WebhookSecurityService;
import com.fintech.settlement_service.service.VirtualAccountRegistryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/virtual-accounts")
public class VirtualAccountController {

    private final VirtualAccountRegistryService registryService;
    private final WebhookSecurityService securityService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VirtualAccountController(VirtualAccountRegistryService registryService, WebhookSecurityService securityService) { this.securityService = securityService;
        this.registryService = registryService;
    }

    public record CreateVirtualAccountRequest(
            String accountNumber,
            String merchantId,
            String merchantName,
            Integer feeBasisPoints,
            Long feeCapKobo
    ) {}

    @PostMapping public ResponseEntity<?> provisionVirtualAccount(@RequestHeader(name = "X-Webhook-Signature", required = false) String sig, @RequestBody String raw) { if (!securityService.isValidSignature(raw, sig)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid or missing HMAC signature")); CreateVirtualAccountRequest req; try { req = objectMapper.readValue(raw, CreateVirtualAccountRequest.class); } catch (Exception e) { return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Malformed JSON")); } if (req.accountNumber() == null || req.accountNumber().isBlank() || req.merchantId() == null || req.merchantId().isBlank() || req.merchantName() == null || req.merchantName().isBlank() || req.feeBasisPoints() == null || req.feeBasisPoints() < 0 || req.feeBasisPoints() > 10_000 || req.feeCapKobo() == null || req.feeCapKobo() < 0) return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Invalid fields or feeBasisPoints outside 0..10000")); try { return ResponseEntity.status(HttpStatus.CREATED).body(registryService.registerAccount(req.accountNumber().trim(), req.merchantId().trim(), req.merchantName().trim(), req.feeBasisPoints(), req.feeCapKobo())); } catch (VirtualAccountRegistryService.DuplicateAccountException d) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", d.getMessage())); } }

    @GetMapping
    public List<VirtualAccount> listVirtualAccounts() {
        return registryService.listAllAccounts();
    }
}