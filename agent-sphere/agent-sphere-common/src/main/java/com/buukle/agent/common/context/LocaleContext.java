package com.buukle.agent.common.context;

/**
 * Request-scoped locale used by data-copy resolution. The interceptor sets it
 * from Accept-Language; async tasks must pass an explicit locale instead.
 */
public final class LocaleContext {

    private static final String DEFAULT_LOCALE = "zh-CN";
    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private LocaleContext() {
    }

    public static void set(String rawLocale) {
        HOLDER.set(normalize(rawLocale));
    }

    public static String get() {
        String locale = HOLDER.get();
        return locale == null ? DEFAULT_LOCALE : locale;
    }

    public static void clear() {
        HOLDER.remove();
    }

    static String normalize(String rawLocale) {
        if (rawLocale == null || rawLocale.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String first = rawLocale.split(",")[0].trim().toLowerCase();
        int semicolon = first.indexOf(';');
        if (semicolon >= 0) {
            first = first.substring(0, semicolon);
        }
        if (first.equals("en") || first.startsWith("en-")) {
            return "en-US";
        }
        if (first.equals("zh") || first.startsWith("zh-")) {
            return "zh-CN";
        }
        return DEFAULT_LOCALE;
    }
}
