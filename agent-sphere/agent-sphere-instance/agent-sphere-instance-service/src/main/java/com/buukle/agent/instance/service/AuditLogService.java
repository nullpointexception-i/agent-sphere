package com.buukle.agent.instance.service;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.event.AuditEvent;
import com.buukle.agent.common.util.RequestUtils;
import com.buukle.agent.instance.domain.AgentAuditLog;
import com.buukle.agent.instance.dtvo.dto.FrontendTrackDTO;
import com.buukle.agent.instance.repository.AgentAuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final int MAX_FE_PER_WINDOW = 50;
    private static final int FE_WINDOW_SECONDS = 10;

    private final ConcurrentHashMap<String, int[]> frontendRateMap = new ConcurrentHashMap<>();

    private final AgentAuditLogMapper auditLogMapper;

    @Async("auditTaskExecutor")
    @EventListener
    public void handleAuditEvent(AuditEvent event) {
        AgentAuditLog entity = new AgentAuditLog();
        entity.setUserId(event.getUserId());
        entity.setUsername(event.getUsername());
        entity.setAction(event.getAction());
        entity.setResourceType(event.getResourceType());
        entity.setResourceId(event.getResourceId());
        entity.setDetail(event.getDetail());
        entity.setSuccess(event.isSuccess());
        entity.setErrorMessage(event.getErrorMessage());
        entity.setCreatedAt(LocalDateTime.now());

        String ip = event.getIpAddress();
        String ua = event.getUserAgent();
        if (ip == null || ua == null) {
            try {
                var attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attr != null) {
                    if (ip == null) ip = RequestUtils.resolveClientIp(attr.getRequest());
                    if (ua == null) ua = RequestUtils.resolveUserAgent(attr.getRequest());
                }
            } catch (Exception ignored) {
            }
        }
        entity.setIpAddress(ip);
        entity.setUserAgent(ua);

        auditLogMapper.insert(entity);
    }

    public void recordFrontendEvents(List<FrontendTrackDTO> events) {
        if (events == null || events.isEmpty()) return;

        Long userId = AuthContext.getUserId();
        if (userId != null) {
            String key = "fe:" + userId;
            int now = (int) (System.currentTimeMillis() / 1000);
            int window = now / FE_WINDOW_SECONDS;
            int[] state = frontendRateMap.get(key);
            if (state != null && state[0] == window && state[1] >= MAX_FE_PER_WINDOW) return;
            if (state == null || state[0] != window) {
                frontendRateMap.put(key, new int[]{window, events.size()});
            } else {
                state[1] += events.size();
            }
        }

        String ip = null;
        String ua = null;
        try {
            var attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attr != null) {
                ip = RequestUtils.resolveClientIp(attr.getRequest());
                ua = RequestUtils.resolveUserAgent(attr.getRequest());
            }
        } catch (Exception ignored) {
        }

        for (FrontendTrackDTO event : events) {
            AgentAuditLog entity = new AgentAuditLog();
            entity.setUserId(AuthContext.getUserId());
            entity.setUsername(AuthContext.getUsername());
            entity.setAction(event.getEventType());
            entity.setResourceType("Frontend");
            entity.setResourceId(event.getPage());
            entity.setDetail("%s | %s | pos=%s,%s | text=%s".formatted(
                    event.getElementPath() != null ? event.getElementPath() : "",
                    event.getElementTag() != null ? event.getElementTag() : "",
                    event.getPositionX() != null ? event.getPositionX() : "",
                    event.getPositionY() != null ? event.getPositionY() : "",
                    event.getElementText() != null ? event.getElementText() : ""
            ));
            entity.setIpAddress(ip);
            entity.setUserAgent(ua);
            entity.setSuccess(true);
            entity.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(entity);
        }
    }
}
