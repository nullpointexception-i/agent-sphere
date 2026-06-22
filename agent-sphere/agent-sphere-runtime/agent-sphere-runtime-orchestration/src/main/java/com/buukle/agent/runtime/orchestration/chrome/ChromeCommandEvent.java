package com.buukle.agent.runtime.orchestration.chrome;

import com.buukle.agent.common.chrome.ChromeCommandDTO;
import com.buukle.agent.runtime.kernel.port.vo.ChromeCommandEventType;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import lombok.Getter;

@Getter
public class ChromeCommandEvent extends RuntimeEventVO {
    private final ChromeCommandDTO command;

    public ChromeCommandEvent(ChromeCommandDTO cmd) {
        super(ChromeCommandEventType.SEND_COMMAND,
                new RuntimeEventDataVO().setSessionId(cmd.getSessionId()));
        this.command = cmd;
    }
}
