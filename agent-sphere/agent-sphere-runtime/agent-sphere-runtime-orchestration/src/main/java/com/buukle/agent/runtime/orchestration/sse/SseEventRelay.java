package com.buukle.agent.runtime.orchestration.sse;

import com.buukle.agent.common.chrome.ChromeCallbackDTO;
import com.buukle.agent.common.chrome.ChromeCommandDTO;
import com.buukle.agent.common.chrome.ChromePendingStore;
import com.buukle.agent.common.chrome.UserConnectionRegistry;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.infrastructure.eventbus.RedisEventBus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.orchestration.chrome.ChromeCommandEnvelope;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 事件总线 → 本地 SSE 投递 relay。
 * 各副本（含发布者自己）订阅：session 事件投 {@link SseManager#sendBySession}，
 * 浏览器指令**按设备定向**投给唯一目标连接。单路径投递，无重复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseEventRelay {

    /** 没有任何在线的浏览器扩展。 */
    public static final String ERROR_BROWSER_NOT_CONNECTED = "browser_not_connected";
    /** 多台在线但用户尚未选择执行设备。 */
    public static final String ERROR_DEVICE_SELECTION_REQUIRED = "device_selection_required";

    private final RedisEventBus eventBus;
    private final SseManager sseManager;
    private final UserConnectionRegistry userConnectionRegistry;

    @PostConstruct
    public void init() {
        subscribe();
    }

    public void subscribe() {
        eventBus.subscribe(DistributedRuntimeConstants.TOPIC_EVENTS, RuntimeEventVO.class, this::onSessionEvent);
        eventBus.subscribe(DistributedRuntimeConstants.TOPIC_CHROME_COMMAND, ChromeCommandEnvelope.class, this::onChromeCommand);
    }

    private void onSessionEvent(RuntimeEventVO event) {
        if (event == null || event.getData() == null || event.getData().getSessionId() == null) {
            return;
        }
        sseManager.sendBySession(event.getData().getSessionId(), event);
    }

    /**
     * 浏览器指令：先选路，再只投给目标连接。
     *
     * <p>不再广播 —— 广播会让多台浏览器**同时执行**，取先返回的结果，正是「命令跑到别的浏览器」的根因。
     * 选路失败时由本副本直接完成 pending future（只有持有 future 的副本生效），工具侧立刻拿到明确错误。
     */
    private void onChromeCommand(ChromeCommandEnvelope envelope) {
        if (envelope == null || envelope.getUsername() == null) {
            return;
        }
        ChromeCommandDTO command = envelope.getCommand();
        if (command == null) {
            return;
        }
        UserConnectionRegistry.Selection selection = userConnectionRegistry.pick(envelope.getUsername());
        switch (selection.state()) {
            case EMPTY -> ChromePendingStore.complete(command.getCommandId(),
                    fail(command.getCommandId(),
                            "浏览器插件未连接：没有在线的扩展。请先打开浏览器，并在插件里登录后再试。",
                            ERROR_BROWSER_NOT_CONNECTED));
            case NEEDS_SELECTION -> ChromePendingStore.complete(command.getCommandId(),
                    fail(command.getCommandId(),
                            selectionRequiredMessage(selection),
                            ERROR_DEVICE_SELECTION_REQUIRED));
            case OK, DEGRADED -> {
                command.setTargetConnectionId(selection.target().connectionId());
                if (selection.state() == UserConnectionRegistry.SelectionState.DEGRADED) {
                    log.info("Browser command degraded: username={} preferred={} -> device={} ({})",
                            envelope.getUsername(), selection.preferredDeviceId(),
                            selection.target().deviceName(), selection.target().deviceId());
                }
                sseManager.sendToConnection(envelope.getUsername(), selection.target().connectionId(), command);
            }
        }
    }

    private static String selectionRequiredMessage(UserConnectionRegistry.Selection selection) {
        StringBuilder sb = new StringBuilder("当前有 ")
                .append(selection.live().size())
                .append(" 台浏览器在线，请先在浏览器扩展的提示里选择「本机执行」后再重试。在线设备：");
        for (int i = 0; i < selection.live().size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            UserConnectionRegistry.ConnectionView view = selection.live().get(i);
            sb.append(view.deviceName() != null && !view.deviceName().isBlank()
                    ? view.deviceName() : "未知设备");
            if (view.source() != null) {
                sb.append("(").append(view.source()).append(")");
            }
        }
        return sb.toString();
    }

    private static ChromeCallbackDTO fail(String commandId, String message, String errorCategory) {
        ChromeCallbackDTO callback = ChromeCallbackDTO.fail(commandId, message);
        callback.setErrorCategory(errorCategory);
        return callback;
    }
}
