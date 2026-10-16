package com.utewedding.app.service.impl;

import com.utewedding.app.integration.GhnAddressCatalogClient;
import com.utewedding.app.integration.GhnAddressCatalogClient.CatalogRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;

/** Downloads, validates, and atomically persists the full GHN Staging catalog. */
@Service
public class GhnAddressCatalogSyncService {
    private static final int MAX_CONCURRENT_PROVINCE_REQUESTS = 4;
    private static final int VERIFIED_FIRST_SYNC_PROVINCE_COUNT = 34;
    private static final int VERIFIED_FIRST_SYNC_WARD_COUNT = 3_321;
    private final GhnAddressCatalogClient client;
    private final GhnAddressCatalogPersistenceService persistence;

    public GhnAddressCatalogSyncService(GhnAddressCatalogClient client,
                                        GhnAddressCatalogPersistenceService persistence) {
        this.client = client;
        this.persistence = persistence;
    }

    @SchedulerLock(name = "ghn-address-catalog-sync", lockAtMostFor = "30m")
    public SyncResult sync() {
        Instant attemptedAt = Instant.now();
        try {
            boolean firstSync = !persistence.hasPublishedCatalog();
            List<CatalogRow> provinces = client.fetchProvinces();
            validateProvinces(provinces);
            List<CatalogRow> wards = fetchAllWards(provinces);
            validateCatalog(provinces, wards, firstSync);
            persistence.publish(provinces, wards, attemptedAt);
            return new SyncResult(provinces.size(), wards.size(), attemptedAt, "READY");
        } catch (RuntimeException exception) {
            String reason = safeError(exception.getMessage());
            persistence.recordFailure(attemptedAt, reason);
            throw new IllegalStateException(reason);
        }
    }

    private List<CatalogRow> fetchAllWards(List<CatalogRow> provinces) {
        ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_PROVINCE_REQUESTS);
        try {
            List<Future<ProvinceWards>> requests = new ArrayList<>();
            for (CatalogRow province : provinces) {
                if (province.status() != 1) continue;
                requests.add(executor.submit(() -> new ProvinceWards(province.code(),
                        client.fetchWards(province.code()))));
            }
            List<CatalogRow> result = new ArrayList<>();
            for (Future<ProvinceWards> request : requests) {
                try {
                    ProvinceWards provinceWards = request.get();
                    if (provinceWards.wards() == null || provinceWards.wards().isEmpty()) {
                        throw new IllegalStateException("GHN không trả phường/xã cho tỉnh "
                                + provinceWards.provinceId() + ".");
                    }
                    if (provinceWards.wards().stream().anyMatch(ward ->
                            !provinceWards.provinceId().equals(ward.parentId()))) {
                        throw new IllegalStateException("GHN trả phường/xã không thuộc đúng tỉnh cha.");
                    }
                    result.addAll(provinceWards.wards());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Đồng bộ danh mục GHN bị gián đoạn.");
                } catch (ExecutionException exception) {
                    throw new IllegalStateException("Không tải đủ phường/xã từ GHN Staging.");
                }
            }
            return List.copyOf(result);
        } finally {
            executor.shutdownNow();
        }
    }

    private void validateProvinces(List<CatalogRow> provinces) {
        if (provinces == null || provinces.isEmpty()) throw new IllegalStateException("GHN không trả danh mục tỉnh/thành.");
        Set<String> codes = new HashSet<>();
        long active = 0;
        for (CatalogRow province : provinces) {
            if (!"province".equals(province.type()) || !"1".equals(province.parentId())
                    || !codes.add(province.code())) {
                throw new IllegalStateException("Danh mục tỉnh/thành GHN có kiểu, quan hệ hoặc mã trùng.");
            }
            if (province.status() == 1) active++;
        }
        if (active < 34) throw new IllegalStateException("Danh mục GHN thiếu tỉnh/thành hoạt động (cần tối thiểu 34).");
    }

    private void validateCatalog(List<CatalogRow> provinces, List<CatalogRow> wards, boolean firstSync) {
        Map<String, CatalogRow> parents = new HashMap<>();
        provinces.stream().filter(row -> row.status() == 1).forEach(row -> parents.put(row.code(), row));
        Set<String> codes = new HashSet<>();
        long active = 0;
        for (CatalogRow ward : wards) {
            CatalogRow parent = parents.get(ward.parentId());
            if (!"ward".equals(ward.type()) || parent == null || !codes.add(ward.code())) {
                throw new IllegalStateException("Danh mục phường/xã GHN có kiểu, tỉnh cha hoặc mã trùng.");
            }
            if (ward.status() == 1) active++;
        }
        if (firstSync && (parents.size() != VERIFIED_FIRST_SYNC_PROVINCE_COUNT
                || wards.size() != VERIFIED_FIRST_SYNC_WARD_COUNT || active != VERIFIED_FIRST_SYNC_WARD_COUNT)) {
            throw new IllegalStateException("Lần đồng bộ GHN đầu tiên phải khớp mốc đã xác minh: "
                    + VERIFIED_FIRST_SYNC_PROVINCE_COUNT + " tỉnh/thành và "
                    + VERIFIED_FIRST_SYNC_WARD_COUNT + " phường/xã đang hoạt động.");
        }
        if (wards.size() < VERIFIED_FIRST_SYNC_WARD_COUNT || active < VERIFIED_FIRST_SYNC_WARD_COUNT) {
            throw new IllegalStateException("Danh mục GHN thiếu dữ liệu phường/xã; cần ít nhất "
                    + VERIFIED_FIRST_SYNC_WARD_COUNT + " bản ghi đang hoạt động.");
        }
    }

    private String safeError(String message) {
        String value = message == null || message.isBlank() ? "Đồng bộ danh mục GHN Staging thất bại." : message;
        value = value.replaceAll("(?i)(token|secret|authorization|password)\\s*[:=]\\s*\\S+", "$1=[REDACTED]")
                .replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.substring(0, Math.min(value.length(), 500));
    }

    public record SyncResult(int provinceCount, int wardCount, Instant completedAt, String status) { }
    private record ProvinceWards(String provinceId, List<CatalogRow> wards) { }
}
