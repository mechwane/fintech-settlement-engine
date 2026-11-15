package com.fintech.settlement_service.repository;

import com.fintech.settlement_service.model.MerchantWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MerchantWalletRepository extends JpaRepository<MerchantWallet, String> {

    @Modifying
    @Query(value = """
            INSERT INTO merchant_wallets (merchant_id, available_balance_kobo, updated_at)
            VALUES (:merchantId, :payoutKobo, NOW())
            ON CONFLICT (merchant_id) DO UPDATE
            SET available_balance_kobo = merchant_wallets.available_balance_kobo + EXCLUDED.available_balance_kobo,
                updated_at = NOW()
            """, nativeQuery = true)
    void creditWalletAtomically(@Param("merchantId") String merchantId, @Param("payoutKobo") long payoutKobo);

    @Modifying
    @Query(value = """
            UPDATE merchant_wallets
            SET available_balance_kobo = available_balance_kobo - :debitKobo,
                updated_at = NOW()
            WHERE merchant_id = :merchantId
              AND available_balance_kobo >= :debitKobo
            """, nativeQuery = true)
    int debitWalletIfSufficient(@Param("merchantId") String merchantId, @Param("debitKobo") long debitKobo);

    @Query(value = "SELECT COALESCE((SELECT available_balance_kobo FROM merchant_wallets WHERE merchant_id = :merchantId), 0)", nativeQuery = true)
    Long getAvailableBalanceKobo(@Param("merchantId") String merchantId);
}