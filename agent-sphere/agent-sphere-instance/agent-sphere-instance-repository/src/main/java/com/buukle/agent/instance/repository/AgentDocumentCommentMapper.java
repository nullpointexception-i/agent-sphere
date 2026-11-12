package com.buukle.agent.instance.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.instance.domain.AgentDocumentComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentDocumentCommentMapper extends BaseMapper<AgentDocumentComment> {

    @Select("""
            <script>
            SELECT document_id, COUNT(*) AS comment_count
            FROM agent_document_comment
            WHERE delete_flag = false
              AND document_id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY document_id
            </script>
            """)
    List<DocumentCommentCount> countByDocumentIds(@Param("ids") List<Long> ids);
}
