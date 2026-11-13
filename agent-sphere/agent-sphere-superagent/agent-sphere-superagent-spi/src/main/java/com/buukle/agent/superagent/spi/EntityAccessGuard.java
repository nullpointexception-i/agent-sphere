package com.buukle.agent.superagent.spi;

/**
 * Guards access to a business entity before an IM conversation is created or
 * used. Implementations live in the owning business module.
 */
public interface EntityAccessGuard {

    default boolean supports(String entityType) {
        return true;
    }

    void assertCanView(String entityType, Long entityId, Long userId);

    default void assertCanOperate(String entityType, Long entityId, Long userId) {
        assertCanView(entityType, entityId, userId);
    }
}
