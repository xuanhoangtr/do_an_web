package com.utewedding.app.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utewedding.app.config.GhnConfig;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/** Reads the GHN Staging v3 two-level address catalog. It never writes or creates shipments. */
@Component
public class GhnAddressCatalogClient {
    private static final String PROVINCES = "/v3/master-data/province/all";
    private static final String WARDS = "/v3/master-data/ward/all-by-province-id";
    private static final int PAGE_SIZE = 200;

    private final GhnConfig config;
    private final RestClient client;
    private final ObjectMapper mapper;

    @Autowired
    public GhnAddressCatalogClient(GhnConfig config, RestClient.Builder builder, ObjectMapper mapper) {
        this(config, configuredClient(builder), mapper);
    }

    GhnAddressCatalogClient(GhnConfig config, RestClient client, ObjectMapper mapper) {
        this.config = config;
        this.client = client;
        this.mapper = mapper;
    }

    public List<CatalogRow> fetchProvinces() {
        return fetchPages(PROVINCES, null);
    }

    public List<CatalogRow> fetchWards(String provinceId) {
        return fetchPages(WARDS, provinceId);
    }

    private List<CatalogRow> fetchPages(String path, String provinceId) {
        if (!config.isConfigured()) {
            throw new IllegalStateException("GHN Staging chưa được cấu hình.");
        }
        List<CatalogRow> result = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (int offset = 0; ; offset = Math.addExact(offset, PAGE_SIZE)) {
            Map<String, String> parameters = new LinkedHashMap<>();
            if (provinceId != null) parameters.put("province_id", provinceId);
            parameters.put("offset", Integer.toString(offset));
            parameters.put("limit", Integer.toString(PAGE_SIZE));
            Map<String, Object> response = get(path, parameters);
            if (number(response.get("code"), 0) != 200) {
                throw new IllegalStateException("GHN Staging từ chối tải danh mục địa chỉ.");
            }
            Object data = response.get("data");
            if (!(data instanceof List<?> page)) {
                throw new IllegalStateException("GHN Staging trả về danh mục địa chỉ không hợp lệ.");
            }
            for (Object raw : page) {
                if (!(raw instanceof Map<?, ?> source)) {
                    throw new IllegalStateException("GHN Staging trả về bản ghi địa chỉ không hợp lệ.");
                }
                Map<String, Object> row = new LinkedHashMap<>();
                source.forEach((key, value) -> row.put(String.valueOf(key), value));
                CatalogRow parsed = parse(row);
                if (!seenIds.add(parsed.code())) {
                    throw new IllegalStateException("GHN Staging lặp mã trong danh mục địa chỉ.");
                }
                result.add(parsed);
            }
            if (page.size() < PAGE_SIZE) break;
            if (offset > 200_000) throw new IllegalStateException("GHN Staging trả quá nhiều trang địa chỉ.");
        }
        return List.copyOf(result);
    }

    private CatalogRow parse(Map<String, Object> row) {
        String code = text(row.get("_id"));
        String name = text(row.get("name"));
        String type = text(row.get("type"));
        String parent = text(row.get("parent_id"));
        int status = (int) number(row.get("status"), -1);
        if (!code.matches("[0-9]{1,20}") || new BigInteger(code).signum() <= 0
                || name.isBlank() || !Set.of("province", "ward").contains(type)
                || !Set.of(1, 2, 10).contains(status)) {
            throw new IllegalStateException("GHN Staging trả về trường địa chỉ thiếu hoặc không hợp lệ.");
        }
        List<String> aliases = new ArrayList<>();
        Object rawAliases = row.get("extension_names");
        if (rawAliases instanceof Collection<?> values) {
            values.stream().filter(value -> value != null).map(String::valueOf)
                    .filter(value -> !value.isBlank()).forEach(aliases::add);
        }
        return new CatalogRow(code, name, type, parent, status, List.copyOf(aliases));
    }

    private Map<String, Object> get(String path, Map<String, String> parameters) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(GhnConfig.STAGING_API_BASE + path);
        parameters.forEach(uri::queryParam);
        try {
            Map<String, Object> response = client.get().uri(uri.build().encode().toUriString())
                    .header("Token", config.token())
                    .header("ShopId", Integer.toString(config.shopId()))
                    .retrieve().body(new ParameterizedTypeReference<>() { });
            if (response == null) throw new IllegalStateException("GHN Staging không trả dữ liệu danh mục.");
            return response;
        } catch (RestClientException exception) {
            throw new IllegalStateException("Không thể kết nối GHN Staging để đồng bộ danh mục địa chỉ.");
        }
    }

    private static RestClient configuredClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(4));
        factory.setReadTimeout(Duration.ofSeconds(12));
        return builder.requestFactory(factory).build();
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(text(value)); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    public record CatalogRow(String code, String name, String type, String parentId,
                             int status, List<String> aliases) { }
}
