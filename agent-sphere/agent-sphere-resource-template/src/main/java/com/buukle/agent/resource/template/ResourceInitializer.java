package com.buukle.agent.resource.template;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 资源模板初始化器：一种资源类型对应一个实现。
 * 新增资源类型时，只需新增一个 {@link ResourceInitializer} bean，无需改动协调器。
 */
public interface ResourceInitializer {

    /** 资源类型标识，对应模板 descriptor 的 "type" 字段。 */
    String type();

    /** 执行初始化；资源已存在时应抛出 {@link ResourceExistsException} 以计入 skipped。 */
    void initialize(JsonNode descriptor, ResourceInitContext ctx);
}
