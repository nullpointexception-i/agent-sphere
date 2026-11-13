package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.constant.ActorType;
import com.buukle.agent.common.constant.ProjectAssistantConstants;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.dtvo.enums.AgentThreadState;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.spi.ProjectThreadSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Creates and reads the single thread that represents a project. */
@Service
@RequiredArgsConstructor
public class ProjectThreadServiceImpl implements ProjectThreadSpi {

    private final AgentThreadMapper threadMapper;

    @Override
    public Long ensureProjectThread(Long tenantId, Long projectId, String title, String createdBy) {
        if (tenantId == null || projectId == null) {
            return null;
        }
        String externalId = ProjectAssistantConstants.THREAD_EXTERNAL_PREFIX + projectId;
        AgentThread thread = threadMapper.selectOne(Wrappers.<AgentThread>lambdaQuery()
                .eq(AgentThread::getTenantId, tenantId)
                .eq(AgentThread::getExternalThreadId, externalId)
                .eq(AgentThread::getKind, ProjectAssistantConstants.THREAD_KIND_PROJECT)
                .last("LIMIT 1"));
        if (thread == null) {
            thread = new AgentThread();
            thread.setTenantId(tenantId);
            thread.setExternalThreadId(externalId);
            thread.setKind(ProjectAssistantConstants.THREAD_KIND_PROJECT);
            thread.setTitle(title);
            thread.setEventSeq(0L);
            thread.setState(AgentThreadState.OPEN);
            thread.setActorType(ActorType.PROJECT);
            thread.setActorId(projectId);
            thread.setCreatedBy(createdBy);
            thread.setUpdatedBy(createdBy);
            threadMapper.insert(thread);
            return thread.getId();
        }
        if (title != null && !Objects.equals(title, thread.getTitle())) {
            thread.setTitle(title);
            thread.setUpdatedBy(createdBy);
            threadMapper.updateById(thread);
        }
        return thread.getId();
    }

    @Override
    public Long findProjectThread(Long tenantId, Long projectId) {
        if (tenantId == null || projectId == null) {
            return null;
        }
        AgentThread thread = threadMapper.selectOne(Wrappers.<AgentThread>lambdaQuery()
                .eq(AgentThread::getTenantId, tenantId)
                .eq(AgentThread::getExternalThreadId,
                        ProjectAssistantConstants.THREAD_EXTERNAL_PREFIX + projectId)
                .eq(AgentThread::getKind, ProjectAssistantConstants.THREAD_KIND_PROJECT)
                .last("LIMIT 1"));
        return thread == null ? null : thread.getId();
    }
}
