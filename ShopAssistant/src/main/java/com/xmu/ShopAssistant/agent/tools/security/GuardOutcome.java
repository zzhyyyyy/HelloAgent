package com.xmu.ShopAssistant.agent.tools.security;

public record GuardOutcome(boolean allowed, String message) {

    public static GuardOutcome allow() {
        return new GuardOutcome(true, null);
    }

    public static GuardOutcome deny(String message) {
        return new GuardOutcome(false, message);
    }
}
