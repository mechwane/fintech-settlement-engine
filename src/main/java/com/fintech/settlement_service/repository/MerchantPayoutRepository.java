package com.fintech.settlement_service.repository;

import com.fintech.settlement_service.model.MerchantPayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MerchantPayoutRepository extends JpaRepository<MerchantPayout, Long> {

    Optional<MerchantPayout> findByPayoutReference(String payoutReference);

    @Modifying
    @Query(value = """
            INSERT INTO merchant_payouts (
                payout_reference, merchant_id, destination_bank_code,
                destination_account_number, amount_kobo, status, created_at, updated_at
            ) VALUES (
                :ref, :merchantId, :bankCode,
                :accountNumber, :amountKobo, 'PROCESSING', NOW(), NOW()
            )
            ON CONFLICT (payout_reference) DO NOTHING
            """, nativeQuery = true)
    int insertPayoutIdempotently(
            @Param("ref") String ref,
            @Param("merchantId") String merchantId,
            @Param("bankCode") String bankCode,
            @Param("accountNumber") String accountNumber,
            @Param("amountKobo") long amountKobo
    );

    @Modifying
    @Query(value = """
            UPDATE merchant_payouts
            SET status = 'SUCCESS',
                nibss_session_id = :sessionId,
                updated_at = NOW()
            WHERE payout_reference = :ref
            """, nativeQuery = true)
    void markSuccess(@Param("ref") String ref, @Param("sessionId") String sessionId);

    @Modifying
    @Query(value = """
            UPDATE merchant_payouts
            SET status = 'FAILED_REFUNDED',
                failure_reason = :reason,
                updated_at = NOW()
            WHERE payout_reference = :ref
            """, nativeQuery = true)
    void markFailedRefunded(@Param("ref") String ref, @Param("reason") String reason);
}