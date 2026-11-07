package com.buukle.agent.sso.dtvo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Default identity-provider resource template (system default JSON, shown as a hint in the admin console). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceTemplateVO implements Serializable {
    private String template;
}
