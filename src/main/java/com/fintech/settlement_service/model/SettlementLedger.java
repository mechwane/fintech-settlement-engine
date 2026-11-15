package com.fintech.settlement_service.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "settlement_ledger")
public class SettlementLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_reference", nullable = false, unique = true, updatable = false)
    private String transactionReference;

    @Column(name = "virtual_account_number", nullable = false, updatable = false)
    private String virtualAccountNumber;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private String merchantId;

    @Column(name = "total_credited_kobo", nullable = false, updatable = false)
    private Long totalCreditedKobo;

    @Column(name = "platform_fee_kobo", nullable = false, updatable = false)
    private Long platformFeeKobo;

    @Column(name = "merchant_payout_kobo", nullable = false, updatable = false)
    private Long merchantPayoutKobo;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public SettlementLedger() {}

    public SettlementLedger(
            String transactionReference,
            String virtualAccountNumber,
            String merchantId,
            Long totalCreditedKobo,
            Long platformFeeKobo,
            Long merchantPayoutKobo,
            String status) {
        this.transactionReference = transactionReference;
        this.virtualAccountNumber = virtualAccountNumber;
        this.merchantId = merchantId;
        this.totalCreditedKobo = totalCreditedKobo;
        this.platformFeeKobo = platformFeeKobo;
        this.merchantPayoutKobo = merchantPayoutKobo;
        this.status = status;
    }

    public Long getId() { return id; }
    public String getTransactionReference() { return transactionReference; }
    public String getVirtualAccountNumber() { return virtualAccountNumber; }
    public String getMerchantId() { return merchantId; }
    public Long getTotalCreditedKobo() { return totalCreditedKobo; }
    public Long getPlatformFeeKobo() { return platformFeeKobo; }
    public Long getMerchantPayoutKobo() { return merchantPayoutKobo; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
