package com.buukle.agent.common.context;

import java.util.Set;

public class AuthContext {
    private static final InheritableThreadLocal<String> TOKEN_HOLDER = new InheritableThreadLocal<>();
    private static final InheritableThreadLocal<String> USERNAME_HOLDER = new InheritableThreadLocal<>();
    private static final InheritableThreadLocal<String> DISPLAY_NAME_HOLDER = new InheritableThreadLocal<>();
    private static final InheritableThreadLocal<Long> USER_ID_HOLDER = new InheritableThreadLocal<>();
    private static final InheritableThreadLocal<Boolean> SUPER_ADMIN_HOLDER = new InheritableThreadLocal<>();
    private static final InheritableThreadLocal<Set<String>> PERMISSIONS_HOLDER = new InheritableThreadLocal<>();

    public static String getToken() {
        return TOKEN_HOLDER.get();
    }

    public static void setToken(String token) {
        TOKEN_HOLDER.set(token);
    }

    public static String getUsername() {
        return USERNAME_HOLDER.get();
    }

    public static void setUsername(String username) {
        USERNAME_HOLDER.set(username);
    }

    public static String getDisplayName() {
        return DISPLAY_NAME_HOLDER.get();
    }

    public static void setDisplayName(String displayName) {
        DISPLAY_NAME_HOLDER.set(displayName);
    }

    public static Long getUserId() {
        return USER_ID_HOLDER.get();
    }

    public static void setUserId(Long userId) {
        USER_ID_HOLDER.set(userId);
    }

    public static boolean isSuperAdmin() {
        return Boolean.TRUE.equals(SUPER_ADMIN_HOLDER.get());
    }

    public static void setSuperAdmin(boolean superAdmin) {
        SUPER_ADMIN_HOLDER.set(superAdmin);
    }

    public static Set<String> getPermissions() {
        return PERMISSIONS_HOLDER.get();
    }

    public static void setPermissions(Set<String> permissions) {
        PERMISSIONS_HOLDER.set(permissions);
    }

    public static boolean hasPermission(String code) {
        Set<String> perms = PERMISSIONS_HOLDER.get();
        return perms != null && perms.contains(code);
    }

    public static void clear() {
        TOKEN_HOLDER.remove();
        USERNAME_HOLDER.remove();
        DISPLAY_NAME_HOLDER.remove();
        USER_ID_HOLDER.remove();
        SUPER_ADMIN_HOLDER.remove();
        PERMISSIONS_HOLDER.remove();
    }
}
