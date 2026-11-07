package com.buukle.agent.sso.controller;


import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.sso.dtvo.vo.SsoIdentityVO;
import com.buukle.agent.sso.service.SsoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SSO identity lookup for the current user (Bearer auth).
 * Note: this must not live under /api/v1/auth/sso (that prefix is on the auth whitelist, so AuthContext is unavailable).
 */
@RestController
@RequestMapping(ApiPrefix.V1 + "/sso")
@RequiredArgsConstructor
public class SsoIdentityController extends BaseController {

    private final SsoService ssoService;

    @GetMapping("/me")
    public ResponseEntity<SsoIdentityVO> me() {
        return ok(ssoService.getCurrentIdentity());
    }
}
