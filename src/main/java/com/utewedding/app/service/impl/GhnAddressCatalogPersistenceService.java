package com.utewedding.app.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utewedding.app.config.GhnConfig;
import com.utewedding.app.domain.entity.AdministrativeAddressCatalogStateEntity;
import com.utewedding.app.integration.GhnAddressCatalogClient.CatalogRow;
import com.utewedding.app.repository.AdministrativeAddressCatalogStateRepository;
import com.utewedding.app.repository.ProvinceRepository;
import com.utewedding.app.repository.WardRepository;
import com.utewedding.app.service.AdministrativeAddressService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publishes a completely validated catalog in one database transaction. */
@Service
public class GhnAddressCatalogPersistenceService {
    private final JdbcTemplate jdbc;
    private final ProvinceRepository provinces;
    private final WardRepository wards;
    private final AdministrativeAddressCatalogStateRepository state;
    private final ObjectMapper mapper;
    private final GhnConfig ghnConfig;

    public GhnAddressCatalogPersistenceService(JdbcTemplate jdbc, ProvinceRepository provinces,
            WardRepository wards, AdministrativeAddressCatalogStateRepository state, ObjectMapper mapper,
            GhnConfig ghnConfig) {
        this.jdbc = jdbc;
        this.provinces = provinces;
        this.wards = wards;
        this.state = state;
        this.mapper = mapper;
        this.ghnConfig = ghnConfig;
    }

    @Transactional(readOnly = true)
    public boolean hasPublishedCatalog() {
        return !activeCodes("provinces").isEmpty() || !activeCodes("wards").isEmpty();
    }

    @Transactional
    public void publish(List<CatalogRow> provinceRows, List<CatalogRow> wardRows, Instant syncedAt) {
        AdministrativeAddressCatalogStateEntity catalogState = state.findByIdForUpdate((short) 1)
                .orElseThrow(() -> new IllegalStateException("Thiếu trạng thái danh mục địa chỉ."));
        Set<String> previouslyActiveProvinces = activeCodes("provinces");
        Set<String> previouslyActiveWards = activeCodes("wards");
        Set<String> incomingActiveProvinces = provinceRows.stream().filter(row -> row.status() == 1)
                .map(CatalogRow::code).collect(Collectors.toSet());
        Set<String> incomingActiveWards = wardRows.stream().filter(row -> row.status() == 1
                        && incomingActiveProvinces.contains(row.parentId()))
                .map(CatalogRow::code).collect(Collectors.toSet());

        if (previouslyActiveProvinces.isEmpty() && previouslyActiveWards.isEmpty()) {
            if (incomingActiveProvinces.size() != 34 || wardRows.size() != 3_321
                    || incomingActiveWards.size() != 3_321) {
                throw new IllegalStateException("Không công bố GHN lần đầu: dữ liệu phải khớp mốc đã xác minh "
                        + "34 tỉnh/thành và 3.321 phường/xã hoạt động.");
            }
        } else {
            requireNoMissingActiveIds("tỉnh/thành", previouslyActiveProvinces, incomingActiveProvinces);
            requireNoMissingActiveIds("phường/xã", previouslyActiveWards, incomingActiveWards);
        }

        requireNoSourceCollisions(provinces.findAllById(provinceRows.stream().map(CatalogRow::code).toList())
                .stream().collect(Collectors.toMap(item -> item.getCode(), item -> item.getCatalogSource())),
                provinceRows, "tỉnh/thành");
        requireNoSourceCollisions(wards.findAllById(wardRows.stream().map(CatalogRow::code).toList())
                .stream().collect(Collectors.toMap(item -> item.getCode(), item -> item.getCatalogSource())),
                wardRows, "phường/xã");

        jdbc.update("UPDATE provinces SET is_active = FALSE WHERE catalog_source = 'GHN_STAGING'");
        jdbc.update("UPDATE wards SET is_active = FALSE WHERE catalog_source = 'GHN_STAGING'");
        upsertProvinces(provinceRows, syncedAt);
        upsertWards(provinceRows, wardRows, syncedAt);

        catalogState.setLastStatus("READY");
        catalogState.setLastAttemptAt(syncedAt);
        catalogState.setLastSuccessAt(syncedAt);
        catalogState.setProvinceCount(provinceRows.size());
        catalogState.setWardCount(wardRows.size());
        catalogState.setLastError(null);
        state.save(catalogState);
    }

    @Transactional
    public void recordFailure(Instant attemptedAt, String reason) {
        state.findByIdForUpdate((short) 1).ifPresent(catalogState -> {
            catalogState.setLastStatus("FAILED");
            catalogState.setLastAttemptAt(attemptedAt);
            catalogState.setLastError(reason);
            state.save(catalogState);
        });
    }

    @Transactional
    public void activateGhn() {
        AdministrativeAddressCatalogStateEntity catalogState = state.findByIdForUpdate((short) 1)
                .orElseThrow(() -> new IllegalStateException("Thiếu trạng thái danh mục địa chỉ."));
        long activeProvinceCount = provinces.countByCatalogSourceAndActiveTrue("GHN_STAGING");
        long activeWardCount = wards.countByCatalogSourceAndActiveTrue("GHN_STAGING");
        if (!"READY".equals(catalogState.getLastStatus()) || activeProvinceCount < 34 || activeWardCount < 3_321) {
            throw new IllegalStateException("Danh mục GHN Staging chưa được đồng bộ và kiểm tra đầy đủ.");
        }
        if (!pickupAddressConfigured()) {
            throw new IllegalStateException("Chưa cấu hình mã tỉnh/phường GHN hợp lệ cho địa chỉ lấy và hoàn hàng. "
                    + "Hãy đặt GHN_PICKUP_PROVINCE_ID và GHN_PICKUP_WARD_ID theo danh mục đã đồng bộ.");
        }
        catalogState.setActiveSource("GHN_STAGING");
        state.save(catalogState);
    }

