package com.buukle.agent.capability.builtin.tool.textcontains.dtvo.vo;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class TextContainsResultVO extends ExecuteResult {
    /** Whether target string B contains substring A. */
    private boolean contains;
}
