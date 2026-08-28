package com.example.nav.common.config;

import com.example.nav.module.bookmark.mapper.BookmarkMapper;
import com.example.nav.module.category.mapper.CategoryMapper;
import com.example.nav.module.customlink.mapper.CustomLinkMapper;
import com.example.nav.module.search.mapper.SearchEngineMapper;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.example.nav.module.install.service.DatabaseConfigurationStore;
import com.example.nav.module.install.service.DatabaseIdentityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private UserMapper userMapper;
    @Mock
    private SiteConfigMapper siteConfigMapper;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private BookmarkMapper bookmarkMapper;
    @Mock
    private SearchEngineMapper searchEngineMapper;
    @Mock
    private CustomLinkMapper customLinkMapper;
    @Mock
    private DatabaseConfigurationStore databaseConfigurationStore;
    @Mock
    private DatabaseIdentityService databaseIdentityService;

    @Test
    void productionBootstrapCreatesOnlyTheFirstAdministrator() {
        BootstrapProperties properties = properties("admin", "Cedar!River2026", false);
        when(userMapper.selectCount(null)).thenReturn(0L);
        when(passwordEncoder.encode("Cedar!River2026")).thenReturn("encoded");

        initializer(properties).run(null);

        ArgumentCaptor<User> inserted = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(inserted.capture());
        assertEquals("admin", inserted.getValue().getUsername());
        assertEquals("encoded", inserted.getValue().getPassword());
        assertEquals(0, inserted.getValue().getTokenVersion().intValue());
        verify(siteConfigMapper).markInstallationCompletedWhenUserExists(
                org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class));
        verifyNoInteractions(categoryMapper, bookmarkMapper, searchEngineMapper, customLinkMapper);
    }

    @Test
    void changedBootstrapUsernameDoesNotCreateASecondAdministrator() {
        BootstrapProperties properties = properties("renamed-admin", null, false);
        when(userMapper.selectCount(null)).thenReturn(1L);

        initializer(properties).run(null);

        verify(userMapper, never()).insert(org.mockito.ArgumentMatchers.any(User.class));
        verify(siteConfigMapper).markInstallationCompletedWhenUserExists(
                org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class));
        verifyNoInteractions(passwordEncoder, categoryMapper, bookmarkMapper,
                searchEngineMapper, customLinkMapper);
    }

    @Test
    void firstAdministratorMustUseTheSharedStrongPasswordPolicy() {
        BootstrapProperties properties = properties("admin", "admin123", false);
        when(userMapper.selectCount(null)).thenReturn(0L);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> initializer(properties).run(null)
        );

        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("密码长度必须为 12-72 个字符"));
        verify(userMapper, never()).insert(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void blankBootstrapPasswordWaitsForEnabledWebInstallation() {
        BootstrapProperties properties = properties("admin", null, false);
        when(userMapper.selectCount(null)).thenReturn(0L);

        initializer(properties).run(null);

        verify(userMapper, never()).insert(org.mockito.ArgumentMatchers.any(User.class));
        verifyNoInteractions(passwordEncoder);
        verify(siteConfigMapper).markInstallationCompletedWhenUserExists(
                org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class));
    }

    @Test
    void legacyBootstrapStillRejectsBlankCredentialsWhenWebInstallationIsDisabled() {
        BootstrapProperties properties = properties("admin", null, false);
        when(userMapper.selectCount(null)).thenReturn(0L);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> initializer(properties, false).run(null)
        );

        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("must not be blank"));
        verify(userMapper, never()).insert(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void completedInstallationMarkerPreventsLegacyBootstrapFromRecreatingADeletedAdministrator() {
        BootstrapProperties properties = properties("admin", "Cedar!River2026", true);
        when(userMapper.selectCount(null)).thenReturn(0L);
        when(siteConfigMapper.countCompletedInstallations()).thenReturn(1L);

        initializer(properties).run(null);

        verify(userMapper, never()).insert(org.mockito.ArgumentMatchers.any(User.class));
        verifyNoInteractions(passwordEncoder, categoryMapper, bookmarkMapper,
                searchEngineMapper, customLinkMapper);
        verify(siteConfigMapper, never()).markInstallationCompletedWhenUserExists(
                org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class));
    }

    private DataInitializer initializer(BootstrapProperties properties) {
        return initializer(properties, true);
    }

    private DataInitializer initializer(BootstrapProperties properties, boolean webInstallEnabled) {
        WebInstallProperties webInstallProperties = new WebInstallProperties();
        webInstallProperties.setEnabled(webInstallEnabled);
        when(databaseIdentityService.ensureVerified()).thenReturn(true);
        return new DataInitializer(properties, webInstallProperties, passwordEncoder, userMapper, siteConfigMapper,
                categoryMapper, bookmarkMapper, searchEngineMapper, customLinkMapper,
                databaseConfigurationStore, databaseIdentityService);
    }

    private BootstrapProperties properties(String username, String password, boolean demoDataEnabled) {
        BootstrapProperties properties = new BootstrapProperties();
        properties.setEnabled(true);
        properties.setDemoDataEnabled(demoDataEnabled);
        properties.setAdminUsername(username);
        properties.setAdminPassword(password);
        return properties;
    }
}
