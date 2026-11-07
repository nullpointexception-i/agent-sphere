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
 * Event bus → local SSE delivery relay.
 * Every replica (including the publisher) subscribes: session events go to {@link SseManager#sendBySession};
 * browser commands are **device-targeted** to a single target connection. One delivery path, no duplicates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseEventRelay {

    /** No online browser extension. */
    public static final String ERROR_BROWSER_NOT_CONNECTED = "browser_not_connected";
    /** Multiple devices online but the user has not selected an execution device yet. */
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
     * Browser command: pick the route first, then deliver only to the target connection.
     *
     * <p>No more broadcast — broadcasting makes multiple browsers **execute concurrently** and takes the first result, which is exactly the root cause of "the command ran in another browser".
     * When routing fails, this replica completes the pending future directly (only the replica holding the future acts), so the tool side immediately gets an explicit error.
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
                            "Browser extension is not connected: no online extension. Open the browser, log in to the extension, and retry.",
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
        StringBuilder sb = new StringBuilder("There are ")
                .append(selection.live().size())
                .append(" browsers online. Choose \"Run on this device\" in the browser extension prompt, then retry. Online devices: ");
        for (int i = 0; i < selection.live().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            UserConnectionRegistry.ConnectionView view = selection.live().get(i);
            sb.append(view.deviceName() != null && !view.deviceName().isBlank()
                    ? view.deviceName() : "Unknown device");
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
