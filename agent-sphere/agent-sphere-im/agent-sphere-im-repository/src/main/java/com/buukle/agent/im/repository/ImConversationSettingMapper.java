package com.buukle.agent.im.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.im.domain.ImConversationSetting;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ImConversationSettingMapper extends BaseMapper<ImConversationSetting> {

    /** New messages make a previously deleted chat visible again for all members. */
    @Update("""
            UPDATE im_conversation_setting
            SET hidden = FALSE,
                updated_at = now()
            WHERE conversation_id = #{conversationId}
              AND hidden = TRUE
              AND delete_flag = FALSE
            """)
    int unhideByConversation(@Param("conversationId") Long conversationId);
}
