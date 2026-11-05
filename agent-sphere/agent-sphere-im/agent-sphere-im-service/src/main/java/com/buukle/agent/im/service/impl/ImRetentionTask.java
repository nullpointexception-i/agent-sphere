package com.buukle.agent.im.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.im.domain.ImConversation;
import com.buukle.agent.im.domain.ImMessage;
import com.buukle.agent.im.domain.ImParticipant;
import com.buukle.agent.im.domain.ImReadCursor;
import com.buukle.agent.im.repository.ImConversationMapper;
import com.buukle.agent.im.repository.ImMessageMapper;
import com.buukle.agent.im.repository.ImParticipantMapper;
import com.buukle.agent.im.repository.ImReadCursorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Logical-delete retention for IM messages and read cursors. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImRetentionTask {

    private final ImMessageMapper messageMapper;
    private final ImReadCursorMapper readCursorMapper;
    private final ImParticipantMapper participantMapper;
    private final ImConversationMapper conversationMapper;

    @Value("${buukle.agent.im.enabled:false}")
    private boolean enabled;
    @Value("${buukle.agent.im.retention-days:180}")
    private int retentionDays;

    @Scheduled(cron = "${buukle.agent.im.retention-cron:0 0 4 * * ?}")
    public void run() {
        if (!enabled || retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        try {
            int messages = messageMapper.delete(Wrappers.<ImMessage>lambdaQuery()
                    .lt(ImMessage::getCreatedAt, cutoff));
            int cursors = readCursorMapper.delete(Wrappers.<ImReadCursor>lambdaQuery()
                    .lt(ImReadCursor::getUpdatedAt, cutoff));
            int participants = participantMapper.delete(Wrappers.<ImParticipant>lambdaQuery()
                    .lt(ImParticipant::getUpdatedAt, cutoff));
            int conversations = conversationMapper.delete(Wrappers.<ImConversation>lambdaQuery()
                    .lt(ImConversation::getUpdatedAt, cutoff));
            log.info("IM retention done: messages={}, readCursors={}, participants={}, conversations={}",
                    messages, cursors, participants, conversations);
        } catch (Exception e) {
            log.warn("IM retention failed: {}", e.getMessage());
        }
    }
}
