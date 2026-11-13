package com.buukle.agent.im.service;

import com.buukle.agent.common.util.PageResult;
import com.buukle.agent.im.dtvo.vo.NotificationVO;

public interface ImNotificationService {

    PageResult<NotificationVO> page(boolean unreadOnly, int page, int pageSize);

    long unreadCount();

    void markRead(Long id);

    void markAllRead();
}
