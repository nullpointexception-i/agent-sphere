package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.runtime.kernel.port.SubRunExecutionContext;
import com.buukle.agent.runtime.kernel.port.SubRunPolicy.PreparedSubRun;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.skill.SkillSubRunPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillSubRunPolicyTest {

    private final SkillSubRunPolicy policy = new SkillSubRunPolicy(new AgentRuntimeProperties());

    private RuntimeTool tool(long id) {
        return RuntimeTool.builder()
                .capabilityType("skill")
                .capabilityId(id)
                .llmToolName("skill_" + id)
                .toolRef("skill:" + id)
                .displayName("t" + id)
                .execBinding(Map.of())
                .build();
    }

    private SubRunExecutionContext root() {
        return SubRunExecutionContext.root(1L, 2L, null);
    }

    @Test
    void prepare_depthExceededWhenBeyondMax() {
        SubRunExecutionContext parent = root().child(3, List.of(7L), null, null);
        PreparedSubRun prep = policy.prepare(tool(5L), 5L, parent);
        assertFalse(prep.ready());
        assertTrue(prep.error().contains("nested depth"));
    }

    @Test
    void prepare_recursiveWhenToolIdInStack() {
        SubRunExecutionContext parent = root().child(1, List.of(5L), null, null);
        PreparedSubRun prep = policy.prepare(tool(5L), 5L, parent);
        assertFalse(prep.ready());
        assertTrue(prep.error().contains("recursive call"));
    }

    @Test
    void prepare_buildsChildContextOnSuccess() {
        PreparedSubRun prep = policy.prepare(tool(5L), 5L, root());
        assertTrue(prep.ready());
        assertNotNull(prep.childCtx());
        assertEquals(1, prep.depth());
        assertEquals(List.of(5L), prep.childCtx().getSkillStack());
        assertEquals(1, prep.childCtx().getSkillDepth());
    }
}