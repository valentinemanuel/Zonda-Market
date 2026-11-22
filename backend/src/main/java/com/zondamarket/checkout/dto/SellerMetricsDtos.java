package com.zondamarket.checkout.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SellerMetricsDtos {
    private SellerMetricsDtos() {}

    public record Day(LocalDate date, String gross, String commission, String refunds, String commissionReversed, String net) {}

    public record SellerMetricsResponse(LocalDate from, LocalDate to, String currency, String timeZone,
            String gross, String commission, String refunds, String commissionReversed, String net,
            Map<String, Long> ordersByState, List<Day> series) {}

    public record Settlement(UUID subOrderId, String status, Instant recordedAt, Instant lastActivityAt,
            String merchandise, String shipping, String gross, String commission, String refunds,
            String commissionReversed, String net) {}

    public record SettlementPage(List<Settlement> content, int page, int size, boolean hasNext) {}
}
