package com.buukle.agent.superagent.service;

import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.service.impl.AgentRouterScheduler;
import com.buukle.agent.superagent.spi.AgentRouterRunPort;
import com.buukle.agent.util.json.JsonUtils;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The avatar delivery must tell the agent who it belongs to and who is talking,
 * so a mention of someone else's avatar is not answered as if the owner spoke.
 */
class AgentRouterSchedulerIdentityTest {

    @Test
    void identityBlockNamesOwnerAndOtherSpeaker() {
        AgentRouterScheduler scheduler = scheduler();
        String block = ReflectionTestUtils.invokeMethod(scheduler, "identityBlock",
                agent(), event(9L, "Celine"));

        assertTrue(block.contains("Daily Assistant"));
        assertTrue(block.contains("bole@elvin"));
        assertTrue(block.contains("Celine"));
        assertTrue(block.contains("not your owner"));
        assertTrue(block.contains("Group members"));
    }

    @Test
    void identityBlockMarksOwnerAsSpeaker() {
        AgentRouterScheduler scheduler = scheduler();
        String block = ReflectionTestUtils.invokeMethod(scheduler, "identityBlock",
                agent(), event(7L, "bole@elvin"));

        assertTrue(block.contains("This message is from bole@elvin"));
        assertFalse(block.contains("not your owner"));
    }

    private static AgentSuperAgent agent() {
        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setId(5L);
        agent.setName("Daily Assistant");
        agent.setOwnerUserId(7L);
        return agent;
    }

    private static AgentEvent event(Long speakerId, String speakerName) {
        Map<String, Object> meta = Map.of(
                "conversationType", "GROUP",
                "memberUserIds", List.of(7L, 8L),
                "speakerId", speakerId,
                "speakerName", speakerName);
        Map<String, Object> payload = Map.of(
                "text", "hello",
                "rawMeta", JsonUtils.toJson(meta));
        AgentEvent event = new AgentEvent();
        event.setId(1L);
        event.setActorId(speakerId);
        event.setPayload(JsonUtils.toJson(payload));
        return event;
    }

    @SuppressWarnings("unchecked")
    private static AgentRouterScheduler scheduler() {
        UserSpi userSpi = mock(UserSpi.class);
        UserIdentityVO owner = new UserIdentityVO();
        owner.setId(7L);
        owner.setDisplayName("Elvin");
        owner.setSsoProviderCode("bole");
        owner.setSsoSubject("elvin");
        UserIdentityVO speaker = new UserIdentityVO();
        speaker.setId(9L);
        speaker.setDisplayName("Celine");
        when(userSpi.identityMap(any())).thenReturn(Map.of(7L, owner, 9L, speaker));
        ObjectProvider<UserSpi> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(userSpi);
        return new AgentRouterScheduler(
                mock(AgentEventDeliveryMapper.class),
                mock(AgentEventMapper.class),
                mock(AgentSuperAgentMapper.class),
                mock(AgentThreadMapper.class),
                mock(SessionSpi.class),
                mock(com.buukle.agent.instance.spi.RunSpi.class),
                mock(com.buukle.agent.superagent.repository.AgentRouterForkMapper.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                provider,
                mock(AgentMetrics.class),
                mock(RedissonClient.class),
                mock(Executor.class));
    }
}
