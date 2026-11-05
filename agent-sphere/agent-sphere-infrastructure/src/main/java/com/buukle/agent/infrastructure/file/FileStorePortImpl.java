package com.buukle.agent.infrastructure.file;

import com.buukle.agent.common.file.FileStorePort;
import com.buukle.agent.common.file.StoredFile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Bridges the shared file-store contract to the database-backed implementation. */
@Component
@RequiredArgsConstructor
public class FileStorePortImpl implements FileStorePort {

    private final GenericFileService genericFileService;

    @Override
    public StoredFile save(String bizKey, String fileKey, String fileName,
                           String contentType, byte[] content) {
        com.buukle.agent.infrastructure.file.StoredFile stored = genericFileService.save(
                bizKey, fileKey, fileName, contentType, content);
        return new StoredFile(stored.bizKey(), stored.fileKey(), stored.fileName(),
                stored.contentType(), stored.sizeBytes(), stored.content());
    }

    @Override
    public StoredFile get(String bizKey, String fileKey) {
        com.buukle.agent.infrastructure.file.StoredFile stored = genericFileService.get(bizKey, fileKey);
        if (stored == null) {
            return null;
        }
        return new StoredFile(stored.bizKey(), stored.fileKey(), stored.fileName(),
                stored.contentType(), stored.sizeBytes(), stored.content());
    }

    @Override
    public void delete(String bizKey, String fileKey) {
        genericFileService.delete(bizKey, fileKey);
    }
}
