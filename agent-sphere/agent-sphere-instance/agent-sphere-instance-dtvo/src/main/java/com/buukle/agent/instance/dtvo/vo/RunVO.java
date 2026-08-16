package com.buukle.agent.instance.dtvo.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
    /** 累积的模型推理/thinking 文本（终态 run 落库，历史回看渲染用） */
    private String reasoning;
    private String intentClassification;
    private String status;
    /** 命中循环次数上限被强收口（任务守卫据此判失败） */
    private Boolean loopCapped;
    private String delivery;
    private String createdBy;
    private String createdAt;
    /** 该 run 是否为澄清应答（userMessage 携带澄清恢复前缀），展示层据此跳过独立用户气泡 */
    private boolean clarificationResponse;
    private List<ClarificationVO> clarifications;
    /** 瞬态标记：为 true 时该 run 的 kernel context 不注册 ask_clarification 工具（不落库，不对外序列化） */
    @JsonIgnore
    private Boolean noClarification;
}
