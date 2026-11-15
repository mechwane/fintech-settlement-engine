package com.fintech.settlement_service.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "virtual_accounts")
public class VirtualAccount {

    @Id
    @Column(name = "account_number", nullable = false, unique = true)
    private String accountNumber;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "merchant_name", nullable = false)
    private String merchantName;

    @Column(name = "fee_basis_points", nullable = false)
    private Integer feeBasisPoints;

    @Column(name = "fee_cap_kobo", nullable = false)
    private Long feeCapKobo;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public VirtualAccount() {}

    public VirtualAccount(
            String accountNumber,
            String merchantId,
            String merchantName,
            Integer feeBasisPoints,
            Long feeCapKobo,
            Boolean active) {
        this.accountNumber = accountNumber;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.feeBasisPoints = feeBasisPoints;
        this.feeCapKobo = feeCapKobo;
        this.active = active;
        this.createdAt = Instant.now();
    }

    public String getAccountNumber() { return accountNumber; }
    public String getMerchantId() { return merchantId; }
    public String getMerchantName() { return merchantName; }
    public Integer getFeeBasisPoints() { return feeBasisPoints; }
    public Long getFeeCapKobo() { return feeCapKobo; }
    public Boolean getActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}