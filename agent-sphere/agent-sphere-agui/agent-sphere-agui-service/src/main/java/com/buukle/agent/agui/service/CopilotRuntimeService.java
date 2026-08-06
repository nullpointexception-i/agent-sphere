package com.buukle.agent.agui.service;

import com.buukle.agent.agui.dtvo.AguiMessageVO;
import com.buukle.agent.agui.dtvo.AguiResumeEntryVO;
import com.buukle.agent.agui.dtvo.AguiRunInputVO;
import com.buukle.agent.agui.dtvo.CopilotAgentDefinitionVO;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.ClarificationSpi;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static com.buukle.agent.agui.service.AguiConstants.CAPABILITY_CHAT;
import static com.buukle.agent.agui.service.AguiConstants.DEFAULT_AGENT_DESCRIPTION;
import static com.buukle.agent.agui.service.AguiConstants.DELIVERY_COPILOT;

@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotRuntimeService {

    private static final int TITLE_MAX_LEN = 50;
    private static final String ROLE_USER = "user";

    private final InstanceSpi instanceSpi;
    private final SessionSpi sessionSpi;
    private final ClarificationSpi clarificationSpi;
    private final ChatRuntimeService chatRuntimeService;
    private final AguiStreamManager streamManager;
    private final ApplicationEventPublisher eventPublisher;

    public CopilotAgentDefinitionVO getAgentDefinition(Long agentId) {
        InstanceVO instance = requireInstance(agentId);
        CopilotAgentDefinitionVO vo = new CopilotAgentDefinitionVO();
        vo.setId(instance.getId());
        vo.setName(instance.getName());
        vo.setDescription(instance.getDescription() == null ? DEFAULT_AGENT_DESCRIPTION : instance.getDescription());
        vo.setCapabilities(List.of(CAPABILITY_CHAT));
        return vo;
    }

    /**
     * AG-UI run：先登记 SSE emitter（保证不丢事件），再异步触发 {@link ChatRuntimeService#chat}，
     * 返回的 emitter 作为 text/event-stream 直接写回客户端。
     */
    public SseEmitter run(Long agentId, AguiRunInputVO input) {
        InstanceVO instance = requireInstance(agentId);
        if (input.getResume() != null && !input.getResume().isEmpty()) {
            return runResume(instance, input);
        }
        String message = lastUserMessage(input.getMessages());
        if (!StringUtils.hasText(message)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "messages must contain a user message");
        }

        Long sessionId = resolveSessionId(agentId, input.getThreadId(), message);

        SendMessageDTO sendMessageDTO = new SendMessageDTO();
        sendMessageDTO.setMessage(message);
        sendMessageDTO.setDelivery(DELIVERY_COPILOT);
        RunVO run = chatRuntimeService.createRun(sessionId, sendMessageDTO);

        SseEmitter emitter = streamManager.register(sessionId, run.getId());
        chatRuntimeService.startRun(run, sessionId, sendMessageDTO, false);
        return emitter;
    }

    /**
     * AG-UI resume：澄清（interrupt）应答后由客户端再次调用 run 时携带 {@code resume}。
     * 标记 pending 澄清为已应答，并以澄清响应 fork 一个新 run 续跑（isClarificationResume=true）。
     */
    private SseEmitter runResume(InstanceVO instance, AguiRunInputVO input) {
        AguiResumeEntryVO entry = input.getResume().get(0);
        Long sessionId = resolveSessionId(instance.getId(), input.getThreadId(), "");
        String clarificationId = entry.getInterruptId();

        // 取消澄清：标记为已忽略，返回一个立即收尾的流（RUN_STARTED + RUN_FINISHED），
        // 让客户端 pendingInterrupts 清零，不续跑。
        if (AguiConstants.RESUME_STATUS_CANCELLED.equals(entry.getStatus())) {
            clarificationSpi.respondToClarificationByClarificationId(
                    sessionId, clarificationId, AguiConstants.CLARIFICATION_RESPONSE_DISMISSED);
            SseEmitter emitter = streamManager.register(sessionId);
            eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.PENDING,
                    new RuntimeEventDataVO().setSessionId(sessionId)));
            eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.COMPLETED,
                    new RuntimeEventDataVO().setSessionId(sessionId)));
            return emitter;
        }

        String response = entry.getPayload() == null ? "" : String.valueOf(entry.getPayload());
        boolean marked = clarificationSpi.respondToClarificationByClarificationId(sessionId, clarificationId, response);
        if (!marked) {
            log.warn("Resume: no pending clarification found for sessionId={}, clarificationId={}",
                    sessionId, clarificationId);
        }

        SendMessageDTO sendMessageDTO = new SendMessageDTO();
        sendMessageDTO.setMessage(ChatClarification.CLARIFICATION_RESUME_PREFIX + response);
        sendMessageDTO.setDelivery(DELIVERY_COPILOT);
        RunVO run = chatRuntimeService.createRun(sessionId, sendMessageDTO);

        SseEmitter emitter = streamManager.register(sessionId, run.getId());
        chatRuntimeService.startRun(run, sessionId, sendMessageDTO, true);
        return emitter;
    }

    public SseEmitter connect(Long agentId, Long sessionId) {
        requireInstance(agentId);
        return streamManager.register(sessionId);
    }

    public void stop(Long agentId, Long sessionId, Long runId) {
        requireInstance(agentId);
        chatRuntimeService.stopRun(sessionId, runId);
    }

    private Long resolveSessionId(Long agentId, String threadId, String message) {
        if (StringUtils.hasText(threadId)) {
            try {
                return Long.parseLong(threadId);
            } catch (NumberFormatException e) {
                log.warn("Invalid AG-UI threadId={} for agent={}, creating a new session", threadId, agentId);
            }
        }
        CreateSessionDTO create = new CreateSessionDTO();
        create.setAgentInstanceId(agentId);
        create.setTitle(deriveTitle(message));
        SessionVO session = sessionSpi.createSession(create);
        return session.getId();
    }

    private static String lastUserMessage(List<AguiMessageVO> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            AguiMessageVO message = messages.get(i);
            if (message != null && ROLE_USER.equals(message.getRole())) {
                return message.getContent();
            }
        }
        return null;
    }

    private InstanceVO requireInstance(Long agentId) {
        InstanceVO instance = instanceSpi.getInstance(agentId);
        if (instance == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!AuthContext.isSuperAdmin() && !AuthContext.getUsername().equals(instance.getCreatedBy())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        return instance;
    }

    private static String deriveTitle(String message) {
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() > TITLE_MAX_LEN ? compact.substring(0, TITLE_MAX_LEN) : compact;
    }
}
