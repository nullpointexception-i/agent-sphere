package com.buukle.agent.superagent.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Structured work item sent to a desktop runtime. Unlike the cloud router, the local
 * driver has no recall_event tool, so the transcript is carried directly.
 */
@Data
public class LocalTaskPayloadVO implements Serializable {

    private Long taskId;
    private Long agentId;
    private String agentName;
    private ThreadInfo thread;
    private Persona persona;
    private Trigger trigger;
    private List<Message> messages;
    private Reply reply;
    private String sessionRef;
    private String deadlineAt;

    @Data
    public static class ThreadInfo implements Serializable {
        private Long id;
        private String title;
        private String summary;
    }

    @Data
    public static class Persona implements Serializable {
        private String position;
        private String description;
        private String instructions;
    }

    @Data
    public static class Trigger implements Serializable {
        private List<Long> deliveryIds;
        private List<Long> eventIds;
        private String reason;
        private Long seqFrom;
        private Long seqTo;
    }

    @Data
    public static class Message implements Serializable {
        private Long seq;
        private String speaker;
        private String speakerType;
        private String text;
        private String at;
    }

    @Data
    public static class Reply implements Serializable {
        private Integer maxChars;
        private String format;
    }
}
