package com.buukle.agent.sso.service;

import com.nimbusds.jose.JWSAlgorithm;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * SSO/OIDC protocol constants shared across the SSO domain.
 */
public final class SsoConstants {

    private SsoConstants() {
    }

    public static final String CALLBACK_PATH = "/api/v1/auth/sso/callback";

    public static final String STATE_KEY_PREFIX = "sso:state:";
    public static final String OTC_KEY_PREFIX = "sso:otc:";

    public static final int STATE_TOKEN_BYTES = 16;
    public static final int NONCE_TOKEN_BYTES = 16;
    public static final int CODE_VERIFIER_BYTES = 32;
    public static final int OTC_TOKEN_BYTES = 24;

    public static final String STATE_VALUE_SEPARATOR = "|";
    public static final int STATE_VALUE_PARTS = 4;
    public static final int STATE_VALUE_PART_PROVIDER = 0;
    public static final int STATE_VALUE_PART_REDIRECT_URI = 1;
    public static final int STATE_VALUE_PART_CODE_VERIFIER = 2;
    public static final int STATE_VALUE_PART_NONCE = 3;

    public static final String RESPONSE_TYPE_CODE = "code";

    public static final String PARAM_RESPONSE_TYPE = "response_type";
    public static final String PARAM_CLIENT_ID = "client_id";
    public static final String PARAM_CLIENT_SECRET = "client_secret";
    public static final String PARAM_REDIRECT_URI = "redirect_uri";
    public static final String PARAM_SCOPE = "scope";
    public static final String PARAM_STATE = "state";
    public static final String PARAM_NONCE = "nonce";
    public static final String PARAM_CODE = "code";
    public static final String PARAM_CODE_VERIFIER = "code_verifier";
    public static final String PARAM_CODE_CHALLENGE = "code_challenge";
    public static final String PARAM_CODE_CHALLENGE_METHOD = "code_challenge_method";
    public static final String PARAM_PROMPT = "prompt";
    public static final String PARAM_ERROR = "error";
    public static final String PARAM_OTC = "otc";

    public static final String CODE_CHALLENGE_METHOD_S256 = "S256";
    public static final String SHA_256 = "SHA-256";

    public static final JWSAlgorithm JWS_ALG_RS256 = JWSAlgorithm.RS256;

    public static final String TOKEN_FIELD_ID_TOKEN = "id_token";

    @Getter
    @RequiredArgsConstructor
    public enum GrantType {
        AUTHORIZATION_CODE("authorization_code");

        public static final String FIELD = "grant_type";

        private final String value;
    }

    @Getter
    @RequiredArgsConstructor
    public enum OidcClaim {
        SUB("sub"),
        EMAIL("email"),
        NAME("name"),
        NONCE("nonce");

        private final String value;
    }
}