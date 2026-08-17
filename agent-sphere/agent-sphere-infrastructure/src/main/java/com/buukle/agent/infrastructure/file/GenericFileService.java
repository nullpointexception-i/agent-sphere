package com.buukle.agent.infrastructure.file;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.infrastructure.persistence.AgentFileStore;
import com.buukle.agent.infrastructure.persistence.AgentFileStoreMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 基于数据库的通用文件服务：按 (bizKey, fileKey) 唯一 upsert/读取/逻辑删除。
 * PG 共享库存储二进制，多副本天然一致；供插件安装包等需要落库字节的场景复用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenericFileService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final AgentFileStoreMapper fileStoreMapper;

    /** 保存/覆盖文件：同一 (bizKey, fileKey) 已存在则更新内容，否则插入。 */
    public StoredFile save(String bizKey, String fileKey, String fileName,
                           String contentType, byte[] content) {
        if (!StringUtils.hasText(bizKey) || !StringUtils.hasText(fileKey)) {
            throw new IllegalArgumentException("bizKey/fileKey 不能为空");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("文件内容不能为空");
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

    /** 读取文件；不存在返回 null。 */
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

    /** 逻辑删除（@TableLogic → delete_flag）。 */
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