package com.buukle.agent.instance.dtvo.vo;

import com.buukle.agent.instance.domain.vo.RunUsageVO;
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
    /** 聊图片附件引用（[{"fileKey","contentType"}]）：Timeline USER 行回显用 */
    private List<RunAttachment> attachments;
    private String status;
    /** Snapshot of session run mode at creation: WRITE / READ_ONLY. */
    private String runMode;
    /** Snapshot of the instance completion contract for this run. */
    private String completionProtocol;
    /** 命中循环次数上限被强收口（任务守卫据此判失败） */
    private Boolean loopCapped;
    /**
     * 该 run 已消耗的 agent 循环轮次（V82 起持久化）。
     *
     * <p>此前 loopCount 是 SessionRunner#runInternal 的局部变量，每次挂起恢复
     * （澄清 AWAITING_USER / 等待 WAITING）都归零，maxLoopCount 因而只约束「单次调用」，
     * 模型可反复挂起无限续命。落库后恢复时读回，循环预算才真正是 per-run。
     */
    private Integer loopCount;
    /**
     * 本 run 生效的轮次上限（V88 起固化）；null 表示尚未记录，首次进入时解析并写入。
     */
    private Integer loopLimit;
    /**
     * 失败原因（V87 起）。此前 run 失败不留任何原因，只能靠间接特征反推。
     */
    private String errorMessage;
    /** 挂起类型（V115）：WAIT / LLM_RETRY。 */
    private String waitKind;
    /** 当前已发生的 LLM 瞬时失败次数（V115）。 */
    private Integer retryAttempt;
    /** 梯度重试上限（由配置推导；WAITING 且有值时前端显示「k/N」）。 */
    private Integer retryMax;
    /** 下次重试时间（epoch ms，前端倒计时）。 */
    private Long nextRetryAt;
    /** 错误分类 code，前端按它选文案。 */
    private String retryHint;
    /** 触发重试的上游 HTTP 状态码。 */
    private Integer retryHttpStatus;
    /** 错误原文（tooltip 展示）。 */
    private String retryDetail;
    private String delivery;
    private String createdBy;
    private String createdAt;
    /** 该 run 是否为澄清应答（userMessage 携带澄清恢复前缀），展示层据此跳过独立用户气泡 */
    private boolean clarificationResponse;
    private List<ClarificationVO> clarifications;
    /** 瞬态标记：为 true 时该 run 的 kernel context 不注册 ask_clarification 工具（不落库，不对外序列化） */
    @JsonIgnore
    private Boolean noClarification;
    /** 该 run 的 LLM 用量聚合（终态 run 有值；列表回填，单查含）。 */
    private RunUsageVO usageSummary;
}
