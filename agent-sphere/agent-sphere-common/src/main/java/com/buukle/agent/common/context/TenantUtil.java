package com.buukle.agent.common.context;

public class TenantUtil {
    private static final InheritableThreadLocal<String> HOLDER = new InheritableThreadLocal<>();

    public static void start(String value) { HOLDER.set(value); }
    public static String get() { return HOLDER.get(); }
    public static void stop() { HOLDER.remove(); }
}
