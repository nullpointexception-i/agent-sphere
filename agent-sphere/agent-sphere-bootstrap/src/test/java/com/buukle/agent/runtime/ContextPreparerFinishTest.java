package com.buukle.agent.runtime;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.capability.cli.spi.CapabilityCliSpi;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.capability.skill.spi.CapabilitySkillSpi;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.InstanceCapabilitySpi;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.orchestration.pipeline.ContextPreparer;
import com.buukle.agent.runtime.orchestration.pipeline.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V149 regression guard: a STRICT_FINISH run must always receive the finish tool.
 *
 * <p>Background: worker runs resume from the database, and before V149 the
 * no-clarification flag was not persisted. After an approval/wait resume the flag
 * read back as false, so this condition evaluated false and finish disappeared —
 * leaving a run whose completion contract requires finish with no legal way to end.
 */
class ContextPreparerFinishTest {

    private static final String FINISH_TOOL = "builtin_" + BuiltinToolEnum.FINISH.getId();

    @Test
    void strictFinishRegistersFinishWithoutCapabilitiesOrNoClarification() {
        KernelContext ctx = prepare("STRICT_FINISH");

        assertTrue(ctx.getTools().stream().anyMatch(t -> FINISH_TOOL.equals(t.getLlmToolName())),
                "STRICT_FINISH run must be able to call finish");
    }

    @Test
    void implicitTextRunWithoutCapabilitiesKeepsPureChatBehaviour() {
        KernelContext ctx = prepare("IMPLICIT_TEXT");

        assertFalse(ctx.getTools().stream().anyMatch(t -> FINISH_TOOL.equals(t.getLlmToolName())),
                "plain chat without capabilities should not be pushed into finish");
    }

    private static KernelContext prepare(String completionProtocol) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        InstanceCapabilitySpi capabilitySpi = mock(InstanceCapabilitySpi.class);
        when(capabilitySpi.getCapabilitiesByInstance(anyLong())).thenReturn(List.of());
        CapabilityBuiltinSpi builtin = mock(CapabilityBuiltinSpi.class);
        when(builtin.listAutoIncludeBuiltinTools()).thenReturn(List.of());
        when(builtin.listBuiltinTools()).thenReturn(List.of());

        ContextPreparer preparer = new ContextPreparer(capabilitySpi,
                mock(CapabilityMcpSpi.class), mock(CapabilitySkillSpi.class),
                mock(CapabilityCliSpi.class), builtin, properties);

        RunVO run = new RunVO();
        run.setId(1L);
        run.setSessionId(1L);
        run.setCompletionProtocol(completionProtocol);
        InstanceVO instance = new InstanceVO();
        instance.setId(1L);
        return preparer.prepare(run, ValidationResult.builder().agentInstance(instance).build(), "hi");
    }
}
