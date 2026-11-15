package com.fintech.settlement_service.dto;

public record SettlementEvent(
    String transactionReference,
    String virtualAccountNumber,
    Long amountKobo,
    String merchantId
) {}