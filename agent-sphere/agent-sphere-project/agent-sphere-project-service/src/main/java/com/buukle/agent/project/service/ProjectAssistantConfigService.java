package com.buukle.agent.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.constant.ProjectAssistantConstants;
import com.buukle.agent.project.domain.PmProjectAssistantConfig;
import com.buukle.agent.project.repository.PmProjectAssistantConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Auto-analysis switch, stored per project. */
@Service
@RequiredArgsConstructor
public class ProjectAssistantConfigService {

    private final PmProjectAssistantConfigMapper configMapper;

    public boolean isAutoAnalyze(Long projectId) {
        if (projectId == null) {
            return false;
        }
        PmProjectAssistantConfig config = configMapper.selectOne(
                new LambdaQueryWrapper<PmProjectAssistantConfig>()
                        .eq(PmProjectAssistantConfig::getProjectId, projectId)
                        .last("LIMIT 1"));
        return config != null && Boolean.TRUE.equals(config.getAutoAnalyze());
    }

    public void setAutoAnalyze(Long tenantId, Long projectId, boolean enabled) {
        if (projectId == null) {
            return;
        }
        PmProjectAssistantConfig existing = configMapper.selectOne(
                new LambdaQueryWrapper<PmProjectAssistantConfig>()
                        .eq(PmProjectAssistantConfig::getProjectId, projectId)
                        .last("LIMIT 1"));
        if (existing == null) {
            PmProjectAssistantConfig config = new PmProjectAssistantConfig();
            config.setTenantId(tenantId);
            config.setProjectId(projectId);
            config.setAutoAnalyze(enabled);
            config.setStatus(ProjectAssistantConstants.STATUS_ACTIVE);
            configMapper.insert(config);
            return;
        }
        if (!Boolean.valueOf(enabled).equals(existing.getAutoAnalyze())) {
            existing.setAutoAnalyze(enabled);
            configMapper.updateById(existing);
        }
    }
}
