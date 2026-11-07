package com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.vo;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.vo.HeadingInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

/** Output of skill management (builtin_9). */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class SkillManageResultVO extends ExecuteResult {
    private String action;
    private Long skillId;
    private String name;
    private String description;
    private String preview;
    /** Outline returned when a patch fails, to help the model locate the correct heading. */
    private List<HeadingInfo> headings;
    private Integer totalLines;
}
