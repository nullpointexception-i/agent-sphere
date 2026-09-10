package com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.dto;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SkillWriteExecuteContext extends ExecuteContext {
    @NotBlank
    private String action;

    private Long skillId;

    private String name;

    private String description;

    private String definition;

    /** update(patch) 时复用 doc 模块 Markdown 工具对该字段的 section 进行编辑的操作类型。 */
    private String operation;

    private String headingSearch;

    private String searchText;

    private String replaceText;

    /** create / update(full) 提交的 definition，或 patch 时插入/替换的 Markdown 内容。 */
    private String content;
}