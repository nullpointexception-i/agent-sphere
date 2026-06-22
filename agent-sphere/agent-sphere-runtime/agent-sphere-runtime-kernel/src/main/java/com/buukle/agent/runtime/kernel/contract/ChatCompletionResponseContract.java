package com.buukle.agent.runtime.kernel.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ChatCompletionResponseContract implements Serializable {
    @JsonProperty("choices")
    private List<ChoiceContract> choiceContracts;
}
