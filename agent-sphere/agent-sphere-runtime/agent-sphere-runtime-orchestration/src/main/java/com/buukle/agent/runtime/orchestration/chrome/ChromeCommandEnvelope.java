package com.buukle.agent.runtime.orchestration.chrome;

import com.buukle.agent.common.chrome.ChromeCommandDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Envelope for user-level browser command delivery (payload of the Redis event bus topic `runtime.chrome.command`). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChromeCommandEnvelope {
    private String username;
    private ChromeCommandDTO command;
}
