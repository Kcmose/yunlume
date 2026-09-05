package com.example.nav.module.install.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.install.model.InstallCommand;
import com.example.nav.module.site.entity.SiteConfig;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InstallTransactionServiceIdentityTest {

    @Test
    void lockedSiteConfigMustMatchTrustedDatabaseInstanceBeforeAdminInsert() {
        UserMapper userMapper = mock(UserMapper.class);
        SiteConfigMapper siteConfigMapper = mock(SiteConfigMapper.class);
        SiteConfig config = new SiteConfig();
        config.setId(1L);
        config.setVersion(0);
        config.setInstallInstanceId(
                UUID.fromString("e38440cb-07d9-4fdf-9800-5a4ef185ee61"));
        when(siteConfigMapper.selectAllForUpdate()).thenReturn(List.of(config));
        when(siteConfigMapper.selectInstallInstanceIdText(1L))
                .thenReturn("4b9b020d-95cb-4754-906e-94f66a00a413");
        InstallCommand command = new InstallCommand(
                "Navigation", "Description", "admin", "Admin",
                "$2a$10$redacted",
                "e38440cb-07d9-4fdf-9800-5a4ef185ee61");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new InstallTransactionService(userMapper, siteConfigMapper)
                        .complete(command));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verifyNoInteractions(userMapper);
    }

    @Test
    void trustedTextIdentityAllowsCompletionWhenImplicitUuidEntityMappingIsNull() {
        UserMapper userMapper = mock(UserMapper.class);
        SiteConfigMapper siteConfigMapper = mock(SiteConfigMapper.class);
        SiteConfig config = new SiteConfig();
        config.setId(1L);
        config.setVersion(0);
        config.setInstallInstanceId(null);
        UUID expected = UUID.fromString("e38440cb-07d9-4fdf-9800-5a4ef185ee61");
        when(siteConfigMapper.selectAllForUpdate()).thenReturn(List.of(config));
        when(siteConfigMapper.selectInstallInstanceIdText(1L))
                .thenReturn(expected.toString());
        when(userMapper.selectCount(null)).thenReturn(0L);
        when(userMapper.insert(any(User.class))).thenReturn(1);
        when(siteConfigMapper.completeInstallation(
                eq(1L), eq("Navigation"), eq("Description"), any(), eq(expected)))
                .thenReturn(1);
        InstallCommand command = new InstallCommand(
                "Navigation", "Description", "admin", "Admin",
                "$2a$10$redacted", expected.toString());

        var result = new InstallTransactionService(userMapper, siteConfigMapper)
                .complete(command);

        assertTrue(result.installed());
        verify(siteConfigMapper).selectInstallInstanceIdText(1L);
        verify(siteConfigMapper).completeInstallation(
                eq(1L), eq("Navigation"), eq("Description"), any(), eq(expected));
    }
}
