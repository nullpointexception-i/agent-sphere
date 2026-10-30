package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class SharedChatVO implements Serializable {
    private String title;
    private List<SharedRunVO> runs;
    private boolean hasMore;
    private Long nextBeforeRunId;

    @Data
    public static class SharedRunVO implements Serializable {
        private Long runId;
        private String userMessage;
        private String assistantReply;
        private String createdAt;
    }
}
