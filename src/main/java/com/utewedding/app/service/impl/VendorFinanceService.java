package com.utewedding.app.service.impl;

import com.utewedding.app.repository.VendorFinanceQueryRepository;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.web.VendorDashboardRange;
import com.utewedding.app.service.web.VendorFinanceHistoryRow;
import com.utewedding.app.service.web.VendorFinanceLabels;
import com.utewedding.app.service.web.VendorFinanceSummary;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only Vendor finance screen and CSV, always resolved from the active owner workspace. */
@Service
public class VendorFinanceService {
    private static final int PAGE_SIZE = 25;
    private static final int EXPORT_LIMIT = 10_000;

    private final VendorWorkspaceContext workspace;
    private final VendorFinanceQueryRepository queries;

    public VendorFinanceService(VendorWorkspaceContext workspace, VendorFinanceQueryRepository queries) {
        this.workspace = workspace;
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    public FinanceView read(UserPrincipal actor, VendorDashboardRange range, int requestedPage) {
        if (range == null) throw new IllegalArgumentException("Finance date range is required.");
        var shop = workspace.requireWorkspace(actor);
        int page = Math.max(0, Math.min(requestedPage, 10_000));
        VendorFinanceSummary summary = queries.summary(shop.shopId(), range.fromInclusive(), range.toExclusive());
        List<VendorFinanceHistoryRow> rows = queries.history(shop.shopId(), range.fromInclusive(),
                range.toExclusive(), page, PAGE_SIZE);
        return new FinanceView(shop.shopName(), range, summary, rows, page, PAGE_SIZE);
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv(UserPrincipal actor, VendorDashboardRange range) {
        if (range == null) throw new IllegalArgumentException("Finance date range is required.");
        var shop = workspace.requireWorkspace(actor);
        List<VendorFinanceHistoryRow> rows = queries.exportRows(shop.shopId(), range.fromInclusive(), range.toExclusive());
        StringBuilder csv = new StringBuilder("\uFEFFLoại,Khoản mục,Trạng thái,Số tiền (VND),Thời điểm,Mã tham chiếu\r\n");
        for (VendorFinanceHistoryRow row : rows) {
            csv.append(text(VendorFinanceLabels.kind(row.kind()))).append(',')
                    .append(text(VendorFinanceLabels.label(row.label()))).append(',')
                    .append(text(VendorFinanceLabels.status(row.status()))).append(',').append(row.amount()).append(',')
                    .append(text(row.occurredAt().toString())).append(',').append(text(row.reference()))
                    .append("\r\n");
        }
        if (rows.size() == EXPORT_LIMIT) csv.append("GHI_CHU,Giới hạn xuất 10.000 dòng,,,,\r\n");
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String text(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    public record FinanceView(String shopName, VendorDashboardRange range, VendorFinanceSummary summary,
                              List<VendorFinanceHistoryRow> rows, int page, int pageSize) { }
}
