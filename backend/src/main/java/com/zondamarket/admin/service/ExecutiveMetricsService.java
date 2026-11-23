package com.zondamarket.admin.service;

import com.zondamarket.admin.dto.ExecutiveMetricsResponse;
import com.zondamarket.admin.dto.ExecutiveMetricsResponse.Day;
import com.zondamarket.admin.repository.ExecutiveMetricsStore;
import com.zondamarket.admin.repository.ExecutiveMetricsStore.Totals;
import com.zondamarket.shared.errors.ApiProblemException;
import com.zondamarket.shared.time.ReportingPeriod;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutiveMetricsService {
    private static final int BREAKDOWN_LIMIT = 1000;
    private final ExecutiveMetricsStore store;
    private final Clock clock;

    ExecutiveMetricsService(ExecutiveMetricsStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExecutiveMetricsResponse dashboard(String from, String to) {
        return metrics(from, to);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SalesExport export(String from, String to) {
        var report = metrics(from, to);
        if (report.breakdownLimited()) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_CONTENT, "SALES_EXPORT_LIMIT",
                    "Elegí un período más corto para exportar hasta 1.000 filas por desglose.");
        }

        var csv = new StringBuilder("\uFEFF");
        row(csv, "scope", "from", "to", "currency", "timeZone", "date", "id", "name", "gmv", "refunds",
                "platformCommission", "paidOrders", "disputes", "openCases", "merchandise", "originalCommission");
        row(csv, "total", report.from(), report.to(), report.currency(), report.timeZone(), "", "", "", report.gmv(),
                report.refunds(), report.platformCommission(), report.paidOrders(), report.disputes(), report.openCases(), "", "");
        for (var day : report.series()) {
            row(csv, "day", report.from(), report.to(), report.currency(), report.timeZone(), day.date(), "", "", day.gmv(),
                    day.refunds(), day.platformCommission(), day.paidOrders(), day.disputes(), "", "", "");
        }
        for (var seller : report.sellers()) {
            row(csv, "seller", report.from(), report.to(), report.currency(), report.timeZone(), "", seller.id(),
                    csvName(seller.name()), seller.gmv(), seller.refunds(), seller.platformCommission(), "", "", "", "", "");
        }
        for (var category : report.categories()) {
            row(csv, "category", report.from(), report.to(), report.currency(), report.timeZone(), "", category.id(),
                    csvName(category.name()), "", "", "", "", "", "", category.merchandise(), category.originalCommission());
        }
        return new SalesExport("ventas-" + report.from() + "-" + report.to() + ".csv", csv.toString());
    }

    private ExecutiveMetricsResponse metrics(String from, String to) {
        var period = ReportingPeriod.parse(from, to, clock, "INVALID_ADMIN_DATE_RANGE");
        var daily = store.daily(period);
        var series = new ArrayList<Day>();
        var total = Totals.EMPTY;
        for (var date = period.from(); !date.isAfter(period.to()); date = date.plusDays(1)) {
            var day = daily.getOrDefault(date, Totals.EMPTY);
            total = total.plus(day);
            series.add(new Day(date, day.gmv().toPlainString(), day.refunds().toPlainString(),
                    day.commission().toPlainString(), day.paidOrders(), day.disputes()));
        }
        var sellers = store.sellers(period, BREAKDOWN_LIMIT + 1);
        var categories = store.categories(period, BREAKDOWN_LIMIT + 1);
        return new ExecutiveMetricsResponse(period.from(), period.to(), "ARS", ReportingPeriod.ZONE.getId(),
                total.gmv().toPlainString(), total.refunds().toPlainString(), total.commission().toPlainString(),
                total.paidOrders(), total.disputes(), store.openCases(), List.copyOf(series),
                sellers.stream().limit(BREAKDOWN_LIMIT).toList(), categories.stream().limit(BREAKDOWN_LIMIT).toList(),
                sellers.size() > BREAKDOWN_LIMIT || categories.size() > BREAKDOWN_LIMIT);
    }

    private static String csvName(String value) {
        // Spreadsheet importers may skip whitespace or interpret full-width formula prefixes.
        var leading = value.codePoints().dropWhile(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)
                || Character.isISOControl(c) || Character.getType(c) == Character.FORMAT).findFirst();
        if (value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n")
                || (leading.isPresent() && "=+-@＝＋－＠".indexOf(leading.getAsInt()) >= 0)) {
            return "'" + value;
        }
        return value;
    }

    private static void row(StringBuilder csv, Object... fields) {
        var cells = new java.util.StringJoiner(",");
        for (var field : fields) {
            String value = field.toString();
            if (value.contains(",") || value.contains("\"") || value.contains("\r") || value.contains("\n")) {
                value = "\"" + value.replace("\"", "\"\"") + "\"";
            }
            cells.add(value);
        }
        csv.append(cells).append("\r\n");
    }

    public record SalesExport(String filename, String content) {}
}
