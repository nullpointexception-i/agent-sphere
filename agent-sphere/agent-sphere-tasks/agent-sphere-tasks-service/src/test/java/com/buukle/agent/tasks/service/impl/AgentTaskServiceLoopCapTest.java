package com.buukle.agent.tasks.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.mcp.TaskMcpCredentialStore;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.tasks.domain.AgentTask;
import com.buukle.agent.tasks.dtvo.enums.TaskEnum;
import com.buukle.agent.tasks.repository.AgentTaskArtifactMapper;
import com.buukle.agent.tasks.repository.AgentTaskMapper;
import com.buukle.agent.tasks.service.TaskCallbackService;
import com.buukle.agent.tasks.service.TaskContractValidator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the loop-cap task check.
 *
 * <p>Production tasks 362 / 360: the runs were actually COMPLETED and both produced 1300+ characters of
 * schema-conformant structured output (run 1575 was even a complete conclusion with
 * {@code status=empty / stopReason=POOL_EXHAUSTED}), yet were vetoed because of {@code loopCapped=true};
 * the caller received only {@code {"error":"run loop limit reached"}} and everything the model wrote was
 * discarded.
 *
 * <p>Root cause: {@code loopCapped} was also set in SessionRunner's COMPLETE branch, misjudging "finished
 * normally in the last round" as "truncated into a partial result". The COMPLETE branch no longer sets it,
 * so the guard only applies to real truncation — but on real truncation it <b>must still</b> carry the
 * run's output back, otherwise the caller still cannot see what happened.
 */
@ExtendWith(MockitoExtension.class)
class AgentTaskServiceLoopCapTest {

    private static final Long TASK_ID = 362L;
    private static final Long RUN_ID = 1575L;

    @Mock
    private AgentTaskMapper taskMapper;
    @Mock
    private AgentTaskArtifactMapper artifactMapper;
    @Mock
    private AgentRuntimeProperties runtimeProperties;
    @Mock
    private InstanceSpi instanceSpi;
    @Mock
    private SessionSpi sessionSpi;
    @Mock
    private RunSpi runSpi;
    @Mock
    private ChatRuntimeService chatRuntimeService;
    @Mock
    private TaskCallbackService taskCallbackService;
    @Mock
    private TaskContractValidator contractValidator;
    @Mock
    private AgentLlmInteractionRecordSpi llmInteractionRecordSpi;
    @Mock
    private AgentToolCallRecordSpi toolCallRecordSpi;
    @Mock
    private TaskMcpCredentialStore taskMcpCredentialStore;

    @InjectMocks
    private AgentTaskServiceImpl service;

    @BeforeAll
    static void initMybatisLambdaCache() {
        // LambdaUpdateWrapper resolves column names via TableInfo; a pure Mockito environment has no MyBatis
        // startup, so without pre-warming it throws "can not find lambda cache".
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "fixture"), AgentTask.class);
    }

    private static AgentTask runningTask() {
        AgentTask t = new AgentTask();
        t.setId(TASK_ID);
        t.setStatus(TaskEnum.STATUS_RUNNING);
        t.setPollPhase("execute");
        t.setRunId(RUN_ID);
        t.setSessionId(674L);
        t.setExpectedOutputJson("{\"required\":[\"candidates\",\"status\"]}");
        return t;
    }

    private static RunVO completedRun(boolean loopCapped) {
        RunVO r = new RunVO();
        r.setId(RUN_ID);
        r.setSessionId(674L);
        r.setStatus("COMPLETED");
        r.setLoopCount(127);
        r.setLoopCapped(loopCapped);
        r.setAssistantReply("{\"candidates\":[],\"status\":\"empty\",\"stopReason\":\"POOL_EXHAUSTED\"}");
        return r;
    }

    /** invoke handleCompletedPhase(AgentTask, String, Long, RunVO) */
    private boolean invokeHandleCompletedPhase(AgentTask task, String phase, Long runId, RunVO run)
            throws Exception {
        Method m = AgentTaskServiceImpl.class.getDeclaredMethod(
                "handleCompletedPhase", AgentTask.class, String.class, Long.class, RunVO.class);
        m.setAccessible(true);
        return (boolean) m.invoke(service, task, phase, runId, run);
    }

    @Test
    void loopCappedRun_failsTaskButCarriesRunOutputAndRemark() throws Exception {
        AgentTask task = runningTask();
        RunVO run = completedRun(true);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);

        assertTrue(invokeHandleCompletedPhase(task, "execute", RUN_ID, run));

        // The terminal snapshot is sent to the caller via the callback; assert what it receives here
        ArgumentCaptor<AgentTask> cap = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskMapper).update(any(), any());
        verify(taskCallbackService).notifyTerminal(cap.capture());
        AgentTask notified = cap.getValue();
        assertEquals(TaskEnum.STATUS_FAILED, notified.getStatus());
        assertNotNull(notified.getResultJson());
        assertTrue(notified.getResultJson().contains("POOL_EXHAUSTED"),
                "loop cap 失败也必须带上 run 的实际产出，否则业务方看不到模型干了什么。实际="
                        + notified.getResultJson());
        assertNotNull(notified.getRemark(), "remark 必须有人话原因，回调里此前一直是空串");
        assertTrue(notified.getRemark().length() > 0);
    }

    @Test
    void loopCappedRun_doesNotStartExtraction() throws Exception {
        AgentTask task = runningTask();
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);

        invokeHandleCompletedPhase(task, "execute", RUN_ID, completedRun(true));

        // Real truncation still does not enter structured extraction (that is the point of the guard) — no extract round is started
        verify(chatRuntimeService, never()).chat(anyLong(), any());
        verify(taskMapper, never()).updateById(any(AgentTask.class));
    }

    @Test
    void notLoopCappedRun_proceedsToExtraction() throws Exception {
        // The key branch after the change: when the model delivers an answer in the last round (loopCapped=false),
        // it must proceed to structured extraction normally instead of being vetoed
        AgentTask task = runningTask();
        // fireFollowUpRun reads runner.taskMaxLoopCount; a mock returning null by default would NPE and exit early
        when(runtimeProperties.getRunner()).thenReturn(new AgentRuntimeProperties.RunnerConfig());
        ChatMessageResponseVO resp = new ChatMessageResponseVO();
        resp.setRunId(999L);
        when(chatRuntimeService.chat(anyLong(), any())).thenReturn(resp);

        invokeHandleCompletedPhase(task, "execute", RUN_ID, completedRun(false));

        verify(chatRuntimeService).chat(anyLong(), any());
        ArgumentCaptor<AgentTask> cap = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskMapper).updateById(cap.capture());
        assertEquals("extract", cap.getValue().getPollPhase(),
                "正常完成的 run 应推进到 extract 阶段做结构化整理");
    }
}
