package com.buukle.agent.superagent.dtvo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** Group Router 的严格 JSON 输出。 */
@Data
public class GroupRouterDecision implements Serializable {

    private List<Deliver> deliver;
    private List<Ignore> ignore;
    private String summary;

    @Data
    public static class Deliver implements Serializable {
        private Long agentId;
        private String reason;
    }

    @Data
    public static class Ignore implements Serializable {
        private Long agentId;
        private String reason;
    }
}
