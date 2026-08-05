package com.buukle.agent.sso.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.sso.domain.IdentityProvider;
import com.buukle.agent.sso.domain.SsoProviderType;
import com.buukle.agent.sso.dtvo.SsoAuthorizeVO;
import com.buukle.agent.sso.exception.SsoErrorCode;
import com.buukle.agent.sso.repository.IdentityProviderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import static com.buukle.agent.sso.service.SsoConstants.CALLBACK_PATH;
import static com.buukle.agent.sso.service.SsoConstants.CODE_CHALLENGE_METHOD_S256;
import static com.buukle.agent.sso.service.SsoConstants.CODE_VERIFIER_BYTES;
import static com.buukle.agent.sso.service.SsoConstants.NONCE_TOKEN_BYTES;
import static com.buukle.agent.sso.service.SsoConstants.OTC_KEY_PREFIX;
import static com.buukle.agent.sso.service.SsoConstants.OTC_TOKEN_BYTES;
import static com.buukle.agent.sso.service.SsoConstants.PARAM_CLIENT_ID;
import static com.buukle.agent.sso.service.SsoConstants.PARAM_CODE_CHALLENGE;
import static com.buukle.agent.sso.service.SsoConstants.PARAM_CODE_CHALLENGE_METHOD;
import static com.buukle.agent.sso.service.SsoConstants.PARAM_ERROR;
import static com.buukle.agent.sso.service.SsoConstants.PARAM_NONCE;
import static com.buukle.agent.sso.service.SsoConstants.PARAM_OTC;
import static com.buukle.agent.sso.service.SsoConstants.PARAM_PROMPT;
import static com.buukle.agent.sso.service.SsoConstants.PARAM_REDIRECT_URI;
import static com.buukle.agent.sso.service.SsoConstants.PARAM_RESPONSE_TYPE;
import static com.buukle.agent.sso.service.SsoConstants.PARAM_SCOPE;
import static com.buukle.agent.sso.service.SsoConstants.PARAM_STATE;
import static com.buukle.agent.sso.service.SsoConstants.RESPONSE_TYPE_CODE;
import static com.buukle.agent.sso.service.SsoConstants.SHA_256;
import static com.buukle.agent.sso.service.SsoConstants.STATE_KEY_PREFIX;
import static com.buukle.agent.sso.service.SsoConstants.STATE_TOKEN_BYTES;
import static com.buukle.agent.sso.service.SsoConstants.STATE_VALUE_PART_CODE_VERIFIER;
import static com.buukle.agent.sso.service.SsoConstants.STATE_VALUE_PART_NONCE;
import static com.buukle.agent.sso.service.SsoConstants.STATE_VALUE_PART_PROVIDER;
import static com.buukle.agent.sso.service.SsoConstants.STATE_VALUE_PART_REDIRECT_URI;
import static com.buukle.agent.sso.service.SsoConstants.STATE_VALUE_PARTS;
import static com.buukle.agent.sso.service.SsoConstants.STATE_VALUE_SEPARATOR;

@Slf4j
@Service
@RequiredArgsConstructor
public class SsoServiceImpl implements SsoService {

    private final IdentityProviderMapper identityProviderMapper;
    private final SsoOidcClient ssoOidcClient;
    private final SsoProvisioningService provisioningService;
    private final UserSpi userSpi;
    private final RedissonClient redissonClient;
    private final AgentRuntimeProperties properties;
    private final SystemConfigSpi systemConfigSpi;

    private String ssoBaseUrl() {
        return systemConfigSpi.get(SystemConfigKeys.SSO_BASE_URL, properties.getSso().getBaseUrl());
    }

    @Override
    public SsoAuthorizeVO authorize(String provider, String redirectUri, String prompt) {
        IdentityProvider identityProvider = requireEnabledProvider(provider);
        String state = randomToken(STATE_TOKEN_BYTES);
        String nonce = randomToken(NONCE_TOKEN_BYTES);
        String codeVerifier = randomToken(CODE_VERIFIER_BYTES);
        String codeChallenge = codeChallenge(codeVerifier);

        String stateValue = String.join(STATE_VALUE_SEPARATOR,
                provider, redirectUri == null ? "" : redirectUri, codeVerifier, nonce);
        set(stateKey(state), stateValue, properties.getSso().getStateTtl());

        String callbackUrl = ssoBaseUrl() + CALLBACK_PATH;
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(identityProvider.getAuthorizationEndpoint())
                .queryParam(PARAM_RESPONSE_TYPE, RESPONSE_TYPE_CODE)
                .queryParam(PARAM_CLIENT_ID, identityProvider.getClientId())
                .queryParam(PARAM_REDIRECT_URI, callbackUrl)
                .queryParam(PARAM_SCOPE, identityProvider.getScopes())
                .queryParam(PARAM_STATE, state)
                .queryParam(PARAM_NONCE, nonce)
                .queryParam(PARAM_CODE_CHALLENGE, codeChallenge)
                .queryParam(PARAM_CODE_CHALLENGE_METHOD, CODE_CHALLENGE_METHOD_S256);
        if (StringUtils.hasText(prompt)) {
            url.queryParam(PARAM_PROMPT, prompt);
        }

        SsoAuthorizeVO vo = new SsoAuthorizeVO();
        vo.setProvider(provider);
        vo.setState(state);
        vo.setAuthorizeUrl(url.build().toUriString());
        return vo;
    }

