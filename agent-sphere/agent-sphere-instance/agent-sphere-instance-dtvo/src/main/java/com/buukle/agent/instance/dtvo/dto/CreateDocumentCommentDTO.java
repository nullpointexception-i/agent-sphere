package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class CreateDocumentCommentDTO implements Serializable {

    @NotBlank
    @Size(max = 5000)
    private String content;

    @Size(max = 500)
    private String quotedText;

    /** Parent comment for a one-level reply. */
    private Long parentId;
}
