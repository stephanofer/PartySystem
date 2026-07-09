package com.stephanofer.partySystem.domain;

import java.util.UUID;

public record PlayerIdentity(UUID uuid, String username, String prefix, String primaryGroup) {
    public static PlayerIdentity simple(UUID uuid, String username) {
        return new PlayerIdentity(uuid, username, "", "");
    }
}