    @Override
    public String callback(String code, String state, String iss, String error) {
        String stateValue = get(stateKey(state));
        if (stateValue == null) {
            throw new BizException(SsoErrorCode.STATE_MISMATCH);
        }
        delete(stateKey(state));

        String[] parts = stateValue.split("\\" + STATE_VALUE_SEPARATOR, STATE_VALUE_PARTS);
        String providerCode = parts[STATE_VALUE_PART_PROVIDER];
        String redirectUri = parts.length > STATE_VALUE_PART_REDIRECT_URI ? parts[STATE_VALUE_PART_REDIRECT_URI] : "";
        String codeVerifier = parts.length > STATE_VALUE_PART_CODE_VERIFIER ? parts[STATE_VALUE_PART_CODE_VERIFIER] : "";
        String nonce = parts.length > STATE_VALUE_PART_NONCE ? parts[STATE_VALUE_PART_NONCE] : "";

        if (StringUtils.hasText(error)) {
            return withQueryParam(redirectUri, PARAM_ERROR, error);
        }

        IdentityProvider identityProvider = requireEnabledProvider(providerCode);
        String callbackUrl = ssoBaseUrl() + CALLBACK_PATH;
        SsoOidcClient.IdTokenClaims claims = ssoOidcClient.exchangeAndVerify(
                identityProvider, code, callbackUrl, codeVerifier, nonce);

        Long userId = provisioningService.provisionOrGet(
                providerCode, claims.subject(), claims.email(), claims.name());

        String otc = randomToken(OTC_TOKEN_BYTES);
        set(otcKey(otc), String.valueOf(userId), properties.getSso().getOtcTtl());
        return withQueryParam(redirectUri, PARAM_OTC, otc);
    }

    @Override
    public UserVO exchange(String otc) {
        if (!StringUtils.hasText(otc)) {
            throw new BizException(SsoErrorCode.OTC_INVALID);
        }
        String key = otcKey(otc);
        String userIdStr = get(key);
        if (userIdStr == null) {
            throw new BizException(SsoErrorCode.OTC_INVALID);
        }
        delete(key);
        try {
            return userSpi.loginByUserId(Long.parseLong(userIdStr));
        } catch (NumberFormatException e) {
            throw new BizException(SsoErrorCode.OTC_INVALID);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("SSO exchange failed for user {}", userIdStr, e);
            throw new BizException(SsoErrorCode.OTC_INVALID);
        }
    }

    private IdentityProvider requireEnabledProvider(String provider) {
        IdentityProvider identityProvider = identityProviderMapper.selectOne(
                new LambdaQueryWrapper<IdentityProvider>()
                        .eq(IdentityProvider::getCode, provider)
                        .eq(IdentityProvider::getType, SsoProviderType.OIDC.getValue())
                        .eq(IdentityProvider::getEnabled, Boolean.TRUE)
                        .last("LIMIT 1"));
        if (identityProvider == null) {
            throw new BizException(SsoErrorCode.PROVIDER_NOT_ENABLED);
        }
        return identityProvider;
    }

    private static String withQueryParam(String baseUri, String param, String value) {
        return UriComponentsBuilder.fromUriString(baseUri)
                .queryParam(param, value)
                .build().toUriString();
    }

    private static String codeChallenge(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance(SHA_256).digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        new SecureRandom().nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static String stateKey(String state) {
        return STATE_KEY_PREFIX + state;
    }

    private static String otcKey(String otc) {
        return OTC_KEY_PREFIX + otc;
    }

    private void set(String key, String value, Duration ttl) {
        RBucket<String> bucket = redissonClient.getBucket(key);
        bucket.set(value, ttl);
    }

    private String get(String key) {
        RBucket<String> bucket = redissonClient.getBucket(key);
        return bucket.get();
    }

    private void delete(String key) {
        redissonClient.getBucket(key).delete();
    }
}