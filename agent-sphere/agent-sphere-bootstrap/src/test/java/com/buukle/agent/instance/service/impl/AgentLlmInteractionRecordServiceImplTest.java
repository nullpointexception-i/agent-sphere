package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.buukle.agent.instance.domain.AgentLlmInteractionRecord;
import com.buukle.agent.instance.repository.AgentLlmInteractionRecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * finish.summary 回填：只更新「最新一条且 reply_content 为空」的 CHAT_REPLY，绝不覆盖正常轮次正文。
 */
@ExtendWith(MockitoExtension.class)
class AgentLlmInteractionRecordServiceImplTest {

    @Mock
    AgentLlmInteractionRecordMapper mapper;

    @InjectMocks
    AgentLlmInteractionRecordServiceImpl service;

    @BeforeAll
    static void initMybatisLambdaCache() {
        // LambdaUpdateWrapper 靠 TableInfo 解析列名；纯 Mockito 环境没有 MyBatis 启动过程，不预热会抛 lambda cache 找不到
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "fixture"),
                AgentLlmInteractionRecord.class);
    }

    @Test
    void updateLatestBlankReply_updatesWhenLatestReplyIsBlank() {
        AgentLlmInteractionRecord rec = new AgentLlmInteractionRecord();
        rec.setId(9L);
        rec.setReplyContent(null);
        when(mapper.selectOne(any())).thenReturn(rec);
        when(mapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertTrue(service.updateLatestBlankReply(1L, "summary"));

        verify(mapper).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void updateLatestBlankReply_doesNotOverwriteNonBlankReply() {
        AgentLlmInteractionRecord rec = new AgentLlmInteractionRecord();
        rec.setId(9L);
        rec.setReplyContent("existing");
        when(mapper.selectOne(any())).thenReturn(rec);

        assertFalse(service.updateLatestBlankReply(1L, "summary"),
                "正常轮次已有正文，finish 的 summary 不得覆盖");

        verify(mapper, never()).update(any(), any());
    }

    @Test
    void updateLatestBlankReply_returnsFalseWhenNoRecord() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertFalse(service.updateLatestBlankReply(1L, "summary"));
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void updateLatestBlankReply_ignoresInvalidInput() {
        assertFalse(service.updateLatestBlankReply(null, "summary"));
        assertFalse(service.updateLatestBlankReply(1L, " "));
        verify(mapper, never()).update(any(), any());
    }
}
