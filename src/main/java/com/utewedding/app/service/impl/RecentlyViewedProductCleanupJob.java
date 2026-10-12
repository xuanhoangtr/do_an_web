package com.utewedding.app.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RecentlyViewedProductCleanupJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecentlyViewedProductCleanupJob.class);
    private final RecentlyViewedProductService service;

    public RecentlyViewedProductCleanupJob(RecentlyViewedProductService service) {
        this.service = service;
    }

    @Scheduled(cron = "${catalog.recently-viewed.cleanup-cron:0 20 3 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void cleanupExpired() {
        try {
            int deleted = service.cleanupExpired();
            LOGGER.info("Expired recently viewed product history cleanup completed: deleted={}", deleted);
        } catch (RuntimeException exception) {
            LOGGER.warn("Expired recently viewed product history cleanup failed: {}",
                    exception.getClass().getSimpleName());
        }
    }
}
