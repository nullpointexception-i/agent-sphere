package com.buukle.agent.sso.service;

import com.buukle.agent.sso.domain.IdentityProvider;
import com.buukle.agent.sso.exception.SsoErrorCode;
import com.buukle.agent.sso.service.SsoConstants.GrantType;
import com.buukle.agent.sso.service.SsoConstants.OidcClaim;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.security.CryptoService;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
public class SsoOidcClient {

    private final RestClient restClient;
    private final CryptoService cryptoService;

    public SsoOidcClient(RestClient.Builder restClientBuilder, CryptoService cryptoService) {
        this.restClient = restClientBuilder.build();
        this.cryptoService = cryptoService;
    }

    public IdTokenClaims exchangeAndVerify(IdentityProvider provider, String code, String redirectUri, String codeVerifier, String nonce) {
        String idToken = exchangeToken(provider, code, redirectUri, codeVerifier);
        return verifyIdToken(provider, idToken, nonce);
    }

    private String exchangeToken(IdentityProvider provider, String code, String redirectUri, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(GrantType.FIELD, GrantType.AUTHORIZATION_CODE.getValue());
        form.add(SsoConstants.PARAM_CODE, code);
        form.add(SsoConstants.PARAM_REDIRECT_URI, redirectUri);
        form.add(SsoConstants.PARAM_CLIENT_ID, provider.getClientId());
        form.add(SsoConstants.PARAM_CLIENT_SECRET, resolveSecret(provider));
        form.add(SsoConstants.PARAM_CODE_VERIFIER, codeVerifier);

        try {
            String body = restClient.post()
                    .uri(URI.create(provider.getTokenEndpoint()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                throw new BizException(SsoErrorCode.TOKEN_EXCHANGE_FAILED);
            }
            JsonNode node = JsonUtils.getMapper().readTree(body);
            return node.path(SsoConstants.TOKEN_FIELD_ID_TOKEN).asText();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("OIDC token exchange failed: provider={}", provider.getCode(), e);
            throw new BizException(SsoErrorCode.TOKEN_EXCHANGE_FAILED);
        }
    }

    private IdTokenClaims verifyIdToken(IdentityProvider provider, String idToken, String nonce) {
        try {
            RemoteJWKSet<SecurityContext> jwkSet = new RemoteJWKSet<>(URI.create(provider.getJwksUrl()).toURL());
            ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(SsoConstants.JWS_ALG_RS256, jwkSet));
            processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                    new JWTClaimsSet.Builder().issuer(provider.getIssuer()).audience(provider.getClientId()).build(),
                    new HashSet<>(Set.of(OidcClaim.SUB.getValue()))));
            JWTClaimsSet claims = processor.process(idToken, null);
            if (nonce != null && nonce.equals(claims.getStringClaim(OidcClaim.NONCE.getValue()))) {
                return new IdTokenClaims(claims.getSubject(),
                        claims.getStringClaim(OidcClaim.EMAIL.getValue()),
                        claims.getStringClaim(OidcClaim.NAME.getValue()));
            }
            throw new BizException(SsoErrorCode.ID_TOKEN_INVALID);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("OIDC id_token verification failed: provider={}", provider.getCode(), e);
            throw new BizException(SsoErrorCode.ID_TOKEN_INVALID);
        }
    }

    public record IdTokenClaims(String subject, String email, String name) {
    }

    /**
     * Decrypt the stored client_secret. Values written before secret encryption
     * was introduced are plaintext — fall back to the raw value so legacy rows
     * keep working until they are next saved through the admin CRUD.
     */
    private String resolveSecret(IdentityProvider provider) {
        String secret = provider.getClientSecret();
        if (secret == null || secret.isBlank()) {
            return secret;
        }
        try {
            return cryptoService.decrypt(secret);
        } catch (Exception e) {
            log.warn("client_secret of provider {} is not encrypted, treating as plaintext", provider.getCode());
            return secret;
        }
    }

    /**
     * Reachability check for an OIDC provider config: fetches the JWKS document
     * and hits the token endpoint with an intentionally-invalid grant. Any HTTP
     * 4xx on the token endpoint means the endpoint is reachable and the client
     * credentials were accepted; connection errors and 5xx fail the test.
     */
    public void testConnection(IdentityProvider provider) {
        try {
            String jwks = restClient.get()
                    .uri(URI.create(provider.getJwksUrl()))
                    .retrieve()
                    .body(String.class);
            JsonNode jwksNode = JsonUtils.getMapper().readTree(jwks);
            if (jwksNode == null || !jwksNode.has("keys") || !jwksNode.get("keys").isArray()) {
                throw new BizException(SsoErrorCode.CONNECTION_FAILED);
            }

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add(GrantType.FIELD, "invalid_test");
            form.add(SsoConstants.PARAM_CLIENT_ID, provider.getClientId());
            form.add(SsoConstants.PARAM_CLIENT_SECRET, resolveSecret(provider));
            restClient.post()
                    .uri(URI.create(provider.getTokenEndpoint()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new BizException(SsoErrorCode.CONNECTION_FAILED);
                    })
                    .body(String.class);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("OIDC connection test failed: provider={}", provider.getCode(), e);
            throw new BizException(SsoErrorCode.CONNECTION_FAILED);
        }
    }
}