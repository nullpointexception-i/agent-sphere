package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class SessionToolStatsVO implements Serializable {
    private long sessionToolCallCount;
    private Long currentRunId;
    private long currentRunToolCallCount;
    private Long latestRunId;
    private long latestRunToolCallCount;
    private List<ToolApprovalGrantVO> authorizedTools;
}
