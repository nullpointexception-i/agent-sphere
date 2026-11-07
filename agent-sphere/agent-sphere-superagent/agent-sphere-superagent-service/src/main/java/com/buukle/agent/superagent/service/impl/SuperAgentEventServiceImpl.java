package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.context.TenantScopeHolder;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.dtvo.enums.AgentEventDeliveryStatus;
import com.buukle.agent.superagent.dtvo.enums.AgentEventStatus;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.service.SuperAgentEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAgentEventServiceImpl implements SuperAgentEventService {

    private static final Set<String> IN_FLIGHT = Set.of(
            AgentEventDeliveryStatus.PENDING,
            AgentEventDeliveryStatus.CLAIMED,
            AgentEventDeliveryStatus.RUNNING);
    private static final String MANUAL_RETRY_REASON = "manual retry";

    private final AgentEventDeliveryMapper deliveryMapper;
    private final AgentEventMapper eventMapper;
    private final AgentSuperAgentMapper superAgentMapper;

    @Override
    public void retry(Long superAgentId, Long eventId) {
        assertOwned(superAgentId);
        AgentEvent event = systemAccess(() -> eventMapper.selectById(eventId));
        if (event == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "event not found");
        }
        AgentEventDelivery delivery = systemAccess(() -> deliveryMapper.selectOne(
                Wrappers.<AgentEventDelivery>lambdaQuery()
                        .eq(AgentEventDelivery::getEventId, eventId)
                        .eq(AgentEventDelivery::getSuperAgentId, superAgentId)
                        .last("LIMIT 1")));
        if (delivery == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND,
                    "event delivery not found for this super agent");
        }
        if (IN_FLIGHT.contains(delivery.getStatus())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "event is still being processed");
        }
        delivery.setStatus(AgentEventDeliveryStatus.PENDING);
        delivery.setAttempts(0);
        delivery.setNextRetryAt(null);
        delivery.setClaimedAt(null);
        delivery.setDeliveredAt(null);
        delivery.setRouterRunId(null);
        delivery.setReason(MANUAL_RETRY_REASON);
        deliveryMapper.updateById(delivery);

        event.setStatus(AgentEventStatus.DELIVERED);
        event.setAttempts(0);
        event.setNextRetryAt(null);
        eventMapper.updateById(event);
        log.info("Event delivery re-queued: event={}, superAgent={}, delivery={}",
                eventId, superAgentId, delivery.getId());
    }

    /**
     * Events and deliveries are written by schedulers, so created_by filtering must be
     * bypassed; ownership is enforced explicitly by {@link #assertOwned(Long)}.
     */
    private <T> T systemAccess(Supplier<T> supplier) {
        String tenant = TenantUtil.get();
        Long tenantId = TenantContext.get();
        boolean admin = AuthContext.isSuperAdmin();
        TenantUtil.stop();
        TenantScopeHolder.clear();
        TenantContext.clear();
        AuthContext.setSuperAdmin(true);
        try {
            return supplier.get();
        } finally {
            AuthContext.setSuperAdmin(admin);
            TenantContext.set(tenantId);
            if (tenant != null) {
                TenantUtil.start(tenant);
            }
        }
    }

    private void assertOwned(Long superAgentId) {
        AgentSuperAgent agent = superAgentMapper.selectById(superAgentId);
        if (agent == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "super agent not found");
        }
        if (!AuthContext.isSuperAdmin() && !AuthContext.getUserId().equals(agent.getOwnerUserId())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }
}
