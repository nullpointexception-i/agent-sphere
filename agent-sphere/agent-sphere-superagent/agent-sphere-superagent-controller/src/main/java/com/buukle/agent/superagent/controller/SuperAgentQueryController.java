package com.buukle.agent.superagent.controller;



import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.service.SuperAgentEventService;
import com.buukle.agent.superagent.service.SuperAgentUsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(ApiPrefix.V1 + "/super-agents/{superAgentId}")
@WithTenant
@RequiredArgsConstructor
public class SuperAgentQueryController extends BaseController {

    private static final int MAX_ROWS = 50;

    private final AgentEventDeliveryMapper deliveryMapper;
    private final AgentEventMapper eventMapper;
    private final AgentThreadMapper threadMapper;
    private final SuperAgentEventService eventService;
    private final SuperAgentUsageService usageService;

    @RequirePermission(PermissionCodes.SUPER_AGENT_READ)
    @GetMapping("/events")
    public ResponseEntity<?> events(@PathVariable Long superAgentId) {
        List<Long> eventIds = deliveryMapper.selectList(Wrappers.<AgentEventDelivery>lambdaQuery()
                        .eq(AgentEventDelivery::getSuperAgentId, superAgentId)
                        .orderByDesc(AgentEventDelivery::getId)
                        .last("LIMIT " + MAX_ROWS))
                .stream().map(AgentEventDelivery::getEventId).toList();
        if (eventIds.isEmpty()) {
            return ok(List.of());
        }
        return ok(eventMapper.selectList(Wrappers.<AgentEvent>lambdaQuery()
                .in(AgentEvent::getId, eventIds)
                .orderByDesc(AgentEvent::getId)));
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @PostMapping("/events/{eventId}/retry")
    public ResponseEntity<?> retryEvent(@PathVariable Long superAgentId,
                                        @PathVariable Long eventId) {
        eventService.retry(superAgentId, eventId);
        return ok();
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_READ)
    @GetMapping("/usage")
    public ResponseEntity<?> usage(@PathVariable Long superAgentId,
                                   @RequestParam(defaultValue = "30") int days) {
        return ok(usageService.usage(superAgentId, days));
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_READ)
    @GetMapping("/threads")
    public ResponseEntity<?> threads(@PathVariable Long superAgentId) {
        List<Long> eventIds = deliveryMapper.selectList(Wrappers.<AgentEventDelivery>lambdaQuery()
                        .eq(AgentEventDelivery::getSuperAgentId, superAgentId)
                        .last("LIMIT " + MAX_ROWS))
                .stream().map(AgentEventDelivery::getEventId).toList();
        if (eventIds.isEmpty()) {
            return ok(List.of());
        }
        List<Long> threadIds = eventMapper.selectList(Wrappers.<AgentEvent>lambdaQuery()
                        .in(AgentEvent::getId, eventIds))
                .stream().map(AgentEvent::getThreadId).filter(java.util.Objects::nonNull).distinct().toList();
        if (threadIds.isEmpty()) {
            return ok(List.of());
        }
        return ok(threadMapper.selectList(Wrappers.<AgentThread>lambdaQuery()
                .in(AgentThread::getId, threadIds)
                .orderByDesc(AgentThread::getId)));
    }
}
