package com.buukle.agent.resource.template;

/** 资源已存在，跳过（计入 skipped）。 */
public class ResourceExistsException extends RuntimeException {
    public ResourceExistsException() {
        super("resource already exists");
    }
}
