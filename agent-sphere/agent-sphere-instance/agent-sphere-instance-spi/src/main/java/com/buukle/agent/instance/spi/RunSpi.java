package com.buukle.agent.instance.spi;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.vo.MessageHistoryVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface RunSpi {
    RunVO createRun(CreateRunDTO dto);

    RunVO getRun(Long id);

    /** 该 session 当前最新活动 run（status ∈ PENDING/RUNNING/AWAITING_USER），无则 null。 */
    RunVO findActiveRun(Long sessionId);

    void updateRun(RunVO run);

    List<RunVO> listRunsBySessionAfterId(Long sessionId, Long afterRunId);

    IPage<RunVO> pageRunsBySession(Long sessionId, String keyword, int page, int size);

    /** 按需批量补拉推理：runId → 已落库推理。仅查 reasoning 列，用于离行列表的定点填充。 */
    Map<Long, String> findReasoningBatch(Collection<Long> runIds);

    MessageHistoryVO getMessageHistory(Long sessionId, String direction, Long runId);
}
