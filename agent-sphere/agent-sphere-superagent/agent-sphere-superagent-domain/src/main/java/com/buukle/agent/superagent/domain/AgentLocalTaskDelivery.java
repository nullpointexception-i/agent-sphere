package com.buukle.agent.superagent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Join table: one local task can cover several router deliveries. */
@Data
@TableName("agent_local_task_delivery")
public class AgentLocalTaskDelivery {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long deliveryId;
    @TableLogic(value = "false", delval = "true")
    private Boolean deleteFlag;
    private LocalDateTime createdAt;
}
