package com.fintech.settlement_service.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "merchant_payouts")
public class MerchantPayout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payout_reference", nullable = false, unique = true, updatable = false)
    private String payoutReference;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private String merchantId;

    @Column(name = "destination_bank_code", nullable = false, updatable = false)
    private String destinationBankCode;

    @Column(name = "destination_account_number", nullable = false, updatable = false)
    private String destinationAccountNumber;

    @Column(name = "amount_kobo", nullable = false, updatable = false)
    private Long amountKobo;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "nibss_session_id")
    private String nibssSessionId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public MerchantPayout() {}

    public Long getId() { return id; }
    public String getPayoutReference() { return payoutReference; }
    public String getMerchantId() { return merchantId; }
    public String getDestinationBankCode() { return destinationBankCode; }
    public String getDestinationAccountNumber() { return destinationAccountNumber; }
    public Long getAmountKobo() { return amountKobo; }
    public String getStatus() { return status; }
    public String getNibssSessionId() { return nibssSessionId; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}