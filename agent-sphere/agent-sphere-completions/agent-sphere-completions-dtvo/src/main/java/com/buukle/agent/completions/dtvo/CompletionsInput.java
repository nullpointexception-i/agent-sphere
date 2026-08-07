package com.buukle.agent.completions.dtvo;

import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * completions 单次调用入参：input 与 input_schema 匹配的任意 JSON，故以动态字段袋子承载。
 */
@Data
public class CompletionsInput implements Serializable {
    private Map<String, Object> values;

    public static CompletionsInput of(Map<String, Object> input) {
        CompletionsInput vo = new CompletionsInput();
        vo.setValues(input == null ? new LinkedHashMap<>() : input);
        return vo;
    }
}
