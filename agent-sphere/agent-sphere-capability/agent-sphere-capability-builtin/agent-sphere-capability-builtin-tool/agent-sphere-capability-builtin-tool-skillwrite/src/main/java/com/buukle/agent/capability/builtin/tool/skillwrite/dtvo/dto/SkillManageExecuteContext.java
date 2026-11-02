package com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.dto;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 技能管理（builtin_9）入参：只包含创建/追加/更新所需字段。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SkillManageExecuteContext extends ExecuteContext {

    @NotBlank
    @Schema(
            description = "操作类型",
            allowableValues = {"create", "append", "update"}
    )
    private String action;

    @Schema(description = "append / update 的目标技能 id")
    private Long skillId;

    @Schema(description = "create 的技能名称")
    private String name;

    @Schema(description = "技能描述")
    private String description;

    @Schema(description = "create / update(full) 提交的 definition JSON")
    private String definition;

    @Schema(
            description = "update(patch) 的 Markdown 操作类型",
            allowableValues = {"replace_section", "insert_after", "replace_text"}
    )
    private String operation;

    @Schema(description = "patch 时用于定位的标题")
    private String headingSearch;

    @Schema(description = "replace_text 时被替换的文本")
    private String searchText;

    @Schema(description = "replace_text 时的替换文本")
    private String replaceText;

    @Schema(description = "append 追加内容 / patch 替换内容")
    private String content;
}
