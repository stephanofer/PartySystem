package com.stephanofer.partySystem.domain;

import java.time.Instant;
import java.util.UUID;

public record PartyInvite(
    UUID senderUuid,
    String senderName,
    String senderPrefix,
    UUID targetUuid,
    String targetName,
    String targetPrefix,
    Instant createdAt,
    Instant expiresAt
) {
    public boolean expired(Instant now) {
        return !this.expiresAt.isAfter(now);
    }
}
