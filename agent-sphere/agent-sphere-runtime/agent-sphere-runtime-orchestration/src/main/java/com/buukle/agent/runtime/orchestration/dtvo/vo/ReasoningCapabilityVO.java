package com.buukle.agent.runtime.orchestration.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 会话的思考强度能力视图。
 *
 * <p>不可行档位照常返回（前端展示并警告），不隐藏。
 */
@Data
public class ReasoningCapabilityVO implements Serializable {

    /** 当前会话所走模型是否支持思考强度。 */
    private boolean supported;
    /** 统一模式：levels。 */
    private String mode;
    /** 本次请求实际生效的输出上限（max_tokens）。 */
    private Long effectiveMaxOutputTokens;
    /** 正文保留的 token 数。 */
    private Long answerReserveTokens;
    /** 动态默认档位。 */
    private String defaultLevel;
    /** 当前选中档位；无活动 run 时为 null（发送时按默认）。 */
    private String selectedLevel;
    /** 可选档位（含不可行项，带 feasible 标记）。 */
    private List<Level> levels;
    /** 会话是否有活动 run；运行中不可修改。 */
    private boolean running;
    /** 活动 run id。 */
    private Long runId;

    @Data
    public static class Level implements Serializable {
        private String value;
        /** 该档位建议的最低输出预算。 */
        private long minOutputTokens;
        /** 在当前输出上限下是否可行。 */
        private boolean feasible;
    }
}
