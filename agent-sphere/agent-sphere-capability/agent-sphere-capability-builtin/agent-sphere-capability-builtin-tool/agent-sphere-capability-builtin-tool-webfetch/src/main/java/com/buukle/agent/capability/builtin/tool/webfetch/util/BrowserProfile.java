package com.buukle.agent.capability.builtin.tool.webfetch.util;

import java.util.LinkedHashMap;
import java.util.Map;

public record BrowserProfile(String userAgent, Map<String, String> headers) {

    private static final BrowserProfile[] PROFILES = new BrowserProfile[]{
            chromeWin(), chromeMac(), edgeWin(), firefoxWin(), safariMac()
    };

    public static BrowserProfile chromeWin() {
        Map<String, String> h = baseHeaders();
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36");
        h.put("Sec-Ch-Ua", "\"Google Chrome\";v=\"125\", \"Chromium\";v=\"125\", \"Not.A/Brand\";v=\"24\"");
        h.put("Sec-Ch-Ua-Mobile", "?0");
        h.put("Sec-Ch-Ua-Platform", "\"Windows\"");
        return new BrowserProfile(h.get("User-Agent"), h);
    }

    public static BrowserProfile chromeMac() {
        Map<String, String> h = baseHeaders();
        h.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36");
        h.put("Sec-Ch-Ua", "\"Google Chrome\";v=\"125\", \"Chromium\";v=\"125\", \"Not.A/Brand\";v=\"24\"");
        h.put("Sec-Ch-Ua-Mobile", "?0");
        h.put("Sec-Ch-Ua-Platform", "\"macOS\"");
        return new BrowserProfile(h.get("User-Agent"), h);
    }

    public static BrowserProfile edgeWin() {
        Map<String, String> h = baseHeaders();
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 Edg/125.0.0.0");
        h.put("Sec-Ch-Ua", "\"Microsoft Edge\";v=\"125\", \"Chromium\";v=\"125\", \"Not.A/Brand\";v=\"24\"");
        h.put("Sec-Ch-Ua-Mobile", "?0");
        h.put("Sec-Ch-Ua-Platform", "\"Windows\"");
        return new BrowserProfile(h.get("User-Agent"), h);
    }

    public static BrowserProfile firefoxWin() {
        Map<String, String> h = baseHeaders();
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0");
        h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7");
        h.put("Upgrade-Insecure-Requests", "1");
        h.put("Sec-Fetch-Dest", "document");
        h.put("Sec-Fetch-Mode", "navigate");
        h.put("Sec-Fetch-Site", "none");
        h.put("Sec-Fetch-User", "?1");
        return new BrowserProfile(h.get("User-Agent"), h);
    }

    public static BrowserProfile safariMac() {
        Map<String, String> h = baseHeaders();
        h.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15");
        h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        h.put("Accept-Language", "zh-CN,zh-Hans;q=0.9");
        h.put("Sec-Fetch-Dest", "document");
        h.put("Sec-Fetch-Mode", "navigate");
        h.put("Sec-Fetch-Site", "none");
        h.put("Referer", "https://www.google.com/");
        return new BrowserProfile(h.get("User-Agent"), h);
    }

    private static Map<String, String> baseHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        h.put("Accept-Encoding", "gzip, deflate, br");
        h.put("Cache-Control", "no-cache");
        h.put("Pragma", "no-cache");
        h.put("Upgrade-Insecure-Requests", "1");
        h.put("Sec-Fetch-Dest", "document");
        h.put("Sec-Fetch-Mode", "navigate");
        h.put("Sec-Fetch-Site", "none");
        h.put("Sec-Fetch-User", "?1");
        h.put("DNT", "1");
        return h;
    }

    public static BrowserProfile random() {
        return PROFILES[(int) (Math.random() * PROFILES.length)];
    }

    public static BrowserProfile randomDifferent(BrowserProfile current) {
        BrowserProfile next;
        do {
            next = random();
        } while (next == current && PROFILES.length > 1);
        return next;
    }

    public void applyTo(Map<String, String> target) {
        target.putAll(headers);
    }
}
