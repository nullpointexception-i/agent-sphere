package com.buukle.agent.instance.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.instance.domain.AgentDocumentFolder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentDocumentFolderMapper extends BaseMapper<AgentDocumentFolder> {

    /** Idempotent system-folder creation; concurrent document writes may race. */
    @Insert("""
            INSERT INTO agent_document_folder
                (tenant_id, owner_user_id, parent_id, name, system_key, created_by, updated_by)
            VALUES (#{tenantId}, #{ownerUserId}, NULL, #{name}, 'INSTANCE_ROOT', #{createdBy}, #{createdBy})
            ON CONFLICT DO NOTHING
            """)
    int insertInstanceRoot(@Param("tenantId") Long tenantId,
                           @Param("ownerUserId") Long ownerUserId,
                           @Param("name") String name,
                           @Param("createdBy") String createdBy);

    /** Idempotent instance-folder creation, guarded by the (owner, instance) unique index. */
    @Insert("""
            INSERT INTO agent_document_folder
                (tenant_id, owner_user_id, parent_id, name, instance_id, created_by, updated_by)
            VALUES (#{tenantId}, #{ownerUserId}, #{parentId}, #{name}, #{instanceId}, #{createdBy}, #{createdBy})
            ON CONFLICT DO NOTHING
            """)
    int insertInstanceFolder(@Param("tenantId") Long tenantId,
                             @Param("ownerUserId") Long ownerUserId,
                             @Param("parentId") Long parentId,
                             @Param("name") String name,
                             @Param("instanceId") Long instanceId,
                             @Param("createdBy") String createdBy);
}
