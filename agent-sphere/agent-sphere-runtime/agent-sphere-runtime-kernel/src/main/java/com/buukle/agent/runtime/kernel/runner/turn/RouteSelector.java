package com.buukle.agent.runtime.kernel.runner.turn;

import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.util.ScreenshotObservationSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/** Resolves model routes, attachment-capability filtering and route API keys for a turn. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteSelector {

    private final RouteListBuilder routeListBuilder;
    private final SessionSpi sessionSpi;
    private final ApiKeySpi apiKeySpi;
    private final ApplicationEventPublisher eventPublisher;

    public List<ModelRouteFullVO> resolveRoutes(Long sessionId, KernelContext ctx) {
        if (ctx != null && (ctx.getModelRoute() != null || ctx.getFallbackRoutes() != null)) {
            return routeListBuilder.fromContext(ctx);
        }
        SessionVO session = sessionId != null ? sessionSpi.getSession(sessionId) : null;
        if (session != null && session.getAgentInstanceId() != null) {
            return routeListBuilder.fromInstance(session.getAgentInstanceId());
        }
        return List.of();
    }

    /** Keeps only attachment-capable routes; degrades to text when none can accept images. */
    public List<ModelRouteFullVO> selectAttachmentCapableRoutes(List<ModelRouteFullVO> routes,
                                                                List<com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO> messages,
                                                                Long sessionId, Long runId) {
        if (!ScreenshotObservationSupport.hasAttachmentImage(messages) || routes.isEmpty()) {
            return routes;
        }
        List<ModelRouteFullVO> capable = routes.stream()
                .filter(r -> Boolean.TRUE.equals(r.getSupportsAttachment()))
                .toList();
        if (!capable.isEmpty()) {
            return capable;
        }
        log.info("No attachment-capable route, degrading to text: session={}, run={}", sessionId, runId);
        ScreenshotObservationSupport.stripImageParts(messages);
        eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId).runId(runId)
                        .response("⚠️ The current route does not support images; continuing with text only")
                        .reasoningType(com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                        .reasoningSubType(com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                        .publishId(java.util.UUID.randomUUID().toString()).build()));
        return routes;
    }

    public String resolveApiKey(ModelRouteFullVO route) {
        if (route.getApiKeyId() == null) return "";
        try {
            return apiKeySpi.getApiKeyValue(route.getApiKeyId());
        } catch (Exception e) {
            log.warn("Failed to resolve API key id={}", route.getApiKeyId(), e);
            return "";
        }
    }
}
