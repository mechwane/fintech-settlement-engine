package com.fintech.settlement_service.repository;

import com.fintech.settlement_service.model.SettlementLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SettlementLedgerRepository extends JpaRepository<SettlementLedger, Long> {
    Optional<SettlementLedger> findByTransactionReference(String transactionReference);
    boolean existsByTransactionReference(String transactionReference); @Modifying @Query(value="UPDATE settlement_ledger SET status='REVERSED' WHERE transaction_reference=:ref AND status='SETTLED'",nativeQuery=true) int markOriginalAsReversed(@Param("ref") String ref);

    @Modifying
    @Query(value = """
            INSERT INTO settlement_ledger (
                transaction_reference, virtual_account_number, merchant_id,
                total_credited_kobo, platform_fee_kobo, merchant_payout_kobo,
                status, created_at
            ) VALUES (
                :ref, :virtualAccount, :merchantId,
                :totalKobo, :feeKobo, :payoutKobo,
                :status, NOW()
            )
            ON CONFLICT (transaction_reference) DO NOTHING
            """, nativeQuery = true)
    int insertLedgerEntryIdempotently(
            @Param("ref") String ref,
            @Param("virtualAccount") String virtualAccount,
            @Param("merchantId") String merchantId,
            @Param("totalKobo") long totalKobo,
            @Param("feeKobo") long feeKobo,
            @Param("payoutKobo") long payoutKobo,
            @Param("status") String status
    );
}