package com.buukle.agent.capability.builtin.controller;

import com.buukle.agent.capability.builtin.service.BuiltinToolService;
import com.buukle.agent.common.util.BaseController;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/capability/builtin")
@RequiredArgsConstructor
public class BuiltinToolController extends BaseController {
    private final BuiltinToolService builtinToolService;

    @GetMapping
    public ResponseEntity<?> list() {
        return ok(builtinToolService.listBuiltinTools());
    }

}
