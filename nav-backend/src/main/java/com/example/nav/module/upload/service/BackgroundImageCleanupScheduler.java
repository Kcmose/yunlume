package com.example.nav.module.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BackgroundImageCleanupScheduler {

    private final BackgroundImageStorageService storageService;

    public BackgroundImageCleanupScheduler(BackgroundImageStorageService storageService) {
        this.storageService = storageService;
    }

    @Scheduled(
            fixedDelayString = "${nav.upload.cleanup-interval-ms:21600000}",
            initialDelayString = "${nav.upload.cleanup-initial-delay-ms:60000}"
    )
    public void cleanup() {
        try {
            BackgroundImageStorageService.CleanupResult result = storageService.cleanupOrphans();
            if (result.skipped()) {
                log.warn("Scheduled orphan background cleanup was skipped because references were unavailable");
            } else if (result.deleted() > 0) {
                log.info(
                        "Deleted {} orphan background files ({} bytes); {} references and {} grace-period files were protected",
                        result.deleted(),
                        result.deletedBytes(),
                        result.referenced(),
                        result.graceProtected()
                );
            }
        } catch (RuntimeException exception) {
            log.error("Scheduled orphan background cleanup failed", exception);
        }
    }
}
