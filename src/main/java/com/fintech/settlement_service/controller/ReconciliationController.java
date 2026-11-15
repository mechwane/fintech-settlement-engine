package com.fintech.settlement_service.controller;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.Map;
@RestController
@RequestMapping("/api/v1/settlements")
public class ReconciliationController {
    private final JdbcTemplate jdbcTemplate;
    public ReconciliationController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    @GetMapping("/reconcile")
    public Map runReconciliation() {
        Long unpublishedOutbox = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE status != 'PUBLISHED'", Long.class);
        Long netLedgerPayoutKobo = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(merchant_payout_kobo), 0) FROM settlement_ledger", Long.class);
        Long totalWalletBalanceKobo = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(available_balance_kobo), 0) FROM merchant_wallets", Long.class);
        Long totalPlatformFeesKobo = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(platform_fee_kobo), 0) FROM settlement_ledger", Long.class);
        long discrepancyKobo = netLedgerPayoutKobo - totalWalletBalanceKobo;
        boolean balanced = (discrepancyKobo == 0L && unpublishedOutbox == 0L);
        var merchantBreakdown = jdbcTemplate.queryForList("SELECT w.merchant_id, w.available_balance_kobo AS wallet_kobo, COALESCE(SUM(l.merchant_payout_kobo), 0) AS ledger_net_kobo, (w.available_balance_kobo - COALESCE(SUM(l.merchant_payout_kobo), 0)) AS variance_kobo FROM merchant_wallets w LEFT JOIN settlement_ledger l ON w.merchant_id = l.merchant_id GROUP BY w.merchant_id, w.available_balance_kobo");
        var report = new LinkedHashMap();
        report.put("reconciliationStatus", balanced ? "BALANCED_OK" : "DISCREPANCY_DETECTED");
        report.put("pendingOutboxEvents", unpublishedOutbox);
        report.put("netLedgerMerchantPayoutKobo", netLedgerPayoutKobo);
        report.put("totalMerchantWalletBalanceKobo", totalWalletBalanceKobo);
        report.put("totalPlatformRevenueKobo", totalPlatformFeesKobo);
        report.put("discrepancyKobo", discrepancyKobo);
        report.put("merchantAudit", merchantBreakdown);
        return report;
    }
}