package com.utewedding.app.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GhnAddressCatalogSyncJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(GhnAddressCatalogSyncJob.class);
    private final GhnAddressCatalogSyncService sync;

    public GhnAddressCatalogSyncJob(GhnAddressCatalogSyncService sync) { this.sync = sync; }

    @Scheduled(cron = "${shipping.address-catalog.sync-cron:0 0 2 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void syncNightly() {
        try {
            var result = sync.sync();
            if (result == null) {
                LOGGER.info("GHN Staging address catalog sync skipped because another run holds the shared lock");
                return;
            }
            LOGGER.info("GHN Staging address catalog sync completed: provinces={}, wards={}",
                    result.provinceCount(), result.wardCount());
        } catch (RuntimeException exception) {
            LOGGER.warn("GHN Staging address catalog sync failed; keeping last published dataset: {}",
                    exception.getMessage());
        }
    }
}
