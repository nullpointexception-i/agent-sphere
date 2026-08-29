package com.buukle.agent.model.dtvo.enums;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;

@Getter
@JsonFormat(shape = JsonFormat.Shape.OBJECT)
public enum ModelProviderCompany {
    DEEPSEEK("DeepSeek", "深度求索", "deepseek"),
    OPENAI("OpenAI", "OpenAI", "openai"),
    GLM("GLM", "智谱", "GLM"),
    ORCAROUTER("OrcaRouter", "OrcaRouter", "orcarouter"),
    ;

    private final String nameEn;
    private final String nameCn;
    private final String value;

    ModelProviderCompany(String nameEn, String nameCn, String value) {
        this.nameEn = nameEn;
        this.nameCn = nameCn;
        this.value = value;
    }

    public static ModelProviderCompany fromValue(String value) {
        for (ModelProviderCompany c : values()) {
            if (c.value.equalsIgnoreCase(value)) return c;
        }
        return null;
    }
}
