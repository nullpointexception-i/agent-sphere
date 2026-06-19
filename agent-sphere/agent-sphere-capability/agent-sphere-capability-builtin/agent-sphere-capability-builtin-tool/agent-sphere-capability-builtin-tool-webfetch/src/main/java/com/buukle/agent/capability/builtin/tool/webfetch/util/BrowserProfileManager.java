package com.buukle.agent.capability.builtin.tool.webfetch.util;

import org.springframework.stereotype.Component;

@Component
public class BrowserProfileManager {

    private BrowserProfile current;

    public synchronized BrowserProfile next() {
        current = BrowserProfile.random();
        return current;
    }

    public synchronized BrowserProfile nextDifferent() {
        BrowserProfile next = BrowserProfile.randomDifferent(current);
        current = next;
        return current;
    }
}
