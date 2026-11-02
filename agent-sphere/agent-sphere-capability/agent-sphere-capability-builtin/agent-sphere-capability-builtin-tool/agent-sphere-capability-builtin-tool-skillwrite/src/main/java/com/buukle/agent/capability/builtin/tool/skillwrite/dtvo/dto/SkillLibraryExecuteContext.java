package com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.dto;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 技能库（builtin_8）入参：只包含发现/读取技能所需字段。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SkillLibraryExecuteContext extends ExecuteContext {

    @NotBlank
    @Schema(
            description = "操作类型",
            allowableValues = {"list", "count", "search", "search_by_title", "get"}
    )
    private String action;

    @Schema(description = "get 时按技能 id 读取")
    private Long skillId;

    @Schema(description = "get 时按精确技能名读取")
    private String name;

    @Schema(description = "get 时仅返回 promptTemplate 大纲")
    private Boolean structure;

    @Schema(description = "get 时按标题读取某一节")
    private String sectionHeading;

    @Schema(description = "get 时起始行号")
    private Integer startLine;

    @Schema(description = "get 时结束行号")
    private Integer endLine;

    @Schema(description = "list / search 的名称关键字")
    private String keyword;

    @Schema(description = "list / search 页码", defaultValue = "1")
    private int page = 1;

    @Schema(description = "list / search 每页条数", defaultValue = "20")
    private int pageSize = 20;
}
