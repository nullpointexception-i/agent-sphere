package com.buukle.agent.im.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.im.domain.ImMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ImMessageMapper extends BaseMapper<ImMessage> {

    /** Full-history text search scoped to one conversation. */
    @Select("""
            <script>
            SELECT * FROM im_message
            WHERE conversation_id = #{conversationId}
              AND delete_flag = FALSE
              <if test="beforeSeq != null">
              AND seq &lt; #{beforeSeq}
              </if>
              AND (
                COALESCE(content-&gt;&gt;'text', '') ILIKE CONCAT('%', #{keyword}, '%')
                OR COALESCE(content-&gt;&gt;'fileName', '') ILIKE CONCAT('%', #{keyword}, '%')
              )
            ORDER BY seq DESC
            LIMIT #{limit}
            </script>
            """)
    List<ImMessage> search(@Param("conversationId") Long conversationId,
                           @Param("keyword") String keyword,
                           @Param("beforeSeq") Long beforeSeq,
                           @Param("limit") int limit);
}
