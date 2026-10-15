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
    /** 目标字符串 B 是否包含子串 A。 */
    private boolean contains;
}
