package com.fintech.settlement_service.controller;

import com.fintech.settlement_service.model.VirtualAccount;
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

    public VirtualAccountController(VirtualAccountRegistryService registryService) {
        this.registryService = registryService;
    }

    public record CreateVirtualAccountRequest(
            String accountNumber,
            String merchantId,
            String merchantName,
            Integer feeBasisPoints,
            Long feeCapKobo
    ) {}

    @PostMapping
    public ResponseEntity<?> provisionVirtualAccount(@RequestBody CreateVirtualAccountRequest req) {
        if (req.accountNumber() == null || req.accountNumber().isBlank()
                || req.merchantId() == null || req.merchantId().isBlank()
                || req.merchantName() == null || req.merchantName().isBlank()
                || req.feeBasisPoints() == null || req.feeBasisPoints() < 0
                || req.feeCapKobo() == null || req.feeCapKobo() < 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "All virtual account fields are required with non-negative fee tiers"));
        }

        VirtualAccount created = registryService.registerAccount(
                req.accountNumber(),
                req.merchantId(),
                req.merchantName(),
                req.feeBasisPoints(),
                req.feeCapKobo()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<VirtualAccount> listVirtualAccounts() {
        return registryService.listAllAccounts();
    }
}