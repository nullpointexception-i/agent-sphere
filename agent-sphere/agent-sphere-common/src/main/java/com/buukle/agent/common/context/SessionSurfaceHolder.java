package com.buukle.agent.common.context;

/**
 * 当前线程的会话来源端（ThreadLocal 传播）。
 *
 * <p>由 {@code AuthInterceptor} 在 HTTP 入口按请求头解析并 set，请求结束必须 clear ——
 * Tomcat 线程是池化的，不清会把上一个请求的来源带进下一个请求。
 *
 * <p>与 {@code AuthContext} / {@code TenantUtil} / {@code TaskLoopLimitHolder} 同一套约定。
 */
public final class SessionSurfaceHolder {

    /**
     * 客户端声明来源的请求头。缺省（未携带）= {@link SessionSurface#CONSOLE}（主站）。
     *
     * <p>这是**策略信号而非安全边界**：伪造它没有意义 —— 用户本就可以直接用主站。
     * 真正的边界是实例开关本身，而那只决定「哪一端能用」，不构成越权。
     */
    public static final String HEADER = "X-Client-Surface";

    private static final ThreadLocal<SessionSurface> SURFACE = new ThreadLocal<>();

    private SessionSurfaceHolder() {
    }

    public static void set(SessionSurface surface) {
        SURFACE.set(surface);
    }

    /** 原始值，未设置时为 {@code null}。 */
    public static SessionSurface get() {
        return SURFACE.get();
    }

    /**
     * 当前来源端；未设置按 {@link SessionSurface#INTERNAL} 处理。
     *
     * <p>内部调用（任务路径）不走拦截器，拿到的永远是 null —— 用这个方法才能安全地放行它们。
     */
    public static SessionSurface current() {
        SessionSurface s = SURFACE.get();
        return s == null ? SessionSurface.INTERNAL : s;
    }

    public static void clear() {
        SURFACE.remove();
    }
}
