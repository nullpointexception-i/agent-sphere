package com.buukle.agent.instance.spi;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.vo.MessageHistoryVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface RunSpi {
    RunVO createRun(CreateRunDTO dto);

    RunVO getRun(Long id);

    /** 轻量读取：不加载 reasoning 大字段（扫描任务使用）。 */
    RunVO getRunMeta(Long id);

    /** 该 session 当前最新活动 run（status ∈ PENDING/RUNNING/AWAITING_USER），无则 null。 */
    RunVO findActiveRun(Long sessionId);

    void updateRun(RunVO run);

    List<RunVO> listRunsBySessionAfterId(Long sessionId, Long afterRunId);

    IPage<RunVO> pageRunsBySession(Long sessionId, String keyword, int page, int size);

    /** 按需批量补拉推理：runId → 已落库推理。仅查 reasoning 列，用于离行列表的定点填充。 */
    Map<Long, String> findReasoningBatch(Collection<Long> runIds);

    MessageHistoryVO getMessageHistory(Long sessionId, String direction, Long runId);

    /** 对账用：按状态 + updated_at 早于某时刻查找 run（升序，最多 limit 条）。 */
    List<RunVO> findStaleByStatus(String status, LocalDateTime olderThan, int limit);

    List<Long> findStaleRunIdsByStatus(String status, LocalDateTime olderThan, int limit);

    boolean existsRunAfterId(Long sessionId, Long runId);
}
