package com.example.nav.module.install.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.install.model.InstallCommand;
import com.example.nav.module.install.vo.InstallCompleteVO;
import com.example.nav.module.site.entity.SiteConfig;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class InstallTransactionService {

    private final UserMapper userMapper;
    private final SiteConfigMapper siteConfigMapper;

    public InstallTransactionService(UserMapper userMapper, SiteConfigMapper siteConfigMapper) {
        this.userMapper = userMapper;
        this.siteConfigMapper = siteConfigMapper;
    }

    @Transactional
    public InstallCompleteVO complete(InstallCommand command) {
        List<SiteConfig> configs = siteConfigMapper.selectAllForUpdate();
        if (configs.size() != 1) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "站点配置必须且只能有一条");
        }
        SiteConfig config = configs.get(0);
        UUID expectedInstanceId = null;
        if (command.expectedDatabaseInstanceId() != null) {
            try {
                expectedInstanceId = UUID.fromString(command.expectedDatabaseInstanceId());
            } catch (RuntimeException exception) {
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                        "受信数据库实例身份无效");
            }
            UUID lockedInstanceId;
            try {
                String lockedInstanceIdText = siteConfigMapper
                        .selectInstallInstanceIdText(config.getId());
                lockedInstanceId = UUID.fromString(lockedInstanceIdText);
            } catch (RuntimeException exception) {
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                        "锁定的数据库实例身份无效");
            }
            if (!expectedInstanceId.equals(lockedInstanceId)) {
                throw BusinessException.conflict("数据库实例在安装提交前发生变化");
            }
        }
        if (config.getInstallCompletedAt() != null || userMapper.selectCount(null) > 0) {
            throw BusinessException.conflict("站点已经完成安装，不能再次初始化");
        }

        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(command.username());
        user.setPassword(command.encodedPassword());
        user.setNickname(command.nickname());
        user.setRole("admin");
        user.setStatus(true);
        user.setTokenVersion(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        if (userMapper.insert(user) != 1) {
            throw BusinessException.conflict("管理员创建失败，请重新检查安装状态");
        }
        if (siteConfigMapper.completeInstallation(
                config.getId(), command.siteName(), command.siteDescription(), now,
                expectedInstanceId) != 1) {
            throw BusinessException.conflict("站点安装状态已发生变化，请重新检查");
        }
        return new InstallCompleteVO(true);
    }
}
