package com.buukle.agent.infrastructure.controller.dtvo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class SystemDicSaveDTO implements Serializable {

    @NotBlank
    @Size(max = 64)
    private String dicType;

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "[A-Za-z0-9_-]+", message = "code may only contain letters, digits, _ and -")
    private String code;

    @NotBlank
    @Size(max = 128)
    private String nameCn;

    @NotBlank
    @Size(max = 128)
    private String nameEn;

    private Integer sortNo;

    @Size(max = 32)
    private String status;

    @Size(max = 512)
    private String remark;
}
