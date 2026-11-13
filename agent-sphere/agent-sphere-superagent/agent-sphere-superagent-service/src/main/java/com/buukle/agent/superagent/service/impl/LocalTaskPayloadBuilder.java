package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.channel.spi.OutboundFormat;
import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.domain.AgentLocalSession;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.dtvo.vo.LocalTaskPayloadVO;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentLocalSessionMapper;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.spi.ImEventMetaKeys;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Builds the structured task payload sent to a desktop runtime. */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalTaskPayloadBuilder {

    private static final int MAX_TRANSCRIPT_CHARS = 12_000;
    private static final int DEFAULT_MAX_REPLY_CHARS = 4_000;
    private static final String DEFAULT_SPEAKER = "User";

    private final AgentEventMapper eventMapper;
    private final AgentThreadMapper threadMapper;
    private final AgentLocalSessionMapper sessionMapper;

    public LocalTaskPayloadVO build(AgentSuperAgent agent, Long threadId,
                                    List<AgentEventDelivery> deliveries, long timeoutSeconds) {
        AgentThread thread = threadMapper.selectById(threadId);

        LocalTaskPayloadVO payload = new LocalTaskPayloadVO();
        payload.setAgentId(agent.getId());
        payload.setAgentName(agent.getName());
        payload.setThread(threadInfo(thread, threadId));
        payload.setPersona(persona(agent));
        payload.setMessages(messages(deliveries));
        payload.setTrigger(trigger(deliveries));
        payload.setReply(reply(agent));
        payload.setSessionRef(sessionRef(agent.getId(), threadId));
        payload.setDeadlineAt(LocalDateTime.now().plusSeconds(Math.max(30, timeoutSeconds)).toString());
        return payload;
    }

    private LocalTaskPayloadVO.ThreadInfo threadInfo(AgentThread thread, Long threadId) {
        LocalTaskPayloadVO.ThreadInfo info = new LocalTaskPayloadVO.ThreadInfo();
        info.setId(threadId);
        if (thread != null) {
            info.setTitle(thread.getTitle());
            info.setSummary(thread.getSummary());
        }
        return info;
    }

    private LocalTaskPayloadVO.Persona persona(AgentSuperAgent agent) {
        LocalTaskPayloadVO.Persona persona = new LocalTaskPayloadVO.Persona();
        persona.setPosition(agent.getPositionNameCn() != null && !agent.getPositionNameCn().isBlank()
                ? agent.getPositionNameCn() : agent.getPositionNameEn());
        persona.setDescription(agent.getDescription());
        persona.setInstructions(instructions(agent.getConfig()));
        return persona;
    }

    private LocalTaskPayloadVO.Trigger trigger(List<AgentEventDelivery> deliveries) {
        LocalTaskPayloadVO.Trigger trigger = new LocalTaskPayloadVO.Trigger();
        trigger.setDeliveryIds(deliveries.stream()
                .map(AgentEventDelivery::getId).filter(Objects::nonNull).toList());
        trigger.setEventIds(deliveries.stream()
                .map(AgentEventDelivery::getEventId).filter(Objects::nonNull).toList());
        AgentEventDelivery first = deliveries.isEmpty() ? null : deliveries.get(0);
        if (first != null) {
            trigger.setReason(first.getReason());
        }
        List<AgentEvent> events = loadEvents(deliveries);
        if (!events.isEmpty()) {
            trigger.setSeqFrom(events.get(0).getSeq());
            trigger.setSeqTo(events.get(events.size() - 1).getSeq());
        }
        return trigger;
    }

    private LocalTaskPayloadVO.Reply reply(AgentSuperAgent agent) {
        LocalTaskPayloadVO.Reply reply = new LocalTaskPayloadVO.Reply();
        reply.setMaxChars(DEFAULT_MAX_REPLY_CHARS);
        reply.setFormat(OutboundFormat.TEXT);
        try {
            JsonNode config = parse(agent.getConfig());
            if (config != null && config.hasNonNull("maxReplyChars")) {
                reply.setMaxChars(Math.max(200, config.get("maxReplyChars").asInt()));
            }
        } catch (Exception ignored) {
            // keep defaults
        }
        return reply;
    }

    private List<LocalTaskPayloadVO.Message> messages(List<AgentEventDelivery> deliveries) {
        List<AgentEvent> events = loadEvents(deliveries);
        List<LocalTaskPayloadVO.Message> rows = new ArrayList<>(events.size());
        for (AgentEvent event : events) {
            LocalTaskPayloadVO.Message message = new LocalTaskPayloadVO.Message();
            message.setSeq(event.getSeq());
            message.setSpeaker(speaker(event));
            message.setSpeakerType("USER");
            message.setText(text(event));
            message.setAt(event.getReceivedAt() == null ? null : event.getReceivedAt().toString());
            rows.add(message);
        }

        int used = 0;
        List<LocalTaskPayloadVO.Message> selected = new ArrayList<>(rows.size());
        for (int index = rows.size() - 1; index >= 0; index--) {
            LocalTaskPayloadVO.Message message = rows.get(index);
            int cost = message.getText() == null ? 0 : message.getText().length();
            if (!selected.isEmpty() && used + cost > MAX_TRANSCRIPT_CHARS) {
                break;
            }
            selected.add(0, message);
            used += cost + 1;
        }
        return selected;
    }

    private List<AgentEvent> loadEvents(List<AgentEventDelivery> deliveries) {
        List<Long> eventIds = deliveries.stream()
                .map(AgentEventDelivery::getEventId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (eventIds.isEmpty()) return List.of();
        return eventMapper.selectBatchIds(eventIds).stream()
                .sorted(Comparator.comparing(AgentEvent::getSeq,
                        Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    private String sessionRef(Long agentId, Long threadId) {
        AgentLocalSession session = sessionMapper.selectOne(
                Wrappers.<AgentLocalSession>lambdaQuery()
                        .eq(AgentLocalSession::getSuperAgentId, agentId)
                        .eq(AgentLocalSession::getThreadId, threadId)
                        .last("LIMIT 1"));
        return session == null ? null : session.getSessionRef();
    }

    private String text(AgentEvent event) {
        try {
            JsonNode root = parse(event.getPayload());
            if (root != null) {
                JsonNode text = root.get("text");
                if (text != null && !text.isNull()) return text.asText("");
            }
        } catch (Exception e) {
            log.debug("Local task event text parse failed: event={}, error={}",
                    event.getId(), e.getMessage());
        }
        return event.getPayload() == null ? "" : event.getPayload();
    }

    private String speaker(AgentEvent event) {
        try {
            JsonNode root = parse(event.getPayload());
            if (root != null) {
                String rawMeta = root.path("rawMeta").asText("");
                if (!rawMeta.isBlank()) {
                    JsonNode meta = parse(rawMeta);
                    if (meta != null) {
                        String name = meta.path(ImEventMetaKeys.SPEAKER_NAME).asText("");
                        if (!name.isBlank()) return name;
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return event.getActorId() == null
                ? DEFAULT_SPEAKER : DEFAULT_SPEAKER + " #" + event.getActorId();
    }

    private String instructions(String config) {
        if (config == null || config.isBlank()) return null;
        try {
            JsonNode root = parse(config);
            if (root == null) return null;
            for (String key : List.of("systemPrompt", "instructions", "prompt")) {
                String value = root.path(key).asText("");
                if (!value.isBlank()) return value;
            }
        } catch (Exception ignored) {
            // ignore malformed config
        }
        return null;
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JsonUtils.getMapper().readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
