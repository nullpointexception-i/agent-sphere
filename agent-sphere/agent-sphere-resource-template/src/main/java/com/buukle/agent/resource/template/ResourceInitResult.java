package com.buukle.agent.resource.template;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 资源模板初始化汇总。 */
@Data
public class ResourceInitResult {
    private int created;
    private int skipped;
    private int failed;
    private List<String> failedDetails = new ArrayList<>();
    private List<String> unknownTypes = new ArrayList<>();

    public void created() {
        created++;
    }

    public void skipped() {
        skipped++;
    }

    public void failed(String detail) {
        failed++;
        failedDetails.add(detail);
    }

    public void unknown(String type) {
        unknownTypes.add(type);
    }
}
