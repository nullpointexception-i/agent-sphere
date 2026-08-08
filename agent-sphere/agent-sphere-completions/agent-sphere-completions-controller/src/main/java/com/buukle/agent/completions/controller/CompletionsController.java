package com.buukle.agent.completions.controller;

import com.buukle.agent.completions.dtvo.ChatCompletionsReq;
import com.buukle.agent.completions.dtvo.ChatCompletionsResp;
import com.buukle.agent.completions.dtvo.CompletionsInput;
import com.buukle.agent.completions.service.CompletionsService;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.sso.spi.CallerAuth;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单次 LLM 能力调用（外部开放接口，业务层以 code+subject+businessType 认证）。
 */
@RestController
@RequestMapping("/api/v1/api/completions")
@RequiredArgsConstructor
public class CompletionsController extends BaseController {

    private final CompletionsService completionsService;

    @PostMapping
    public ResponseEntity<ChatCompletionsResp> execute(@Valid @RequestBody ChatCompletionsReq req) {
        CallerAuth auth = CallerAuth.of(req.getCode(), req.getSubject(), req.getBusinessType());
        return ok(completionsService.execute(auth, CompletionsInput.of(req.getInput())));
    }
}
