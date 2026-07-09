package com.stephanofer.partySystem.follow;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

public final class MovementCauseTracker {

    private final Cache<UUID, String> causes;

    public MovementCauseTracker(Duration ttl) {
        this.causes = Caffeine.newBuilder().expireAfterWrite(ttl).build();
    }

    public void markPartyFollow(UUID uuid, String targetServerId) {
        this.causes.put(uuid, normalize(targetServerId));
    }

    public boolean consumePartyFollow(UUID uuid, String targetServerId) {
        String expectedTarget = this.causes.getIfPresent(uuid);
        if (!normalize(targetServerId).equals(expectedTarget)) {
            return false;
        }
        this.causes.invalidate(uuid);
        return true;
    }

    public void clearPartyFollow(UUID uuid, String targetServerId) {
        String expectedTarget = this.causes.getIfPresent(uuid);
        if (normalize(targetServerId).equals(expectedTarget)) {
            this.causes.invalidate(uuid);
        }
    }

    private static String normalize(String serverId) {
        return serverId == null ? "" : serverId.trim().toLowerCase(Locale.ROOT);
    }
}
