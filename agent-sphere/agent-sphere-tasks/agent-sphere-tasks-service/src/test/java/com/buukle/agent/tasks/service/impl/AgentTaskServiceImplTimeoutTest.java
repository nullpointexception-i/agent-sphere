package com.buukle.agent.tasks.service.impl;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.mcp.TaskMcpCredentialStore;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.sso.spi.SsoIdentitySpi;
import com.buukle.agent.tasks.repository.AgentTaskArtifactMapper;
import com.buukle.agent.tasks.repository.AgentTaskMapper;
import com.buukle.agent.tasks.service.TaskCallbackService;
import com.buukle.agent.tasks.service.TaskContractValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Value semantics for task timeouts: the caller-specified value wins; only when unspecified does it fall
 * back to the AS-side configured default.
 *
 * <p>This boundary matters — the value determines both the task lifetime cap and the MCP credential TTL
 * the caller issues. If the task-level value were mistakenly ignored in favor of the default, long tasks
 * would keep running past the credential TTL and every MCP call midway would be rejected (surfacing as a
 * "missing credential" error far removed from the real root cause).
 */
@ExtendWith(MockitoExtension.class)
class AgentTaskServiceImplTimeoutTest {

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
    private SsoIdentitySpi ssoIdentitySpi;
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

    private void setDefaultMaxPollSeconds(long value) throws Exception {
        Field field = AgentTaskServiceImpl.class.getDeclaredField("defaultMaxPollSeconds");
        field.setAccessible(true);
        field.setLong(service, value);
    }

    private void setMinTimeoutSeconds(long value) throws Exception {
        Field field = AgentTaskServiceImpl.class.getDeclaredField("minTimeoutSeconds");
        field.setAccessible(true);
        field.setLong(service, value);
    }

    @Test
    void fallsBackToConfiguredDefaultWhenTaskHasNoTimeout() throws Exception {
        setDefaultMaxPollSeconds(3600L);
        setMinTimeoutSeconds(0L);

        assertEquals(3600L, service.effectiveTimeoutSeconds(null));
    }

    @Test
    void taskLevelTimeoutOverridesConfiguredDefault() throws Exception {
        setDefaultMaxPollSeconds(3600L);
        setMinTimeoutSeconds(0L);

        assertEquals(120L, service.effectiveTimeoutSeconds(120));
    }

    // ---------- Timeout lower bound (min-timeout-seconds) ----------

    @Test
    void businessValueBelowFloorIsRaisedToFloor() throws Exception {
        // 155 of 158 production tasks had the caller explicitly pass 3600, already persisted in the
        // task_timeout_seconds column. Changing max-poll-seconds has no effect on them; this lower bound is
        // the only thing that can raise the actual timeout.
        setDefaultMaxPollSeconds(3600L);
        setMinTimeoutSeconds(10800L);

        assertEquals(10800L, service.effectiveTimeoutSeconds(3600));
    }

    @Test
    void businessValueAboveFloorIsKept() throws Exception {
        // The lower bound only compensates; it does not lower a larger value the caller deliberately supplied
        setDefaultMaxPollSeconds(3600L);
        setMinTimeoutSeconds(10800L);

        assertEquals(14400L, service.effectiveTimeoutSeconds(14400));
    }

    @Test
    void nullTaskValueAlsoRespectsFloor() throws Exception {
        // When the lower bound exceeds the default, tasks falling back to the default must be raised too
        setDefaultMaxPollSeconds(3600L);
        setMinTimeoutSeconds(10800L);

        assertEquals(10800L, service.effectiveTimeoutSeconds(null));
    }

    @Test
    void floorOfZeroKeepsLegacyBehaviourExactly() throws Exception {
        // Environments without this key must be completely unchanged: whatever the caller gives is used
        setDefaultMaxPollSeconds(3600L);
        setMinTimeoutSeconds(0L);

        assertEquals(120L, service.effectiveTimeoutSeconds(120));
        assertEquals(3600L, service.effectiveTimeoutSeconds(null));
    }
}
