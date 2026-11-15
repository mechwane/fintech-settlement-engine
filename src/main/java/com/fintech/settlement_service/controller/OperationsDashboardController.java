package com.fintech.settlement_service.controller;
import java.util.LinkedHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping("/api/v1/dashboard")
public class OperationsDashboardController {
    private final JdbcTemplate jdbcTemplate;
    private final ReconciliationController reconciliationController;
    public OperationsDashboardController(JdbcTemplate jdbcTemplate, ReconciliationController reconciliationController) {
        this.jdbcTemplate = jdbcTemplate;
        this.reconciliationController = reconciliationController;
    }
    @GetMapping("/overview")
    public Object getDashboardOverview() {
        var payload = new LinkedHashMap();
        payload.put("reconciliation", reconciliationController.runReconciliation());
        payload.put("ledger", jdbcTemplate.queryForList("SELECT * FROM settlement_ledger ORDER BY id DESC LIMIT 25"));
        payload.put("outbox", jdbcTemplate.queryForList("SELECT * FROM outbox_events ORDER BY id DESC LIMIT 15"));
        payload.put("payouts", jdbcTemplate.queryForList("SELECT * FROM merchant_payouts ORDER BY id DESC LIMIT 15"));
        payload.put("virtualAccounts", jdbcTemplate.queryForList("SELECT * FROM virtual_accounts ORDER BY created_at DESC"));
        payload.put("dltEvents", jdbcTemplate.queryForList("SELECT * FROM dead_letter_events ORDER BY id DESC LIMIT 15"));
        return payload;
    }
}