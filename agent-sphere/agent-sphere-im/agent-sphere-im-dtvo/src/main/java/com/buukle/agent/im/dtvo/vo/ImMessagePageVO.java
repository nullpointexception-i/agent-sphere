package com.buukle.agent.im.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** Keyset page for IM message history. */
@Data
public class ImMessagePageVO implements Serializable {
    private List<MessageVO> rows;
    private Boolean hasMore;
    /** Oldest seq in {@code rows}; pass as {@code beforeSeq} to load older messages. */
    private Long nextBeforeSeq;
    /** Newest seq in {@code rows}; pass as {@code afterSeq} to load newer messages. */
    private Long nextAfterSeq;
}
