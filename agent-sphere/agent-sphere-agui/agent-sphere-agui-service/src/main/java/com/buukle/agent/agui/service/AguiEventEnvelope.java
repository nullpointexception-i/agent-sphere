package com.buukle.agent.agui.service;

import com.buukle.agent.agui.dtvo.AguiEventVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AG-UI 事件投递信封（Redis 事件总线 `runtime.agui` 载荷）。
 * 翻译在**执行副本**完成（累积状态不可跨副本重建），经总线广播后各副本 relay 投本地 emitter；
 * {@code terminal} 标记 run 结束，relay 据此调用 complete。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AguiEventEnvelope {
    private Long sessionId;
    private Long runId;
    private boolean terminal;
    private AguiEventVO event;
}
