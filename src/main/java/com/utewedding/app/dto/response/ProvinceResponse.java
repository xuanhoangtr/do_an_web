package com.utewedding.app.dto.response;

public record ProvinceResponse(String code, String name, String fullName, String catalogSource) {
    public ProvinceResponse(String code, String name, String fullName) {
        this(code, name, fullName, "LOCAL");
    }
}
