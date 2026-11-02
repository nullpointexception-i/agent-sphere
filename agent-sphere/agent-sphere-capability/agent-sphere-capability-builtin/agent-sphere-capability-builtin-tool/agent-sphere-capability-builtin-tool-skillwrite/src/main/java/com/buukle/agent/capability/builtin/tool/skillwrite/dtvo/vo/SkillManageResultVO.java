package com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.vo;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.vo.HeadingInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

/** 技能管理（builtin_9）出参。 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class SkillManageResultVO extends ExecuteResult {
    private String action;
    private Long skillId;
    private String name;
    private String description;
    private String preview;
    /** patch 失败时回带大纲，帮助模型定位正确标题。 */
    private List<HeadingInfo> headings;
    private Integer totalLines;
}
