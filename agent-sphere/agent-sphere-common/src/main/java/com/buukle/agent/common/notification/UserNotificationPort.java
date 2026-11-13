package com.buukle.agent.common.notification;

/** Writes a notification for a user. Implementations must be idempotent. */
public interface UserNotificationPort {

    void notifyUser(UserNotificationCommand command);
}
