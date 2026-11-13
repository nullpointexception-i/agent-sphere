package com.buukle.agent.application.service;

import com.buukle.agent.application.dtvo.ApplicationRunVO;
import com.buukle.agent.application.dtvo.CreateApplicationRunDTO;
import com.buukle.agent.common.util.PageResult;

public interface ApplicationRunService {
    ApplicationRunVO trigger(Long sceneId, CreateApplicationRunDTO dto);

    ApplicationRunVO triggerCron(Long sceneId, Long runAsUserId, java.time.LocalDateTime scheduledAt);

    PageResult<ApplicationRunVO> page(Long applicationId, Long sceneId, int page, int pageSize);

    ApplicationRunVO detail(Long id);

    void cancel(Long id);

    /** Resumes a run parked on approval by requeueing its work item. */
    ApplicationRunVO resume(Long id);
}
