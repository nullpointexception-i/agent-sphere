package com.buukle.agent.instance.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class RunActivityListVO implements Serializable {
    private int total;
    private List<RunActivitySummaryVO> records;

    public RunActivityListVO() {
    }

    public RunActivityListVO(int total, List<RunActivitySummaryVO> records) {
        this.total = total;
        this.records = records;
    }
}
