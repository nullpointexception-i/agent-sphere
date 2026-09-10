package com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.vo;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.vo.HeadingInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class SkillWriteResultVO extends ExecuteResult {
    private String action;
    private Long skillId;
    private String name;
    private String description;
    private String preview;
    private String content;
    private List<SkillSummaryVO> skills;
    private List<HeadingInfo> headings;
    private Integer totalLines;
    private Integer total;

    @Data
    @NoArgsConstructor
    public static class SkillSummaryVO {
        private Long skillId;
        private String name;
        private String description;
        private String status;
        private String createdAt;
    }
}