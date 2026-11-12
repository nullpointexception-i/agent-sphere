package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class CreateDocumentFolderDTO implements Serializable {

    @NotBlank
    @Size(max = 128)
    private String name;

    private Long parentId;
}
