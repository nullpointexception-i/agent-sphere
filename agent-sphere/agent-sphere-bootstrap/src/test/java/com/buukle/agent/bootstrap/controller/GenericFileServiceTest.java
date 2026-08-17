package com.buukle.agent.bootstrap.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.buukle.agent.infrastructure.file.GenericFileService;
import com.buukle.agent.infrastructure.file.StoredFile;
import com.buukle.agent.infrastructure.persistence.AgentFileStore;
import com.buukle.agent.infrastructure.persistence.AgentFileStoreMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GenericFileServiceTest {

    @Mock
    AgentFileStoreMapper fileStoreMapper;

    @InjectMocks
    GenericFileService genericFileService;

    @BeforeAll
    static void initMpTableInfo() {
        // 单元测试无 MyBatis-Plus 上下文：LambdaWrapper 需要实体 TableInfo
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentFileStore.class);
    }

    @Test
    void save_noExisting_inserts() {
        given(fileStoreMapper.selectOne(any(Wrapper.class))).willReturn(null);

        StoredFile saved = genericFileService.save("biz", "key", "a.zip", "application/zip", new byte[]{1, 2, 3});

        ArgumentCaptor<AgentFileStore> captor = ArgumentCaptor.forClass(AgentFileStore.class);
        verify(fileStoreMapper).insert(captor.capture());
        assertEquals("biz", captor.getValue().getBizKey());
        assertEquals("key", captor.getValue().getFileKey());
        assertEquals("a.zip", captor.getValue().getFileName());
        assertEquals(3L, captor.getValue().getSizeBytes());
        assertEquals("application/zip", captor.getValue().getContentType());
        assertArrayEquals(new byte[]{1, 2, 3}, captor.getValue().getContent());
        assertEquals("biz", saved.bizKey());
        assertEquals(3L, saved.sizeBytes());
    }

    @Test
    void save_existing_updatesById() {
        AgentFileStore existing = new AgentFileStore();
        existing.setId(7L);
        given(fileStoreMapper.selectOne(any(Wrapper.class))).willReturn(existing);

        genericFileService.save("biz", "key", "b.zip", "application/zip", new byte[]{9});

        ArgumentCaptor<AgentFileStore> captor = ArgumentCaptor.forClass(AgentFileStore.class);
        verify(fileStoreMapper).updateById(captor.capture());
        assertEquals(7L, captor.getValue().getId());
        assertEquals("biz", captor.getValue().getBizKey());
        assertArrayEquals(new byte[]{9}, captor.getValue().getContent());
    }

    @Test
    void save_rejectsEmptyContent() {
        assertThrows(IllegalArgumentException.class,
                () -> genericFileService.save("biz", "key", "a.zip", "application/zip", new byte[0]));
    }

    @Test
    void get_returnsStoredFileWhenFound() {
        AgentFileStore entity = new AgentFileStore();
        entity.setBizKey("biz");
        entity.setFileKey("key");
        entity.setFileName("a.zip");
        entity.setContentType("application/zip");
        entity.setSizeBytes(3L);
        entity.setContent(new byte[]{1, 2, 3});
        given(fileStoreMapper.selectOne(any(Wrapper.class))).willReturn(entity);

        StoredFile stored = genericFileService.get("biz", "key");

        assertNotNull(stored);
        assertEquals("biz", stored.bizKey());
        assertEquals("key", stored.fileKey());
        assertArrayEquals(new byte[]{1, 2, 3}, stored.content());
        assertEquals(3L, stored.sizeBytes());
    }

    @Test
    void get_returnsNullWhenAbsent() {
        given(fileStoreMapper.selectOne(any(Wrapper.class))).willReturn(null);

        assertNull(genericFileService.get("biz", "key"));
    }

    @Test
    void exists_checksCount() {
        given(fileStoreMapper.selectCount(any(Wrapper.class))).willReturn(1L);
        assertTrue(genericFileService.exists("biz", "key"));

        given(fileStoreMapper.selectCount(any(Wrapper.class))).willReturn(0L);
        assertFalse(genericFileService.exists("biz", "key"));
    }

    @Test
    void delete_logicalDeletes() {
        genericFileService.delete("biz", "key");

        verify(fileStoreMapper).delete(any(Wrapper.class));
    }
}