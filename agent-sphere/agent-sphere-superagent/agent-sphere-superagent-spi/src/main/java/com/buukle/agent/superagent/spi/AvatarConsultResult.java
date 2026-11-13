package com.buukle.agent.superagent.spi;

/** Answer returned by an avatar consultation. */
public record AvatarConsultResult(boolean ok, String answer, String error) {

    public static AvatarConsultResult ok(String answer) {
        return new AvatarConsultResult(true, answer, null);
    }

    public static AvatarConsultResult failed(String error) {
        return new AvatarConsultResult(false, null, error);
    }
}
