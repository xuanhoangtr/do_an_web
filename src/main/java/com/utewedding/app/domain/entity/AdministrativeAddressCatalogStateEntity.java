package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "administrative_address_catalog_state")
public class AdministrativeAddressCatalogStateEntity {
    @Id
    @Column(name = "singleton_id")
    private Short singletonId;
    @Column(name = "active_source", nullable = false, length = 32)
    private String activeSource = "LOCAL";
    @Column(name = "last_status", nullable = false, length = 16)
    private String lastStatus = "NOT_SYNCED";
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;
    @Column(name = "last_success_at")
    private Instant lastSuccessAt;
    @Column(name = "province_count", nullable = false)
    private int provinceCount;
    @Column(name = "ward_count", nullable = false)
    private int wardCount;
    @Column(name = "last_error", length = 500)
    private String lastError;

    protected AdministrativeAddressCatalogStateEntity() {}

    public Short getSingletonId() { return singletonId; }
    public String getActiveSource() { return activeSource; }
    public void setActiveSource(String value) { activeSource = value; }
    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String value) { lastStatus = value; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant value) { lastAttemptAt = value; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public void setLastSuccessAt(Instant value) { lastSuccessAt = value; }
    public int getProvinceCount() { return provinceCount; }
    public void setProvinceCount(int value) { provinceCount = value; }
    public int getWardCount() { return wardCount; }
    public void setWardCount(int value) { wardCount = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String value) { lastError = value; }
}
