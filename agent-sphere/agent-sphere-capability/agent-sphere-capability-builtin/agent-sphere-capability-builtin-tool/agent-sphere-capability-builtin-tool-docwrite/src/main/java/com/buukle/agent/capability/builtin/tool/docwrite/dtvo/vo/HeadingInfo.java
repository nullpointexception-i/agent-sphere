package com.buukle.agent.capability.builtin.tool.docwrite.dtvo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HeadingInfo {
    private int line;
    private int level;
    private String text;
}
