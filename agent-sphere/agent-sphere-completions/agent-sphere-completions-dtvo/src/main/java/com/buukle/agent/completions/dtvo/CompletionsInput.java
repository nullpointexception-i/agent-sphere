package com.buukle.agent.completions.dtvo;

import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Input of a single completions call: input is arbitrary JSON matching input_schema, so it is carried as a dynamic field bag.
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
