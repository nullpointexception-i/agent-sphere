package com.buukle.agent.project.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.project.domain.PmProject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PmProjectMapper extends BaseMapper<PmProject> {

    @Update("UPDATE pm_project SET requirement_seq = requirement_seq + 1, updated_at = now() " +
            "WHERE id = #{projectId} AND delete_flag = FALSE")
    int incrementRequirementSeq(@Param("projectId") Long projectId);

    @Select("SELECT requirement_seq FROM pm_project WHERE id = #{projectId} AND delete_flag = FALSE")
    Long selectRequirementSeq(@Param("projectId") Long projectId);
}
