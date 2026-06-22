package com.buukle.agent.runtime.orchestration.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

@Data
public class CopilotThreadsResponseVO implements Serializable {
    private List<?> threads = Collections.emptyList();
}
