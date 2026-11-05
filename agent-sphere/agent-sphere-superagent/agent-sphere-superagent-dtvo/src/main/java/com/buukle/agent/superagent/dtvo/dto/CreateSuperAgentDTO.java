package com.buukle.agent.superagent.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class CreateSuperAgentDTO implements Serializable {

    @NotBlank
    @Size(max = 128)
    private String name;
    @Size(max = 512)
    private String description;
    /** 角色字典 code（system_dic.type=POSITION）；新建时必填。 */
    @Size(max = 64)
    private String positionCode;
    /** 预算/触发/回复规范（JSON）。 */
    private String config;
}
