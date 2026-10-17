package com.utewedding.app.dto.response;

public record WardResponse(String code, String name, String fullName, String provinceCode,
                           String catalogSource) {
    public WardResponse(String code, String name, String fullName, String provinceCode) {
        this(code, name, fullName, provinceCode, "LOCAL");
    }
}
