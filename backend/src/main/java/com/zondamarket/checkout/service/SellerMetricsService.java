package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.SellerMetricsDtos.Day;
import com.zondamarket.checkout.dto.SellerMetricsDtos.SellerMetricsResponse;
import com.zondamarket.checkout.dto.SellerMetricsDtos.Settlement;
import com.zondamarket.checkout.dto.SellerMetricsDtos.SettlementPage;
import com.zondamarket.checkout.repository.SellerMetricsStore;
import com.zondamarket.checkout.repository.SellerMetricsStore.Amounts;
import com.zondamarket.checkout.repository.SellerMetricsStore.Entry;
import com.zondamarket.identity.domain.UserAccount.Role;
import com.zondamarket.identity.domain.SellerProfile;
import com.zondamarket.identity.repository.SellerProfileRepository;
import com.zondamarket.shared.errors.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SellerMetricsService {
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final int MAX_DAYS = 90;
    private final SellerMetricsStore store;
    private final SellerProfileRepository sellers;
    private final Clock clock;

    SellerMetricsService(SellerMetricsStore store, SellerProfileRepository sellers, Clock clock) {
        this.store = store;
        this.sellers = sellers;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SellerMetricsResponse metrics(UUID account, String from, String to) {
        UUID seller = sellerId(account);
        Range range = range(from, to);
        var daily = store.daily(seller, range.start(), range.end(), ZONE.getId());
        var ordersByState = store.states(seller, range.start(), range.end());
        var series = new ArrayList<Day>();
        Amounts total = Amounts.EMPTY;
        for (var date = range.from(); !date.isAfter(range.to()); date = date.plusDays(1)) {
            var values = daily.getOrDefault(date, Amounts.EMPTY);
            total = total.plus(values);
            series.add(new Day(date, values.gross().toPlainString(), values.commission().toPlainString(),
                    values.refunds().toPlainString(), values.reversed().toPlainString(), values.net().toPlainString()));
        }
        return new SellerMetricsResponse(range.from(), range.to(), "ARS", ZONE.getId(), total.gross().toPlainString(),
                total.commission().toPlainString(), total.refunds().toPlainString(), total.reversed().toPlainString(),
                total.net().toPlainString(), ordersByState, List.copyOf(series));
    }

    @Transactional(readOnly = true)
    public SettlementPage settlements(UUID account, String from, String to, int page, int size) {
        UUID seller = sellerId(account);
        Range range = range(from, to);
        var entries = store.settlements(seller, range.start(), range.end(), page, size);
        return new SettlementPage(entries.stream().limit(size).map(SellerMetricsService::response).toList(),
                page, size, entries.size() > size);
    }

    private UUID sellerId(UUID account) {
        return sellers.findByAccountIdAndDeletedFalseAndAccountDeletedFalseAndAccountRole(account, Role.SELLER)
                .map(SellerProfile::getId)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.FORBIDDEN, "SELLER_REQUIRED", "Activá tu perfil de vendedor."));
    }

    private Range range(String from, String to) {
        if (from == null && to == null) {
            var today = LocalDate.now(clock.withZone(ZONE));
            return new Range(today.minusDays(29), today);
        }
        if (from == null || to == null || !from.matches("\\d{4}-\\d{2}-\\d{2}") || !to.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw invalidRange();
        }
        try {
            var start = LocalDate.parse(from);
            var end = LocalDate.parse(to);
            if (start.getYear() == 0 || end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
                throw invalidRange();
            }
            return new Range(start, end);
        } catch (DateTimeParseException exception) {
            throw invalidRange();
        }
    }

    private static ApiProblemException invalidRange() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_SELLER_DATE_RANGE",
                "Elegí un período de hasta 90 días corridos, desde la fecha menor hasta la mayor.");
    }

    private static Settlement response(Entry entry) {
        var amounts = entry.amounts();
        return new Settlement(entry.subOrderId(), entry.status(), entry.recordedAt(), entry.lastActivityAt(),
                amounts.merchandise().toPlainString(), amounts.shipping().toPlainString(), amounts.gross().toPlainString(),
                amounts.commission().toPlainString(), amounts.refunds().toPlainString(), amounts.reversed().toPlainString(),
                amounts.net().toPlainString());
    }

    private record Range(LocalDate from, LocalDate to) {
        Instant start() { return from.atStartOfDay(ZONE).toInstant(); }
        Instant end() { return to.plusDays(1).atStartOfDay(ZONE).toInstant(); }
    }
}
