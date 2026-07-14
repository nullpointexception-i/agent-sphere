package com.buukle.agent.instance.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class RunActivityListVO implements Serializable {
    private int total;
    private List<RunActivityVO> records;

    public RunActivityListVO() {
    }

    public RunActivityListVO(int total, List<RunActivityVO> records) {
        this.total = total;
        this.records = records;
    }
}
