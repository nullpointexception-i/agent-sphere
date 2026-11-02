package com.buukle.agent.common.context;

/**
 * 请求头 → {@link SessionSurface} 的解析。
 *
 * <p>抽成无 servlet 依赖的静态方法，是因为两条入口都需要它，但依赖方向不同：
 * {@code AuthInterceptor} 在 infrastructure，登录/SSO 控制器在 instance-controller，
 * 两者不能互相依赖。调用方各自从 {@code HttpServletRequest} 取头，再交给这里判定。
 */
public final class SessionSurfaceResolver {

    private SessionSurfaceResolver() {
    }

    /**
     * 缺省（未带头）按主站处理；只有显式声明 {@code widget} 才判为 widget。
     *
     * <p>这是策略信号而非安全边界，与 {@link SessionSurfaceHolder} 的约定一致。
     */
    public static SessionSurface fromHeader(String rawHeader) {
        return SessionSurface.WIDGET.name().equalsIgnoreCase(rawHeader == null ? null : rawHeader.trim())
                ? SessionSurface.WIDGET
                : SessionSurface.CONSOLE;
    }
}
