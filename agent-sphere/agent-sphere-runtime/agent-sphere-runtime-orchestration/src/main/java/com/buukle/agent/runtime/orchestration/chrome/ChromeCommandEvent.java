package com.buukle.agent.runtime.orchestration.chrome;

import com.buukle.agent.common.chrome.ChromeCommandDTO;

/**
 * Browser command event. Deliberately a standalone record: the runtime event
 * envelope is now an immutable record and cannot be extended. A dedicated
 * {@code @EventListener} consumes this type.
 */
public record ChromeCommandEvent(ChromeCommandDTO command) {

    public Long sessionId() {
        return command == null ? null : command.getSessionId();
    }
}
