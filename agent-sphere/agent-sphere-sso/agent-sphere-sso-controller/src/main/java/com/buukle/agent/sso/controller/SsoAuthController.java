package com.buukle.agent.sso.controller;

import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.sso.dtvo.SsoAuthorizeVO;
import com.buukle.agent.sso.dtvo.SsoExchangeDTO;
import com.buukle.agent.sso.service.SsoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/auth/sso")
@RequiredArgsConstructor
public class SsoAuthController extends BaseController {

    private final SsoService ssoService;

    @GetMapping("/authorize")
    public ResponseEntity<SsoAuthorizeVO> authorize(@RequestParam String provider,
                                                    @RequestParam(value = "redirect_uri", required = false) String redirectUri,
                                                    @RequestParam(required = false) String prompt) {
        return ok(ssoService.authorize(provider, redirectUri, prompt));
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String iss,
                                         @RequestParam(required = false) String error) {
        String redirectUri = ssoService.callback(code, state, iss, error);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUri)).build();
    }

    @PostMapping("/exchange")
    public ResponseEntity<UserVO> exchange(@Valid @RequestBody SsoExchangeDTO dto) {
        return ok(ssoService.exchange(dto.getOtc()));
    }
}