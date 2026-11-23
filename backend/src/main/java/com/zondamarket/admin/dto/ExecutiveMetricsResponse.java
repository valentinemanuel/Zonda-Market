package com.zondamarket.admin.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ExecutiveMetricsResponse(LocalDate from, LocalDate to, String currency, String timeZone,
        String gmv, String refunds, String platformCommission, long paidOrders, long disputes, long openCases,
        List<Day> series, List<Seller> sellers, List<Category> categories, boolean breakdownLimited) {

    public record Day(LocalDate date, String gmv, String refunds, String platformCommission, long paidOrders, long disputes) {}
    public record Seller(UUID id, String name, String gmv, String refunds, String platformCommission) {}
    public record Category(UUID id, String name, String merchandise, String originalCommission) {}
}
