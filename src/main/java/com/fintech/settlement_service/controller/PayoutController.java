package com.fintech.settlement_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.settlement_service.model.MerchantPayout;
import com.fintech.settlement_service.repository.MerchantPayoutRepository;
import com.fintech.settlement_service.service.PayoutService;
import com.fintech.settlement_service.service.WebhookSecurityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/payouts")
public class PayoutController {

    private final PayoutService payoutService;
    private final MerchantPayoutRepository payoutRepository;
    private final WebhookSecurityService securityService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PayoutController(
            PayoutService payoutService,
            MerchantPayoutRepository payoutRepository,
            WebhookSecurityService securityService) {
        this.payoutService = payoutService;
        this.payoutRepository = payoutRepository;
        this.securityService = securityService;
    }

    @PostMapping
    public ResponseEntity<PayoutService.PayoutResult> initiatePayout(
            @RequestHeader(name = "X-Webhook-Signature", required = false) String signature,
            @RequestBody String rawJson) {

        if (!securityService.isValidSignature(rawJson, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new PayoutService.PayoutResult(401, "rejected", null, null, null,
                            "Invalid or missing HMAC signature"));
        }

        PayoutService.PayoutRequest req;
        try {
            req = objectMapper.readValue(rawJson, PayoutService.PayoutRequest.class);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new PayoutService.PayoutResult(400, "rejected", null, null, null,
                            "Malformed JSON payload"));
        }

        PayoutService.PayoutResult result = payoutService.executeDisbursement(req);
        return ResponseEntity.status(result.httpStatus()).body(result);
    }

    @GetMapping
    public List<MerchantPayout> listPayouts() {
        return payoutRepository.findAll();
    }
}