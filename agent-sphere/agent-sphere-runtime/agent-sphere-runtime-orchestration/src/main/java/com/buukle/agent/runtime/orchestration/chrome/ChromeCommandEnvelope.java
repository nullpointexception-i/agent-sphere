package com.buukle.agent.runtime.orchestration.chrome;

import com.buukle.agent.common.chrome.ChromeCommandDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 用户级浏览器指令投递信封（Redis 事件总线 `runtime.chrome.command` 载荷）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChromeCommandEnvelope {
    private String username;
    private ChromeCommandDTO command;
}
