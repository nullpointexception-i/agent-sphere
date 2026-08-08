package com.buukle.agent.sso.dtvo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 身份源资源模板默认值（系统默认 JSON，供管理端提示展示）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceTemplateVO implements Serializable {
    private String template;
}
