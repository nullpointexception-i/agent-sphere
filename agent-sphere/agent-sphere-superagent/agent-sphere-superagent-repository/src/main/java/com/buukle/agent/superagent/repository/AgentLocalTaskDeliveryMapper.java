package com.buukle.agent.superagent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.superagent.domain.AgentLocalTaskDelivery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentLocalTaskDeliveryMapper extends BaseMapper<AgentLocalTaskDelivery> {

    @Update("""
            UPDATE agent_event_delivery d
            SET status = 'PROCESSED', delivered_at = now(), updated_at = now()
            FROM agent_local_task_delivery td
            WHERE td.task_id = #{taskId}
              AND td.delete_flag = FALSE
              AND d.id = td.delivery_id
              AND d.delete_flag = FALSE
            """)
    int markProcessed(@Param("taskId") Long taskId);

    @Update("""
            UPDATE agent_event_delivery d
            SET status = 'FAILED', remark = #{remark}, updated_at = now()
            FROM agent_local_task_delivery td
            WHERE td.task_id = #{taskId}
              AND td.delete_flag = FALSE
              AND d.id = td.delivery_id
              AND d.delete_flag = FALSE
            """)
    int markFailed(@Param("taskId") Long taskId, @Param("remark") String remark);

    @Update("""
            UPDATE agent_event_delivery d
            SET status = 'FAILED', remark = 'local task lease expired', updated_at = now()
            FROM agent_local_task t
            JOIN agent_local_task_delivery td
              ON td.task_id = t.id AND td.delete_flag = FALSE
            WHERE t.status = 'EXPIRED'
              AND d.id = td.delivery_id
              AND d.status IN ('CLAIMED', 'RUNNING')
              AND d.delete_flag = FALSE
            """)
    int markExpiredDeliveries();
}
