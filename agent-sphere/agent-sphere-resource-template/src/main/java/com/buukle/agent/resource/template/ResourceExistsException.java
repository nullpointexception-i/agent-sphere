package com.buukle.agent.resource.template;

/** The resource already exists; skip it (counted as skipped). */
public class ResourceExistsException extends RuntimeException {
    public ResourceExistsException() {
        super("resource already exists");
    }
}
