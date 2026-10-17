package com.utewedding.app.controller;

import com.utewedding.app.dto.response.ProvinceResponse;
import com.utewedding.app.dto.response.WardResponse;
import com.utewedding.app.service.AdministrativeAddressService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/provinces")
public class AdministrativeAddressApiController {
    private final AdministrativeAddressService service;

    public AdministrativeAddressApiController(AdministrativeAddressService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProvinceResponse> provinces() {
        return service.provinces();
    }

    @GetMapping("/{provinceCode}/wards")
    public List<WardResponse> wards(@PathVariable String provinceCode) {
        return service.wards(provinceCode);
    }
}
