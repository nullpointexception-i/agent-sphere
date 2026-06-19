package com.buukle.agent.runtime.kernel.port.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeEventVO {
    private EventType eventType;
    private RuntimeEventDataVO data;
}
