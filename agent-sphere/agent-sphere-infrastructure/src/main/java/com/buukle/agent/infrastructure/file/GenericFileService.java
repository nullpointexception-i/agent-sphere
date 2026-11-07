package com.buukle.agent.infrastructure.file;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.infrastructure.persistence.AgentFileStore;
import com.buukle.agent.infrastructure.persistence.AgentFileStoreMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Database-backed generic file service: upsert/read/logical-delete by unique (bizKey, fileKey).
 * The shared PG database stores the binary, so multiple replicas are consistent by nature; reusable for
 * scenarios that need bytes persisted, such as plugin installers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenericFileService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final AgentFileStoreMapper fileStoreMapper;

    /** Saves/overwrites a file: if the same (bizKey, fileKey) already exists, update the content; otherwise insert. */
    public StoredFile save(String bizKey, String fileKey, String fileName,
                           String contentType, byte[] content) {
        if (!StringUtils.hasText(bizKey) || !StringUtils.hasText(fileKey)) {
            throw new IllegalArgumentException("bizKey/fileKey must not be blank");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("file content must not be empty");
        }
        AgentFileStore existing = findIdOnly(bizKey, fileKey);
        AgentFileStore entity = new AgentFileStore();
        entity.setBizKey(bizKey);
        entity.setFileKey(fileKey);
        entity.setFileName(StringUtils.hasText(fileName) ? fileName : fileKey);
        entity.setContent(content);
        entity.setSizeBytes((long) content.length);
        entity.setContentType(StringUtils.hasText(contentType) ? contentType : "application/octet-stream");
        entity.setStatus(STATUS_ACTIVE);
        if (existing != null) {
            entity.setId(existing.getId());
            fileStoreMapper.updateById(entity);
        } else {
            fileStoreMapper.insert(entity);
        }
        return new StoredFile(bizKey, fileKey, entity.getFileName(),
                entity.getContentType(), entity.getSizeBytes(), content);
    }

    /** Reads a file; returns null if it does not exist. */
    public StoredFile get(String bizKey, String fileKey) {
        AgentFileStore entity = findOne(bizKey, fileKey);
        if (entity == null) {
            return null;
        }
        return new StoredFile(entity.getBizKey(), entity.getFileKey(), entity.getFileName(),
                entity.getContentType(), entity.getSizeBytes(), entity.getContent());
    }

    public boolean exists(String bizKey, String fileKey) {
        return fileStoreMapper.selectCount(new LambdaQueryWrapper<AgentFileStore>()
                .eq(AgentFileStore::getBizKey, bizKey)
                .eq(AgentFileStore::getFileKey, fileKey)) > 0;
    }

    /** Logical delete (@TableLogic → delete_flag). */
    public void delete(String bizKey, String fileKey) {
        fileStoreMapper.delete(new LambdaQueryWrapper<AgentFileStore>()
                .eq(AgentFileStore::getBizKey, bizKey)
                .eq(AgentFileStore::getFileKey, fileKey));
    }

    private AgentFileStore findIdOnly(String bizKey, String fileKey) {
        return fileStoreMapper.selectOne(new LambdaQueryWrapper<AgentFileStore>()
                .eq(AgentFileStore::getBizKey, bizKey)
                .eq(AgentFileStore::getFileKey, fileKey)
                .select(AgentFileStore::getId));
    }

    private AgentFileStore findOne(String bizKey, String fileKey) {
        return fileStoreMapper.selectOne(new LambdaQueryWrapper<AgentFileStore>()
                .eq(AgentFileStore::getBizKey, bizKey)
                .eq(AgentFileStore::getFileKey, fileKey));
    }
}