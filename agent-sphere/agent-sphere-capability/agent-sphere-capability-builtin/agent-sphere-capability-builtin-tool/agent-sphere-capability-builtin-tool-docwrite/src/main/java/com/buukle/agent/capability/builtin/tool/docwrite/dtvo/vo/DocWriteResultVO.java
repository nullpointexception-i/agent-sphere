package com.buukle.agent.capability.builtin.tool.docwrite.dtvo.vo;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.vo.HeadingInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class DocWriteResultVO extends ExecuteResult {
    private Long documentId;
    private String title;
    /** 文档内容版本（V108；随结果返回给时间线展示）。 */
    private Integer version;
    private String action;
    private String preview;
    private String content;
    private List<DocReadSummaryVO> documents;
    private Integer total;
    private List<HeadingInfo> headings;
    private Integer totalLines;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocReadSummaryVO {
        private Long documentId;
        private String title;
        private String preview;
        private String createdAt;
    }
}
