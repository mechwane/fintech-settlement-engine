package com.fintech.settlement_service.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "merchant_wallets")
public class MerchantWallet {

    @Id
    @Column(name = "merchant_id", nullable = false, updatable = false)
    private String merchantId;

    @Column(name = "available_balance_kobo", nullable = false)
    private Long availableBalanceKobo;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public MerchantWallet() {}

    public MerchantWallet(String merchantId, Long availableBalanceKobo) {
        this.merchantId = merchantId;
        this.availableBalanceKobo = availableBalanceKobo;
    }

    public String getMerchantId() { return merchantId; }
    public Long getAvailableBalanceKobo() { return availableBalanceKobo; }
    public Instant getUpdatedAt() { return updatedAt; }
}