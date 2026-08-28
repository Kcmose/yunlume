package com.example.nav.module.install.service;

import com.example.nav.common.config.WebInstallProperties;
import com.example.nav.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InstallAccessServiceTest {

    @Test
    void enabledInstallerIsAccepted() {
        WebInstallProperties properties = new WebInstallProperties();
        properties.setEnabled(true);
        InstallAccessService service = new InstallAccessService(properties);

        assertDoesNotThrow(service::requireEnabled);
    }

    @Test
    void disabledInstallerIsRejected() {
        WebInstallProperties properties = new WebInstallProperties();
        properties.setEnabled(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> new InstallAccessService(properties).requireEnabled());
        assertEquals(403, exception.getStatus().value());
    }
}
