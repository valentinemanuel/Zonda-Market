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
import com.zondamarket.shared.time.ReportingPeriod;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SellerMetricsService {
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
        var range = ReportingPeriod.parse(from, to, clock, "INVALID_SELLER_DATE_RANGE");
        var daily = store.daily(seller, range.start(), range.end(), ReportingPeriod.ZONE.getId());
        var ordersByState = store.states(seller, range.start(), range.end());
        var series = new ArrayList<Day>();
        Amounts total = Amounts.EMPTY;
        for (var date = range.from(); !date.isAfter(range.to()); date = date.plusDays(1)) {
            var values = daily.getOrDefault(date, Amounts.EMPTY);
            total = total.plus(values);
            series.add(new Day(date, values.gross().toPlainString(), values.commission().toPlainString(),
                    values.refunds().toPlainString(), values.reversed().toPlainString(), values.net().toPlainString()));
        }
        return new SellerMetricsResponse(range.from(), range.to(), "ARS", ReportingPeriod.ZONE.getId(), total.gross().toPlainString(),
                total.commission().toPlainString(), total.refunds().toPlainString(), total.reversed().toPlainString(),
                total.net().toPlainString(), ordersByState, List.copyOf(series));
    }

    @Transactional(readOnly = true)
    public SettlementPage settlements(UUID account, String from, String to, int page, int size) {
        UUID seller = sellerId(account);
        var range = ReportingPeriod.parse(from, to, clock, "INVALID_SELLER_DATE_RANGE");
        var entries = store.settlements(seller, range.start(), range.end(), page, size);
        return new SettlementPage(entries.stream().limit(size).map(SellerMetricsService::response).toList(),
                page, size, entries.size() > size);
    }

    private UUID sellerId(UUID account) {
        return sellers.findByAccountIdAndDeletedFalseAndAccountDeletedFalseAndAccountRole(account, Role.SELLER)
                .map(SellerProfile::getId)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.FORBIDDEN, "SELLER_REQUIRED", "Activá tu perfil de vendedor."));
    }

    private static Settlement response(Entry entry) {
        var amounts = entry.amounts();
        return new Settlement(entry.subOrderId(), entry.status(), entry.recordedAt(), entry.lastActivityAt(),
                amounts.merchandise().toPlainString(), amounts.shipping().toPlainString(), amounts.gross().toPlainString(),
                amounts.commission().toPlainString(), amounts.refunds().toPlainString(), amounts.reversed().toPlainString(),
                amounts.net().toPlainString());
    }
}
