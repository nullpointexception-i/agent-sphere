package com.buukle.agent.infrastructure.controller.dtvo;

import lombok.Data;

import java.io.Serializable;

@Data
public class SystemDicVO implements Serializable {
    private Long id;
    private String dicType;
    private String code;
    private String nameCn;
    private String nameEn;
    private Integer sortNo;
    private String status;
    private String remark;
}
