package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class RunVO implements Serializable {
    private Long id;
    private Long sessionId;
    private String type;
    private String userMessage;
    private String assistantReply;
    private String intentClassification;
    private String status;
    private String delivery;
    private String createdBy;
    private String createdAt;
    /** 该 run 是否为澄清应答（userMessage 携带澄清恢复前缀），展示层据此跳过独立用户气泡 */
    private boolean clarificationResponse;
    private List<ClarificationVO> clarifications;
}
