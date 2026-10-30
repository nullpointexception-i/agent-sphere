package com.buukle.agent.runtime.kernel.config;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.config.AgentRuntimeProperties.RunnerConfig.CompactionTier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LlmContextValidatorTest {

    @Test
    void dumpConfigHandlesCollectionFieldsWithoutJdkReflection() throws Exception {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        CompactionTier tier = new CompactionTier();
        tier.setMaxInputTokens(64_000L);
        tier.setTriggerTokens(50_000L);
        tier.setTargetTokens(20_000L);
        tier.setChatMaxOutputTokens(8_192L);
        properties.getRunner().getCompaction().setTiers(List.of(tier));

        LlmContextValidator validator = new LlmContextValidator(properties);
        Method dump = LlmContextValidator.class.getDeclaredMethod(
                "dumpConfig", String.class, Object.class);
        dump.setAccessible(true);

        assertDoesNotThrow(() -> dump.invoke(validator, "", properties));
    }
}
