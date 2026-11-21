package com.insurance.training.identity.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.identity.entity.AdminUser;
import com.insurance.training.identity.mapper.AdminUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 生产环境默认口令守卫。
 *
 * <p>V2 迁移内置了本地开发账号 `admin`。若生产环境仍在使用迁移内置的默认口令，
 * 等于把管理员入口公开在互联网上。这里在启动时检测并**直接终止启动**，
 * 迫使运维在首次上线前完成改密（显式失败，不静默放行）。
 */
@Component
@Profile("prod")
public class DefaultAdminPasswordGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultAdminPasswordGuard.class);
    private static final String LEGACY_USERNAME = "admin";
    private static final String LEGACY_PASSWORD = "Admin@12345";

    private final AdminUserMapper adminUserMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public DefaultAdminPasswordGuard(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        AdminUser legacy = adminUserMapper.selectOne(
                Wrappers.<AdminUser>lambdaQuery().eq(AdminUser::getUsername, LEGACY_USERNAME));
        if (legacy != null && encoder.matches(LEGACY_PASSWORD, legacy.getPasswordHash())) {
            throw new IllegalStateException(
                    "检测到生产环境仍在使用迁移内置的默认管理员口令，已拒绝启动。"
                            + "请先通过数据库更新 admin 账号的 password_hash（BCrypt），再重新启动。");
        }
        log.info("默认口令守卫通过：未发现使用内置默认口令的管理员账号");
    }
}
