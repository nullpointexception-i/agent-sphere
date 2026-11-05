package com.buukle.agent.model.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.model.domain.AgentModelRoute;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RouteMapper extends BaseMapper<AgentModelRoute> {

    @Select("""
            SELECT * FROM agent_model_route
            WHERE is_default = TRUE AND delete_flag = 0
              AND COALESCE(created_by, 'system') = COALESCE(#{owner}, 'system')
            ORDER BY id
            LIMIT 1
            """)
    AgentModelRoute selectDefault(@Param("owner") String owner);

    @Update("""
            UPDATE agent_model_route
            SET is_default = FALSE
            WHERE is_default = TRUE AND delete_flag = 0
              AND COALESCE(created_by, 'system') = COALESCE(#{owner}, 'system')
            """)
    int clearDefault(@Param("owner") String owner);
}
