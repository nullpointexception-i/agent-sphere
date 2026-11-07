package com.buukle.agent.im.service.impl;

import com.buukle.agent.common.context.TenantScopeHolder;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.im.domain.ImConversation;
import com.buukle.agent.im.domain.ImParticipant;
import com.buukle.agent.im.dtvo.dto.ResolveSessionInputDTO;
import com.buukle.agent.im.dtvo.enums.ImParticipantType;
import com.buukle.agent.im.repository.ImConversationMapper;
import com.buukle.agent.im.repository.ImParticipantMapper;
import com.buukle.agent.im.service.ImSessionInputService;
import com.buukle.agent.superagent.spi.SessionInputSpi;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Bridges DM card decisions to the Router parked-run resume port. Membership and
 * tenant checks stay in the IM module; the cross-module call only ships the
 * thread id so no im tables are read on the other side.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImSessionInputServiceImpl implements ImSessionInputService {

    private final ImConversationMapper conversationMapper;
    private final ImParticipantMapper participantMapper;
    private final ObjectProvider<SessionInputSpi> sessionInputPortProvider;

    @Override
    public void resolveApproval(Long conversationId, String inputId, ResolveSessionInputDTO dto) {
        Long userId = currentUserId();
        assertMember(conversationId, userId);
        ImConversation conversation = systemRead(() -> conversationMapper.selectById(conversationId));
        if (conversation == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "conversation not found");
        }
        if (conversation.getThreadId() == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "conversation thread not ready");
        }
        SessionInputSpi sessionInputSpi = sessionInputPortProvider.getIfAvailable();
        if (sessionInputSpi == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "session input bridge unavailable");
        }
        String decisionsJson = JsonUtils.toJson(dto.getDecisions());
        sessionInputSpi.resolveRouterApproval(conversation.getThreadId(), inputId, decisionsJson);
    }

    private Long currentUserId() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private void assertMember(Long conversationId, Long userId) {
        long count = systemRead(() -> participantMapper.selectCount(Wrappers.<ImParticipant>lambdaQuery()
                .eq(ImParticipant::getConversationId, conversationId)
                .eq(ImParticipant::getParticipantType, ImParticipantType.USER)
                .eq(ImParticipant::getParticipantId, userId)));
        if (count == 0) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }

    private <T> T systemRead(java.util.function.Supplier<T> supplier) {
        String tenant = TenantUtil.get();
        boolean admin = AuthContext.isSuperAdmin();
        TenantUtil.stop();
        TenantScopeHolder.clear();
        AuthContext.setSuperAdmin(true);
        try {
            return supplier.get();
        } finally {
            AuthContext.setSuperAdmin(admin);
            if (tenant != null) {
                TenantUtil.start(tenant);
            }
        }
    }
}
