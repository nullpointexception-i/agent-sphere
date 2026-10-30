package com.buukle.agent.instance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.instance.domain.AgentMessageFeedback;
import com.buukle.agent.instance.repository.AgentMessageFeedbackMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MessageFeedbackService {

    private static final String LIKE = "LIKE";
    private static final String DISLIKE = "DISLIKE";

    private final AgentMessageFeedbackMapper mapper;

    public String setFeedback(Long sessionId, Long runId, Long interactionId, Long userId, String value) {
        if (sessionId == null || interactionId == null || userId == null) {
            return null;
        }
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!LIKE.equals(normalized) && !DISLIKE.equals(normalized)) {
            mapper.delete(new LambdaQueryWrapper<AgentMessageFeedback>()
                    .eq(AgentMessageFeedback::getUserId, userId)
                    .eq(AgentMessageFeedback::getInteractionId, interactionId));
            return null;
        }
        AgentMessageFeedback existing = mapper.selectOne(new LambdaQueryWrapper<AgentMessageFeedback>()
                .eq(AgentMessageFeedback::getUserId, userId)
                .eq(AgentMessageFeedback::getInteractionId, interactionId)
                .last("LIMIT 1"));
        if (existing != null) {
            existing.setValue(normalized);
            mapper.updateById(existing);
        } else {
            AgentMessageFeedback row = new AgentMessageFeedback();
            row.setSessionId(sessionId);
            row.setRunId(runId);
            row.setInteractionId(interactionId);
            row.setUserId(userId);
            row.setValue(normalized);
            mapper.insert(row);
        }
        return normalized;
    }

    public String getFeedback(Long interactionId, Long userId) {
        if (interactionId == null || userId == null) return null;
        AgentMessageFeedback row = mapper.selectOne(new LambdaQueryWrapper<AgentMessageFeedback>()
                .eq(AgentMessageFeedback::getUserId, userId)
                .eq(AgentMessageFeedback::getInteractionId, interactionId)
                .last("LIMIT 1"));
        return row != null ? row.getValue() : null;
    }

    public Map<Long, String> getFeedbackMap(List<Long> interactionIds, Long userId) {
        if (interactionIds == null || interactionIds.isEmpty() || userId == null) {
            return Map.of();
        }
        Map<Long, String> out = new HashMap<>();
        for (AgentMessageFeedback row : mapper.selectList(new LambdaQueryWrapper<AgentMessageFeedback>()
                .eq(AgentMessageFeedback::getUserId, userId)
                .in(AgentMessageFeedback::getInteractionId, interactionIds))) {
            out.put(row.getInteractionId(), row.getValue());
        }
        return out;
    }
}
