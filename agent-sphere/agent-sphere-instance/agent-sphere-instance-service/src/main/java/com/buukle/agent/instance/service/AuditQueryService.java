package com.buukle.agent.instance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.instance.domain.AgentAuditLog;
import com.buukle.agent.instance.dtvo.vo.AuditLogVO;
import com.buukle.agent.instance.repository.AgentAuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private final AgentAuditLogMapper auditLogMapper;

    public Page<AuditLogVO> listPage(int page, int size, String username, String action,
                                     String resourceType, LocalDateTime startDate, LocalDateTime endDate) {
        LambdaQueryWrapper<AgentAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentAuditLog::getDeleteFlag, 0);

        if (username != null && !username.isEmpty()) {
            wrapper.like(AgentAuditLog::getUsername, username);
        }
        if (action != null && !action.isEmpty()) {
            wrapper.eq(AgentAuditLog::getAction, action);
        }
        if (resourceType != null && !resourceType.isEmpty()) {
            wrapper.eq(AgentAuditLog::getResourceType, resourceType);
        }
        if (startDate != null) {
            wrapper.ge(AgentAuditLog::getCreatedAt, startDate);
        }
        if (endDate != null) {
            wrapper.le(AgentAuditLog::getCreatedAt, endDate);
        }

        wrapper.orderByDesc(AgentAuditLog::getCreatedAt);

        Page<AgentAuditLog> mpPage = auditLogMapper.selectPage(new Page<>(page, size), wrapper);

        Page<AuditLogVO> voPage = new Page<>(mpPage.getCurrent(), mpPage.getSize(), mpPage.getTotal());
        voPage.setRecords(mpPage.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return voPage;
    }

    private AuditLogVO toVO(AgentAuditLog entity) {
        AuditLogVO vo = new AuditLogVO();
        vo.setId(entity.getId());
        vo.setUserId(entity.getUserId());
        vo.setUsername(entity.getUsername());
        vo.setAction(entity.getAction());
        vo.setResourceType(entity.getResourceType());
        vo.setResourceId(entity.getResourceId());
        vo.setDetail(entity.getDetail());
        vo.setIpAddress(entity.getIpAddress());
        vo.setSuccess(Boolean.TRUE.equals(entity.getSuccess()));
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
