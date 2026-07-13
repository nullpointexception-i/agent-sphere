package com.buukle.agent.common.config;

public interface SystemConfigSpi {
    String get(String key);
    String get(String key, String defaultValue);
    void set(String key, String value);
}
