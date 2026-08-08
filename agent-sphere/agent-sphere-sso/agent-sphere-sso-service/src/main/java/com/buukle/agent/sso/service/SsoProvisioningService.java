package com.buukle.agent.sso.service;

import com.buukle.agent.instance.dtvo.dto.RegisterDTO;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.sso.domain.SsoIdentity;
import com.buukle.agent.sso.exception.SsoErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.sso.repository.SsoIdentityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class SsoProvisioningService {

    private static final int USERNAME_MAX_LEN = 32;
    private static final int USERNAME_MIN_LEN = 5;
    private static final int RANDOM_PASSWORD_BYTES = 24;
    private static final int USERNAME_PADDING_FLOOR = 'a';
    private static final int USERNAME_PADDING_CEILING = 'z' + 1;
    private static final String USERNAME_SANITIZE_REGEX = "[^a-zA-Z0-9]";

    private final SsoIdentityMapper ssoIdentityMapper;
    private final UserSpi userSpi;

    @Transactional
    public ProvisionResult provisionOrGet(String providerCode, String subject, String email, String displayName,
                                          String preferredUsername, Long defaultRoleId) {
        SsoIdentity existing = ssoIdentityMapper.selectOne(
                new LambdaQueryWrapper<SsoIdentity>()
                        .eq(SsoIdentity::getProviderCode, providerCode)
                        .eq(SsoIdentity::getSubject, subject)
                        .last("LIMIT 1"));
        if (existing != null) {
            // 每次登录刷新第三方用户名（displaySubject），用户改名后下次登录自动同步
            String displaySubject = resolveDisplaySubject(preferredUsername, displayName, subject);
            if (!java.util.Objects.equals(existing.getDisplaySubject(), displaySubject)) {
                existing.setDisplaySubject(displaySubject);
                ssoIdentityMapper.updateById(existing);
            }
            UserVO existingUser = userSpi.getByUserId(existing.getAgentUserId());
            return ProvisionResult.of(existing.getAgentUserId(),
                    existingUser == null ? null : existingUser.getUsername(), false);
        }

        RegisterDTO registerDTO = new RegisterDTO();
        registerDTO.setUsername(deriveUsername(providerCode, subject));
        registerDTO.setPassword(randomPassword());
        registerDTO.setRepeatPassword(registerDTO.getPassword());
        UserVO user;
        try {
            user = userSpi.register(registerDTO, defaultRoleId);
        } catch (Exception e) {
            log.error("SSO provision failed: provider={}, subject={}", providerCode, subject, e);
            throw new BizException(SsoErrorCode.PROVISION_FAILED);
        }

        SsoIdentity identity = new SsoIdentity();
        identity.setProviderCode(providerCode);
        identity.setSubject(subject);
        identity.setDisplaySubject(resolveDisplaySubject(preferredUsername, displayName, subject));
        identity.setAgentUserId(user.getId());
        ssoIdentityMapper.insert(identity);
        return ProvisionResult.of(user.getId(), user.getUsername(), true);
    }

    /** 开通结果：userId + username + 是否首次开通（首次需要生成用户资源副本）。 */
    public record ProvisionResult(Long userId, String username, boolean created) {
        public static ProvisionResult of(Long userId, String username, boolean created) {
            return new ProvisionResult(userId, username, created);
        }
    }

    private static String resolveDisplaySubject(String preferredUsername, String displayName, String subject) {
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            return preferredUsername;
        }
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return subject;
    }

    static String deriveUsername(String providerCode, String subject) {
        String base = providerCode + "_" + subject.replaceAll(USERNAME_SANITIZE_REGEX, "");
        if (base.length() > USERNAME_MAX_LEN) {
            base = base.substring(0, USERNAME_MAX_LEN);
        }
        if (base.length() < USERNAME_MIN_LEN) {
            base = base + new SecureRandom().ints(USERNAME_PADDING_FLOOR, USERNAME_PADDING_CEILING)
                    .limit(USERNAME_MIN_LEN - base.length())
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                    .toString();
        }
        return base;
    }

    private static String randomPassword() {
        byte[] bytes = new byte[RANDOM_PASSWORD_BYTES];
        new SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}