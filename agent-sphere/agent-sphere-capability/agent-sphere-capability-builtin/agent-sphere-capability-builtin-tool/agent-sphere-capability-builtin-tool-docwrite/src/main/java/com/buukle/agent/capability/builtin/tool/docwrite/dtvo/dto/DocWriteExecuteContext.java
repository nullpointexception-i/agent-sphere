package com.buukle.agent.capability.builtin.tool.docwrite.dtvo.dto;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DocWriteExecuteContext extends ExecuteContext {
    @NotBlank
    private String action;

    private String title;

    private String content;

    private Long documentId;

    private String operation;

    private String headingSearch;

    private String searchText;

    private String replaceText;
}
