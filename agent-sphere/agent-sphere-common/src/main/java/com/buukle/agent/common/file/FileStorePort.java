package com.buukle.agent.common.file;

/**
 * Storage contract for small binary files kept in the shared file store.
 *
 * <p>IM and other business modules depend on this port instead of the infrastructure
 * implementation, keeping the module dependency direction one-way.
 */
public interface FileStorePort {

    StoredFile save(String bizKey, String fileKey, String fileName,
                    String contentType, byte[] content);

    StoredFile get(String bizKey, String fileKey);

    void delete(String bizKey, String fileKey);
}
