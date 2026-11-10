package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.constant.ActorType;
import com.buukle.agent.common.context.SessionSurface;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.RouteSpi;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.dtvo.enums.AgentThreadState;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.spi.GroupChatBootstrapSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Creates the IM thread and group-router session at conversation creation time. */
@Service
@RequiredArgsConstructor
public class GroupChatBootstrapServiceImpl implements GroupChatBootstrapSpi {

    private static final String CHANNEL_KIND_IM = "IM";

    private final AgentThreadMapper threadMapper;
    private final InstanceSpi instanceSpi;
    private final SessionSpi sessionSpi;
    private final RouteSpi routeSpi;

    @Override
    @Transactional
    public GroupChatBootstrapResult bootstrap(GroupChatBootstrapCommand command) {
        if (command == null || command.conversationId() == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "conversationId is required");
        }
        boolean group = "GROUP".equalsIgnoreCase(command.conversationType());
        AgentThread thread = findThread(command.conversationId());
        if (thread == null) {
            thread = createThread(command);
        }
        Long groupRouterSessionId = thread.getGroupRouterSessionId();
        if (group && groupRouterSessionId == null) {
            groupRouterSessionId = createGroupRouterSession(thread, command);
            thread.setGroupRouterSessionId(groupRouterSessionId);
            threadMapper.updateById(thread);
        }
        return new GroupChatBootstrapResult(thread.getId(), groupRouterSessionId);
    }

    private AgentThread findThread(Long conversationId) {
        return threadMapper.selectOne(Wrappers.<AgentThread>lambdaQuery()
                .eq(AgentThread::getExternalThreadId, String.valueOf(conversationId))
                .isNull(AgentThread::getChannelBindingId)
                .last("LIMIT 1"));
    }

    private AgentThread createThread(GroupChatBootstrapCommand command) {
        AgentThread thread = new AgentThread();
        thread.setTenantId(command.tenantId());
        thread.setExternalThreadId(String.valueOf(command.conversationId()));
        thread.setKind(CHANNEL_KIND_IM);
        thread.setTitle(command.title());
        thread.setEventSeq(0L);
        thread.setState(AgentThreadState.OPEN);
        thread.setActorType(ActorType.USER);
        thread.setActorId(command.creatorUserId());
        thread.setCreatedBy(command.creatorUsername());
        thread.setUpdatedBy(command.creatorUsername());
        threadMapper.insert(thread);
        return thread;
    }

    private Long createGroupRouterSession(AgentThread thread, GroupChatBootstrapCommand command) {
        InstanceVO router = instanceSpi.findCurrentGroupRouter(command.tenantId());
        if (router == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "tenant current GROUP_ROUTER instance is not configured");
        }
        Long modelRouteId = router.getModelRouteId();
        if (modelRouteId == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "tenant current instance has no model route");
        }
        ModelRouteVO route = routeSpi.getRoute(modelRouteId);
        if (!Boolean.TRUE.equals(route.getApiKeyConfigured())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "tenant current provider API key is not configured");
        }
        CreateSessionDTO sessionDTO = new CreateSessionDTO();
        sessionDTO.setAgentInstanceId(router.getId());
        sessionDTO.setTitle("Group Router " + command.conversationId());
        sessionDTO.setSessionType(SessionType.GROUP_ROUTER);
        sessionDTO.setThreadId(thread.getId());
        sessionDTO.setCreatedBy(command.creatorUsername());
        sessionDTO.setOverrideModelRouteId(modelRouteId);
        SessionSurface previousSurface = SessionSurfaceHolder.current();
        SessionSurfaceHolder.set(SessionSurface.INTERNAL);
        try {
            SessionVO session = sessionSpi.createSession(sessionDTO);
            return session.getId();
        } finally {
            SessionSurfaceHolder.set(previousSurface);
        }
    }
}
