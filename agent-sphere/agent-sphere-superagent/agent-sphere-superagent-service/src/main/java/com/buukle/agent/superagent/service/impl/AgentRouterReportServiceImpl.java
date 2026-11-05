package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.dtvo.enums.AgentEventDeliveryStatus;
import com.buukle.agent.superagent.dtvo.enums.AgentRouterRunStatus;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.spi.AgentRouterReportPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Applies Agent Router run terminal state to claimed deliveries. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRouterReportServiceImpl implements AgentRouterReportPort {

    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_BASE_SECONDS = 30;

    private final AgentEventDeliveryMapper deliveryMapper;

    @Override
    public void onRouterTerminal(Long sessionId, Long runId, String status) {
        if (runId == null) {
            return;
        }
        if (AgentRouterRunStatus.COMPLETED.equals(status)) {
            deliveryMapper.update(null, Wrappers.<AgentEventDelivery>lambdaUpdate()
                    .eq(AgentEventDelivery::getRouterRunId, runId)
                    .eq(AgentEventDelivery::getStatus, AgentEventDeliveryStatus.RUNNING)
                    .set(AgentEventDelivery::getStatus, AgentEventDeliveryStatus.PROCESSED)
                    .set(AgentEventDelivery::getDeliveredAt, LocalDateTime.now()));
            return;
        }
        var running = deliveryMapper.selectList(Wrappers.<AgentEventDelivery>lambdaQuery()
                .eq(AgentEventDelivery::getRouterRunId, runId)
                .eq(AgentEventDelivery::getStatus, AgentEventDeliveryStatus.RUNNING));
        for (AgentEventDelivery delivery : running) {
            int attempts = (delivery.getAttempts() == null ? 0 : delivery.getAttempts()) + 1;
            delivery.setAttempts(attempts);
            if (attempts >= MAX_ATTEMPTS) {
                delivery.setStatus(AgentEventDeliveryStatus.FAILED);
                delivery.setReason("router run failed after retries");
            } else {
                delivery.setStatus(AgentEventDeliveryStatus.PENDING);
                delivery.setNextRetryAt(LocalDateTime.now().plusSeconds(
                        RETRY_BASE_SECONDS * (1L << Math.min(attempts, 5))));
            }
            deliveryMapper.updateById(delivery);
        }
    }
}
