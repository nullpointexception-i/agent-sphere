package com.buukle.agent.sso.controller;

import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.sso.dtvo.vo.SsoIdentityVO;
import com.buukle.agent.sso.service.SsoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户的 SSO 身份查询（Bearer 鉴权）。
 * 注意：不能挂在 /api/v1/auth/sso 下（该前缀在鉴权白名单内，拿不到 AuthContext）。
 */
@RestController
@RequestMapping("/api/v1/sso")
@RequiredArgsConstructor
public class SsoIdentityController extends BaseController {

    private final SsoService ssoService;

    @GetMapping("/me")
    public ResponseEntity<SsoIdentityVO> me() {
        return ok(ssoService.getCurrentIdentity());
    }
}
