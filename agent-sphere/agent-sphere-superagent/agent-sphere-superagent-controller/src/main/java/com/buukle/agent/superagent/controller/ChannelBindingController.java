package com.buukle.agent.superagent.controller;



import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.superagent.domain.AgentChannelBinding;
import com.buukle.agent.superagent.service.ChannelBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPrefix.V1 + "/super-agents/{superAgentId}/channels")
@WithTenant
@RequiredArgsConstructor
public class ChannelBindingController extends BaseController {

    private final ChannelBindingService channelBindingService;

    @RequirePermission(PermissionCodes.SUPER_AGENT_READ)
    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long superAgentId) {
        return ok(channelBindingService.list(superAgentId));
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_CREATE)
    @PostMapping
    public ResponseEntity<?> create(@PathVariable Long superAgentId,
                                    @RequestBody AgentChannelBinding binding) {
        binding.setSuperAgentId(superAgentId);
        return created(channelBindingService.create(binding));
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long superAgentId,
                                          @PathVariable Long id,
                                          @RequestParam String status) {
        return ok(channelBindingService.updateStatus(id, status));
    }
}
