package com.buukle.agent.common.event;

import lombok.Data;

/** 自助注册用户创建完成事件（SSO 开通走 defaultRoleId 路径不发布） */
@Data
public class UserRegisteredEvent {
    private Long userId;
    private String username;
}