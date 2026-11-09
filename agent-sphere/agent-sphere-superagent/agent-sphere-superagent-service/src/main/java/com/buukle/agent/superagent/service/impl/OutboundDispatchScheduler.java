package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.context.TenantScopeHolder;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.channel.spi.ChannelAdapter;
import com.buukle.agent.channel.spi.OutboundFormat;
import com.buukle.agent.channel.spi.OutboundMessage;
import com.buukle.agent.channel.spi.SendResult;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.superagent.domain.AgentOutboundMessage;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.dtvo.enums.AgentOutboundMessageStatus;
import com.buukle.agent.superagent.dtvo.vo.AgentOutboundPayloadVO;
import com.buukle.agent.superagent.repository.AgentChannelBindingMapper;
import com.buukle.agent.superagent.repository.AgentOutboundMessageMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.service.event.AgentOutboundCreatedEvent;
import com.buukle.agent.util.json.JsonUtils;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outbox sender: reliably delivers Agent replies to the target channel.
 *
 * <p>Persist first, then send; failed sends retry with exponential backoff; duplicate messages are
 * deduplicated by idempotency_key. The target channel is matched by {@link ChannelAdapter#type()};
 * currently only IM_BUILTIN exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundDispatchScheduler {

    /** {@code agent_outbound_message.remark} is VARCHAR(512); keep the failure write bounded. */
    private static final int MAX_REMARK = 500;

    private static final String CHANNEL_IM = "IM_BUILTIN";

    private final AgentOutboundMessageMapper outboundMapper;
    private final AgentSuperAgentMapper superAgentMapper;
    private final AgentThreadMapper threadMapper;
    private final AgentChannelBindingMapper channelBindingMapper;
    private final UserSpi userSpi;
    private final List<ChannelAdapter> adapters;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    @Qualifier("runtimeAsyncExecutor")
    private final Executor wakeExecutor;

    @Value("${buukle.agent.super-agent.enabled:false}")
    private boolean superAgentEnabled;
    @Value("${buukle.agent.super-agent.outbound.enabled:false}")
    private boolean enabled;
    @Value("${buukle.agent.super-agent.outbound.batch-size:20}")
    private int batchSize;
    @Value("${buukle.agent.super-agent.outbound.max-attempts:8}")
    private int maxAttempts;
    @Value("${buukle.agent.super-agent.outbound.retry-base-ms:2000}")
    private long retryBaseMs;
    @Value("${buukle.agent.super-agent.outbound.lease-seconds:900}")
    private long leaseSeconds;

    /** Event-driven wake: committed outbound rows are sent within milliseconds; polling is a fallback. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOutboundCreated(AgentOutboundCreatedEvent event) {
        wakeAsync();
    }

    @Scheduled(fixedDelayString = "${buukle.agent.super-agent.outbound.interval-ms:3000}")
    public void run() {
        drain(true);
    }

    private final AtomicBoolean waking = new AtomicBoolean(false);
    private final AtomicBoolean wakeAgain = new AtomicBoolean(false);

    private void wakeAsync() {
        if (!superAgentEnabled || !enabled) {
            return;
        }
        if (wakeExecutor == null) {
            return;
        }
        if (!waking.compareAndSet(false, true)) {
            wakeAgain.set(true);
            return;
        }
        try {
            wakeExecutor.execute(() -> {
                try {
                    do {
                        wakeAgain.set(false);
                        drain(false);
                    } while (wakeAgain.get());
                } finally {
                    waking.set(false);
                    if (wakeAgain.get()) {
                        wakeAsync();
                    }
                }
            });
        } catch (Exception e) {
            waking.set(false);
            log.warn("Outbound wake rejected: {}", e.getMessage());
        }
    }

    private void drain(boolean resetStuck) {
        if (!superAgentEnabled || !enabled) {
            return;
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        AuthContext.clear();
        if (resetStuck) {
            outboundMapper.resetStuckSending();
        }
        List<AgentOutboundMessage> messages = outboundMapper.selectPending(
                LocalDateTime.now(), Math.max(1, batchSize));
        for (AgentOutboundMessage message : messages) {
            String token = UUID.randomUUID().toString();
            LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(Math.max(30, leaseSeconds));
            if (outboundMapper.claim(message.getId(), token, leaseUntil) == 0) {
                continue;
            }
            message.setLeaseToken(token);
            ChannelAdapter adapter = findAdapter(message);
            if (adapter == null) {
                log.warn("No ChannelAdapter for outbound {}; skipped", message.getId());
                fail(message, "channel adapter not found", false, token);
                continue;
            }
            send(adapter, message, token);
        }
    }

    private void send(ChannelAdapter adapter, AgentOutboundMessage row, String token) {
        OutboundMessage message = toMessage(row);
        if (message == null) {
            fail(row, "invalid payload", false, token);
            return;
        }
        applySendIdentity(row);
        try {
            SendResult result = message.targetMessageId() != null
                    ? adapter.update(message) : adapter.send(message);
            if (result != null && result.success()) {
                int updated = outboundMapper.update(null, Wrappers.<AgentOutboundMessage>lambdaUpdate()
                        .eq(AgentOutboundMessage::getId, row.getId())
                        .eq(AgentOutboundMessage::getStatus, AgentOutboundMessageStatus.SENDING)
                        .eq(AgentOutboundMessage::getLeaseToken, token)
                        .set(AgentOutboundMessage::getStatus, AgentOutboundMessageStatus.SENT)
                        .set(AgentOutboundMessage::getExternalMessageId, result.externalMessageId())
                        .set(AgentOutboundMessage::getRemark, null)
                        .set(AgentOutboundMessage::getLeaseToken, null)
                        .set(AgentOutboundMessage::getLeaseUntil, null));
                if (updated == 0) {
                    log.warn("Outbound lease lost before SENT: id={}, token={}", row.getId(), token);
                    increment("superagent.outbox.lease_conflict");
                    return;
                }
                increment("superagent.outbox.sent");
                return;
            }
            fail(row, result == null ? "send returned null" : result.error(), true, token);
        } catch (Exception e) {
            log.warn("Outbound send failed: id={}, error={}", row.getId(), e.getMessage());
            fail(row, e.getMessage(), true, token);
        } finally {
            TenantUtil.stop();
            TenantScopeHolder.clear();
            TenantContext.clear();
            AuthContext.clear();
        }
    }

    private void applySendIdentity(AgentOutboundMessage row) {
        // Never inherit a caller's tenant: look the agent up without scope, then
        // pin the delivery to the row's tenant (falling back to the agent's).
        TenantContext.clear();
        TenantScopeHolder.clear();
        AgentSuperAgent agent = null;
        try {
            agent = superAgentMapper.selectById(row.getSuperAgentId());
        } catch (Exception e) {
            log.debug("Outbound agent lookup failed: id={}", row.getSuperAgentId());
        }
        Long tenantId = row.getTenantId();
        if (tenantId == null || tenantId <= 0) {
            // Rows written without a tenant used to fail with "conversation not found".
            tenantId = agent == null ? null : agent.getTenantId();
        }
        TenantContext.set(tenantId);
        if (agent != null && agent.getOwnerUserId() != null) {
            try {
                UserVO owner = userSpi.getByUserId(agent.getOwnerUserId());
                if (owner != null && owner.getUsername() != null) {
                    TenantUtil.start(owner.getUsername());
                    AuthContext.setUserId(owner.getId());
                    AuthContext.setUsername(owner.getUsername());
                }
            } catch (Exception e) {
                log.debug("Outbound owner lookup failed: userId={}", agent.getOwnerUserId());
            }
        }
    }

    private void fail(AgentOutboundMessage row, String reason, boolean retryable, String token) {
        int attempts = (row.getAttempts() == null ? 0 : row.getAttempts()) + 1;
        String remark = reason == null || reason.length() <= MAX_REMARK
                ? reason : reason.substring(0, MAX_REMARK);
        String status;
        LocalDateTime nextRetryAt = null;
        if (!retryable || attempts >= maxAttempts) {
            status = AgentOutboundMessageStatus.FAILED;
            increment("superagent.outbox.failed");
        } else {
            status = AgentOutboundMessageStatus.RETRY;
            long delay = retryBaseMs * (1L << Math.min(attempts, 6));
            nextRetryAt = LocalDateTime.now().plusNanos(delay * 1_000_000L);
        }
        int updated = outboundMapper.update(null, Wrappers.<AgentOutboundMessage>lambdaUpdate()
                .eq(AgentOutboundMessage::getId, row.getId())
                .eq(AgentOutboundMessage::getStatus, AgentOutboundMessageStatus.SENDING)
                .eq(AgentOutboundMessage::getLeaseToken, token)
                .set(AgentOutboundMessage::getStatus, status)
                .set(AgentOutboundMessage::getAttempts, attempts)
                .set(AgentOutboundMessage::getNextRetryAt, nextRetryAt)
                .set(AgentOutboundMessage::getRemark, remark)
                .set(AgentOutboundMessage::getLeaseToken, null)
                .set(AgentOutboundMessage::getLeaseUntil, null));
        if (updated == 0) {
            log.warn("Outbound lease lost before terminal update: id={}, token={}", row.getId(), token);
            increment("superagent.outbox.lease_conflict");
        }
    }

    private void increment(String name) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter(name).increment();
        }
    }

    private ChannelAdapter findAdapter(AgentOutboundMessage row) {
        if (adapters == null) {
            return null;
        }
        String channelType = CHANNEL_IM;
        if (row.getThreadId() != null) {
            AgentThread thread = threadMapper.selectById(row.getThreadId());
            if (thread != null && thread.getChannelBindingId() != null) {
                var binding = channelBindingMapper.selectById(thread.getChannelBindingId());
                if (binding != null && binding.getChannelType() != null) {
                    channelType = binding.getChannelType();
                }
            }
        }
        for (ChannelAdapter adapter : adapters) {
            if (channelType.equals(adapter.type())) {
                return adapter;
            }
        }
        return null;
    }

    private static OutboundMessage toMessage(AgentOutboundMessage row) {
        try {
            AgentOutboundPayloadVO payload = JsonUtils.parse(row.getPayload(), AgentOutboundPayloadVO.class);
            if (payload == null || payload.getText() == null) {
                return null;
            }
            String format = payload.getFormat() == null || payload.getFormat().isBlank()
                    ? OutboundFormat.TEXT : payload.getFormat();
            return new OutboundMessage(row.getSuperAgentId(), row.getThreadId(),
                    payload.getText(), format, payload.getReplyToId(), row.getIdempotencyKey(),
                    payload.getTargetMessageId());
        } catch (Exception e) {
            return null;
        }
    }
}
