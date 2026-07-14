package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.ClarificationVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface ClarificationSpi {
    Long createPending(Long sessionId, Long runId, Long messageId, String title, String type, String optionsJson, String clarificationId);

    Map<Long, List<ClarificationVO>> mapByRunIdList(Collection<Long> runIds);
}
