package com.buukle.agent.im.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.buukle.agent.im.domain.ImConversation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ImConversationMapper extends BaseMapper<ImConversation> {

    /** Atomically increments the conversation sequence; a single UPDATE ... RETURNING guarantees strict monotonicity. */
    @Select("""
            UPDATE im_conversation
            SET last_seq = last_seq + 1
            WHERE id = #{id} AND delete_flag = FALSE
            RETURNING last_seq
            """)
    Long nextSeq(@Param("id") Long id);

    @Update("""
            UPDATE im_conversation
            SET thread_id = #{threadId},
                updated_at = now()
            WHERE id = #{id} AND thread_id IS NULL AND delete_flag = FALSE
            """)
    int updateThreadId(@Param("id") Long id, @Param("threadId") Long threadId);

    @Update("""
            UPDATE im_conversation
            SET last_message_id = #{messageId},
                last_message_at = #{sentAt},
                updated_at = now()
            WHERE id = #{id} AND delete_flag = FALSE
            """)
    int updateLastMessage(@Param("id") Long id,
                          @Param("messageId") Long messageId,
                          @Param("sentAt") LocalDateTime sentAt);

    @Update("""
            UPDATE im_conversation
            SET pinned_message_id = #{messageId},
                updated_at = now()
            WHERE id = #{id} AND delete_flag = FALSE
            """)
    int updatePinnedMessage(@Param("id") Long id, @Param("messageId") Long messageId);

    /**
     * Cursor page of conversations the user participates in, excluding pinned ids.
     * The caller wraps this in systemRead because DM rows may be created by the other user.
     */
    @Select("""
            <script>
            SELECT c.*
            FROM im_conversation c
            JOIN im_participant p
              ON p.conversation_id = c.id
             AND p.participant_type = 'USER'
             AND p.participant_id = #{userId}
             AND p.delete_flag = FALSE
            WHERE c.delete_flag = FALSE
            AND c.status = 'ACTIVE'
            <if test="excludeIds != null and !excludeIds.isEmpty()">
              AND c.id NOT IN
              <foreach collection="excludeIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
            </if>
            <if test="conversationType != null and conversationType != ''">
              AND c.type = #{conversationType}
            </if>
            <if test="cursorTime != null">
              AND (COALESCE(c.last_message_at, c.created_at) &lt; #{cursorTime}
                   OR (COALESCE(c.last_message_at, c.created_at) = #{cursorTime} AND c.id &lt; #{cursorId}))
            </if>
            ORDER BY COALESCE(c.last_message_at, c.created_at) DESC, c.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<ImConversation> pageForUser(@Param("userId") Long userId,
                                     @Param("excludeIds") List<Long> excludeIds,
                                     @Param("conversationType") String conversationType,
                                     @Param("cursorTime") LocalDateTime cursorTime,
                                     @Param("cursorId") Long cursorId,
                                     @Param("limit") int limit);
}
