package com.buukle.agent.capability.builtin.dtvo.enums;

public enum BuiltinToolEnum {
    WEB_SEARCH(2),
    TODOWRITE(3),
    WEB_READ(4),
    CHROME(5),
    DOCWRITE(6);

    private final int id;

    BuiltinToolEnum(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }
}

