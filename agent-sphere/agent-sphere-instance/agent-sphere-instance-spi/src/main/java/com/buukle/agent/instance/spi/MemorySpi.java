package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.MemoryVO;
import java.util.List;

public interface MemorySpi {
    List<MemoryVO> getMemoryBySession(Long sessionId);
    List<MemoryVO> getMemoryByRun(Long runId);
    List<MemoryVO> getMemoryByTask(Long taskId);
}
