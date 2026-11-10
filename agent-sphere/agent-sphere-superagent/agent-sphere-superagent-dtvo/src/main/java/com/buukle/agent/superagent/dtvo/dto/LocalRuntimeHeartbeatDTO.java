package com.buukle.agent.superagent.dtvo.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** Desktop heartbeat; running tasks keep their leases alive. */
@Data
public class LocalRuntimeHeartbeatDTO implements Serializable {

    @Size(max = 32)
    private String appVersion;

    @Size(max = 32)
    private String os;

    @Size(max = 32)
    private String arch;

    private List<Long> runningTaskIds;
}
