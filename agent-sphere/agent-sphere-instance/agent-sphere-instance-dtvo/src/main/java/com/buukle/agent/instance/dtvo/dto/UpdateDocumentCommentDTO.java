package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class UpdateDocumentCommentDTO implements Serializable {

    @NotBlank
    @Size(max = 5000)
    private String content;
}
