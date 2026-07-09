package com.stephanofer.partySystem.follow;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.UUID;

public final class MovementCauseTracker {

    private static final String PARTY_FOLLOW = "PARTY_FOLLOW";
    private final Cache<UUID, String> causes;

    public MovementCauseTracker(Duration ttl) {
        this.causes = Caffeine.newBuilder().expireAfterWrite(ttl).build();
    }

    public void markPartyFollow(UUID uuid) {
        this.causes.put(uuid, PARTY_FOLLOW);
    }

    public boolean causedByPartySystem(UUID uuid) {
        return PARTY_FOLLOW.equals(this.causes.getIfPresent(uuid));
    }
}
