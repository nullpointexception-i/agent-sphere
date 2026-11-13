package com.buukle.agent.im.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.notification.NotificationStatus;
import com.buukle.agent.common.notification.UserNotificationCommand;
import com.buukle.agent.common.notification.UserNotificationPort;
import com.buukle.agent.common.util.PageResult;
import com.buukle.agent.im.domain.ImNotification;
import com.buukle.agent.im.dtvo.vo.NotificationVO;
import com.buukle.agent.im.repository.ImNotificationMapper;
import com.buukle.agent.im.service.ImNotificationService;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ImNotificationServiceImpl implements ImNotificationService, UserNotificationPort {

    private final ImNotificationMapper notificationMapper;

    @Override
    @Transactional
    public void notifyUser(UserNotificationCommand command) {
        if (command == null || command.userId() == null) {
            return;
        }
        ImNotification existing = findExisting(command);
        ImNotification row = existing == null ? new ImNotification() : existing;
        if (existing == null) {
            row.setUserId(command.userId());
            row.setTenantId(command.tenantId());
            row.setType(command.type());
            row.setStatus(NotificationStatus.ACTIVE);
            row.setReadFlag(false);
        }
        row.setTitleKey(command.titleKey());
        row.setBodyKey(command.bodyKey());
        row.setPayload(command.payload() == null ? null : JsonUtils.toJson(command.payload()));
        row.setSourceType(command.sourceType());
        row.setSourceId(command.sourceId());
        row.setReadFlag(false);
        if (existing == null) {
            notificationMapper.insert(row);
        } else {
            notificationMapper.updateById(row);
        }
    }

    @Override
    public PageResult<NotificationVO> page(boolean unreadOnly, int page, int pageSize) {
        Long userId = currentUserId();
        LambdaQueryWrapper<ImNotification> wrapper = new LambdaQueryWrapper<ImNotification>()
                .eq(ImNotification::getUserId, userId)
                .eq(ImNotification::getStatus, NotificationStatus.ACTIVE)
                .eq(unreadOnly, ImNotification::getReadFlag, false)
                .orderByDesc(ImNotification::getCreatedAt);
        Page<ImNotification> result = notificationMapper.selectPage(Page.of(page, pageSize), wrapper);
        List<NotificationVO> rows = result.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(rows, result.getTotal(), page, pageSize);
    }

    @Override
    public long unreadCount() {
        Long userId = currentUserId();
        return notificationMapper.selectCount(new LambdaQueryWrapper<ImNotification>()
                .eq(ImNotification::getUserId, userId)
                .eq(ImNotification::getStatus, NotificationStatus.ACTIVE)
                .eq(ImNotification::getReadFlag, false));
    }

    @Override
    @Transactional
    public void markRead(Long id) {
        Long userId = currentUserId();
        ImNotification row = notificationMapper.selectById(id);
        if (row == null || !userId.equals(row.getUserId())) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "notification not found");
        }
        if (Boolean.TRUE.equals(row.getReadFlag())) {
            return;
        }
        row.setReadFlag(true);
        notificationMapper.updateById(row);
    }

    @Override
    @Transactional
    public void markAllRead() {
        Long userId = currentUserId();
        ImNotification patch = new ImNotification();
        patch.setReadFlag(true);
        notificationMapper.update(patch, new LambdaQueryWrapper<ImNotification>()
                .eq(ImNotification::getUserId, userId)
                .eq(ImNotification::getReadFlag, false));
    }

    private ImNotification findExisting(UserNotificationCommand command) {
        if (command.sourceType() == null || command.sourceId() == null) {
            return null;
        }
        return notificationMapper.selectOne(new LambdaQueryWrapper<ImNotification>()
                .eq(ImNotification::getUserId, command.userId())
                .eq(ImNotification::getType, command.type())
                .eq(ImNotification::getSourceType, command.sourceType())
                .eq(ImNotification::getSourceId, command.sourceId())
                .last("limit 1"));
    }

    private NotificationVO toVO(ImNotification row) {
        NotificationVO vo = new NotificationVO();
        vo.setId(row.getId());
        vo.setType(row.getType());
        vo.setTitleKey(row.getTitleKey());
        vo.setBodyKey(row.getBodyKey());
        vo.setPayload(row.getPayload());
        vo.setSourceType(row.getSourceType());
        vo.setSourceId(row.getSourceId());
        vo.setRead(Boolean.TRUE.equals(row.getReadFlag()));
        vo.setCreatedAt(row.getCreatedAt());
        return vo;
    }

    private Long currentUserId() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
