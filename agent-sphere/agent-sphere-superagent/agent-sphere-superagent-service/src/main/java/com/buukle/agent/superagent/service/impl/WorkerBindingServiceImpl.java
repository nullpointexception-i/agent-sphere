package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.superagent.domain.AgentWorkItem;
import com.buukle.agent.superagent.dtvo.enums.WorkItemStatus;
import com.buukle.agent.superagent.repository.AgentWorkItemMapper;
import com.buukle.agent.superagent.spi.WorkerBindingPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Worker 会话绑定：只写 worker_session_id，不改终态，交由调度器/终态回报推进状态。 */
@Service
@RequiredArgsConstructor
public class WorkerBindingServiceImpl implements WorkerBindingPort {

    private final AgentWorkItemMapper workItemMapper;

    @Override
    public void bind(Long workItemId, Long workerSessionId) {
        if (workItemId == null || workerSessionId == null) {
            return;
        }
        workItemMapper.update(null, Wrappers.<AgentWorkItem>lambdaUpdate()
                .eq(AgentWorkItem::getId, workItemId)
                .in(AgentWorkItem::getStatus, WorkItemStatus.QUEUED, WorkItemStatus.DISPATCHING)
                .set(AgentWorkItem::getWorkerSessionId, workerSessionId)
                .set(AgentWorkItem::getLastProgressAt, LocalDateTime.now()));
    }
}
