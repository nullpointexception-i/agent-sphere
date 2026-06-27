package com.buukle.agent.capability.builtin.tool.docwrite.dtvo.vo;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
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
