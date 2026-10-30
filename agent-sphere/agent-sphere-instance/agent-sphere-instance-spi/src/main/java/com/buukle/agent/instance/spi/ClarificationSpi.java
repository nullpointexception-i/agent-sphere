package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.ClarificationVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface ClarificationSpi {
    Long createPending(Long sessionId, Long runId, Long messageId, String title, String type,
                       boolean multiple, String optionsJson, String description, String clarificationId);

    /** L2 tool approval: one pending clarification carrying the batch of tool calls. */
    Long createToolApprovalPending(Long sessionId, Long runId, String title, String optionsJson,
                                   String toolCallsJson, String clarificationId);

    Map<Long, List<ClarificationVO>> mapByRunIdList(Collection<Long> runIds);

    /**
     * 按 sessionId + clarificationId 把待应答的澄清标记为已应答（AG-UI resume 路径使用）。
     *
     * @return 是否找到并更新了 pending 记录
     */
    boolean respondToClarificationByClarificationId(Long sessionId, String clarificationId, String response);
}
