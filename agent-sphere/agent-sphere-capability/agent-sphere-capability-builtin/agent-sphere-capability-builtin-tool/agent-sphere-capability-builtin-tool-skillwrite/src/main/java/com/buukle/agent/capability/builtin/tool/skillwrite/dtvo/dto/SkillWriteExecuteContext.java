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

    /** create / update(full) 提交的 definition，或 patch/append 时插入/替换的 Markdown 内容。 */
    private String content;

    /** get 时：structure=true 仅返回 promptTemplate 大纲（headings + line 编号）。 */
    private Boolean structure;

    /** get 时：按标题提取 promptTemplate 对应 section 正文。 */
    private String sectionHeading;

    /** get 时：按行号提取 promptTemplate 内容（含标题行）。 */
    private Integer startLine;

    /** get 时：与 startLine 搭配的行范围终点，缺省取到末尾。 */
    private Integer endLine;

    /** search_by_title 时的名称关键字。 */
    private String keyword;

    /** list / search_by_title 分页。 */
    private int page = 1;

    /** list / search_by_title 分页大小。 */
    private int pageSize = 20;
}