    public boolean pickupAddressConfigured() {
        if (!ghnConfig.isConfigured() || ghnConfig.pickupProvinceId().isBlank()
                || ghnConfig.pickupWardId().isBlank()) return false;
        return provinces.findByCodeAndCatalogSourceAndActiveTrue(ghnConfig.pickupProvinceId(),
                        AdministrativeAddressService.GHN_STAGING).isPresent()
                && wards.findByCodeAndProvinceCodeAndCatalogSourceAndActiveTrue(ghnConfig.pickupWardId(),
                        ghnConfig.pickupProvinceId(), AdministrativeAddressService.GHN_STAGING).isPresent();
    }

    private Set<String> activeCodes(String table) {
        List<String> codes = jdbc.queryForList("SELECT code FROM " + table
                + " WHERE catalog_source = 'GHN_STAGING' AND is_active = TRUE", String.class);
        return new TreeSet<>(codes);
    }

    private void requireNoMissingActiveIds(String label, Set<String> previouslyActive,
                                           Set<String> incomingActive) {
        Set<String> missing = new TreeSet<>(previouslyActive);
        missing.removeAll(incomingActive);
        if (!missing.isEmpty()) {
            String sample = missing.stream().limit(5).collect(Collectors.joining(", "));
            throw new IllegalStateException("GHN Staging trả thiếu " + missing.size() + " mã " + label
                    + " đang công bố; giữ nguyên danh mục cũ và cần kiểm tra. Mã ví dụ: " + sample);
        }
    }

    private void upsertProvinces(List<CatalogRow> rows, Instant syncedAt) {
        String sql = """
                INSERT INTO provinces(code, name, full_name, aliases, catalog_type, provider_status,
                    catalog_source, catalog_synced_at, is_active)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, 'GHN_STAGING', ?, ?)
                ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name, full_name=EXCLUDED.full_name,
                    aliases=EXCLUDED.aliases, catalog_type=EXCLUDED.catalog_type,
                    provider_status=EXCLUDED.provider_status, catalog_synced_at=EXCLUDED.catalog_synced_at,
                    is_active=EXCLUDED.is_active
                WHERE provinces.catalog_source='GHN_STAGING'
                """;
        int[][] results = jdbc.batchUpdate(sql, rows, 250, (statement, row) -> {
            statement.setString(1, row.code());
            statement.setString(2, row.name());
            statement.setString(3, row.name());
            statement.setString(4, aliases(row));
            statement.setString(5, row.type());
            statement.setInt(6, row.status());
            statement.setTimestamp(7, Timestamp.from(syncedAt));
            statement.setBoolean(8, row.status() == 1);
        });
        requireBatchWrites(results);
    }

    private void upsertWards(List<CatalogRow> provinceRows, List<CatalogRow> rows, Instant syncedAt) {
        String sql = """
                INSERT INTO wards(code, name, full_name, aliases, catalog_type, provider_status,
                    catalog_source, province_code, catalog_synced_at, is_active)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, 'GHN_STAGING', ?, ?, ?)
                ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name, full_name=EXCLUDED.full_name,
                    aliases=EXCLUDED.aliases, catalog_type=EXCLUDED.catalog_type,
                    provider_status=EXCLUDED.provider_status, province_code=EXCLUDED.province_code,
                    catalog_synced_at=EXCLUDED.catalog_synced_at, is_active=EXCLUDED.is_active
                WHERE wards.catalog_source='GHN_STAGING'
                """;
        Map<String, Boolean> parentActive = provinceRows.stream()
                .collect(Collectors.toMap(CatalogRow::code, item -> item.status() == 1));
        int[][] results = jdbc.batchUpdate(sql, rows, 500, (statement, row) -> {
            statement.setString(1, row.code());
            statement.setString(2, row.name());
            statement.setString(3, row.name());
            statement.setString(4, aliases(row));
            statement.setString(5, row.type());
            statement.setInt(6, row.status());
            statement.setString(7, row.parentId());
            statement.setTimestamp(8, Timestamp.from(syncedAt));
            statement.setBoolean(9, row.status() == 1 && parentActive.getOrDefault(row.parentId(), false));
        });
        requireBatchWrites(results);
    }

    private String aliases(CatalogRow row) {
        try { return mapper.writeValueAsString(row.aliases()); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Không lưu được alias địa chỉ."); }
    }

    private void requireNoSourceCollisions(Map<String, String> existing, List<CatalogRow> incoming, String label) {
        Set<String> collisions = incoming.stream().map(CatalogRow::code).filter(existing::containsKey)
                .filter(code -> !"GHN_STAGING".equals(existing.get(code))).collect(Collectors.toSet());
        if (!collisions.isEmpty()) {
            String sample = collisions.stream().sorted().limit(5).collect(Collectors.joining(", "));
            throw new IllegalStateException("Mã GHN bị trùng với danh mục LOCAL ở " + label + ": " + sample);
        }
    }

    private void requireBatchWrites(int[][] results) {
        if (results.length == 0) throw new IllegalStateException("Danh mục GHN rỗng, không công bố dữ liệu.");
        for (int[] batch : results) {
            for (int result : batch) {
                if (result == 0) throw new IllegalStateException("Phát hiện xung đột mã nguồn khi công bố danh mục GHN.");
            }
        }
    }
}
