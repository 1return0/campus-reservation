package com.campusreserve.identity.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusreserve.identity.domain.CampusRole;
import com.campusreserve.identity.domain.IdentityUserStatus;
import com.campusreserve.identity.dao.entity.IdentityUserEntity;
import com.campusreserve.identity.dao.mapper.IdentityUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.regex.Pattern;

@Slf4j
@Configuration
@EnableConfigurationProperties(BootstrapAdminProperties.class)
public class BootstrapAdminConfiguration {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{4,32}");

    @Bean
    ApplicationRunner bootstrapAdmin(
            BootstrapAdminProperties properties,
            IdentityUserMapper identityUserMapper,
            PasswordEncoder passwordEncoder
    ) {
        return arguments -> {
            if (!properties.isConfigured()) {
                return;
            }
            if (!properties.isComplete()) {
                throw new IllegalStateException(
                        "CAMPUS_BOOTSTRAP_ADMIN_USERNAME, CAMPUS_BOOTSTRAP_ADMIN_PASSWORD and "
                                + "CAMPUS_BOOTSTRAP_ADMIN_DISPLAY_NAME must be configured together"
                );
            }
            if (!USERNAME_PATTERN.matcher(properties.username()).matches()) {
                throw new IllegalStateException("bootstrap admin username must match [A-Za-z0-9_-]{4,32}");
            }
            if (properties.password().length() < 12 || properties.password().length() > 72) {
                throw new IllegalStateException("bootstrap admin password length must be between 12 and 72");
            }

            IdentityUserEntity existing = identityUserMapper.selectOne(
                    new LambdaQueryWrapper<IdentityUserEntity>()
                            .eq(IdentityUserEntity::getUsername, properties.username())
            );
            if (existing != null) {
                log.info("Bootstrap administrator '{}' already exists; leaving its role and credentials unchanged", properties.username());
                return;
            }

            IdentityUserEntity administrator = new IdentityUserEntity();
            administrator.setUsername(properties.username());
            administrator.setPasswordHash(passwordEncoder.encode(properties.password()));
            administrator.setDisplayName(properties.displayName());
            administrator.setRoleCode(CampusRole.ADMIN);
            administrator.setStatus(IdentityUserStatus.ACTIVE);
            identityUserMapper.insert(administrator);
            log.info("Created the configured bootstrap administrator '{}'", properties.username());
        };
    }
}
