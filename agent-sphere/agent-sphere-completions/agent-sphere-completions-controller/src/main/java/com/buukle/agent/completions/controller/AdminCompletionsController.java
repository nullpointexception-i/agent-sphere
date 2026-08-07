package com.buukle.agent.completions.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.completions.dtvo.ActivatePromptDTO;
import com.buukle.agent.completions.dtvo.CompletionsCallVO;
import com.buukle.agent.completions.dtvo.CompletionsPromptVO;
import com.buukle.agent.completions.dtvo.CompletionsVO;
import com.buukle.agent.completions.dtvo.CreateCompletionsDTO;
import com.buukle.agent.completions.dtvo.CreatePromptDTO;
import com.buukle.agent.completions.service.CompletionsPromptService;
import com.buukle.agent.completions.service.CompletionsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * completions 能力管理（RBAC：admin:completions:*）。
 */
@WithTenant
@RestController
@RequestMapping("/api/v1/admin/completions")
@RequiredArgsConstructor
public class AdminCompletionsController extends BaseController {

    private final CompletionsService completionsService;
    private final CompletionsPromptService completionsPromptService;

    @RequirePermission("admin:completions:read")
    @GetMapping
    public ResponseEntity<Page<CompletionsVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ok(completionsService.list(keyword, page, size));
    }

    @AuditLog(action = "CREATE", resourceType = "Completions", resourceId = "#result?.body?.id")
    @RequirePermission("admin:completions:create")
    @PostMapping
    public ResponseEntity<CompletionsVO> create(@Valid @RequestBody CreateCompletionsDTO dto) {
        return created(completionsService.create(dto));
    }

    @RequirePermission("admin:completions:read")
    @GetMapping("/{id}")
    public ResponseEntity<CompletionsVO> detail(@PathVariable Long id) {
        return ok(completionsService.detail(id));
    }

    @AuditLog(action = "UPDATE", resourceType = "Completions", resourceId = "#id")
    @RequirePermission("admin:completions:update")
    @PutMapping("/{id}")
    public ResponseEntity<CompletionsVO> update(@PathVariable Long id, @Valid @RequestBody CreateCompletionsDTO dto) {
        return ok(completionsService.update(id, dto));
    }

    @AuditLog(action = "DELETE", resourceType = "Completions", resourceId = "#id")
    @RequirePermission("admin:completions:delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        completionsService.delete(id);
        return deleted();
    }

    @AuditLog(action = "ADD_PROMPT", resourceType = "Completions", resourceId = "#id")
    @RequirePermission("admin:completions:update")
    @PostMapping("/{id}/prompts")
    public ResponseEntity<CompletionsPromptVO> addPrompt(@PathVariable Long id, @Valid @RequestBody CreatePromptDTO dto) {
        return created(completionsPromptService.addVersion(id, dto));
    }

    @RequirePermission("admin:completions:read")
    @GetMapping("/{id}/prompts")
    public ResponseEntity<List<CompletionsPromptVO>> listPrompts(@PathVariable Long id) {
        return ok(completionsPromptService.listByCompletions(id));
    }

    @AuditLog(action = "ACTIVATE_PROMPT", resourceType = "Completions", resourceId = "#id")
    @RequirePermission("admin:completions:update")
    @PutMapping("/{id}/activate")
    public ResponseEntity<?> activate(@PathVariable Long id, @Valid @RequestBody ActivatePromptDTO dto) {
        completionsPromptService.activate(id, dto.getPromptId());
        return ok();
    }

    @RequirePermission("admin:completions:read")
    @GetMapping("/{id}/calls")
    public ResponseEntity<Page<CompletionsCallVO>> listCalls(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ok(completionsService.listCalls(id, page, size));
    }
}
