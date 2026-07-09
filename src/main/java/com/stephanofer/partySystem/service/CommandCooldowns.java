package com.stephanofer.partySystem.service;

import com.stephanofer.partySystem.config.PluginConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CommandCooldowns {

    private final PluginConfig.Cooldowns config;
    private final Map<String, Instant> lastUse = new ConcurrentHashMap<>();

    public CommandCooldowns(PluginConfig.Cooldowns config) {
        this.config = config;
    }

    public Duration invite(UUID uuid) {
        return this.remaining(uuid, "invite", this.config.invite());
    }

    public Duration list(UUID uuid) {
        return this.remaining(uuid, "list", this.config.list());
    }

    public Duration chat(UUID uuid) {
        return this.remaining(uuid, "chat", this.config.chat());
    }

    public Duration toggleChat(UUID uuid) {
        return this.remaining(uuid, "togglechat", this.config.togglechat());
    }

    private Duration remaining(UUID uuid, String action, Duration cooldown) {
        if (cooldown.isZero() || cooldown.isNegative()) {
            return Duration.ZERO;
        }
        String key = uuid + ":" + action;
        Instant now = Instant.now();
        Instant last = this.lastUse.get(key);
        if (last != null) {
            Duration elapsed = Duration.between(last, now);
            if (elapsed.compareTo(cooldown) < 0) {
                return cooldown.minus(elapsed);
            }
        }
        this.lastUse.put(key, now);
        return Duration.ZERO;
    }
}
