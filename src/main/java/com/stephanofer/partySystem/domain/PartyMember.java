package com.stephanofer.partySystem.domain;

import java.time.Instant;
import java.util.UUID;

public record PartyMember(
    UUID uuid,
    String username,
    String prefix,
    String primaryGroup,
    PartyRole role,
    PartyMemberState state,
    boolean transferable,
    String serverId,
    Instant joinedAt
) {
    public PartyMember withRole(PartyRole role) {
        return new PartyMember(this.uuid, this.username, this.prefix, this.primaryGroup, role, this.state, this.transferable, this.serverId, this.joinedAt);
    }

    public PartyMember withState(PartyMemberState state, boolean transferable, String serverId) {
        return new PartyMember(this.uuid, this.username, this.prefix, this.primaryGroup, this.role, state, transferable, serverId, this.joinedAt);
    }

    public PartyMember withIdentity(PlayerIdentity identity) {
        return new PartyMember(this.uuid, identity.username(), identity.prefix(), identity.primaryGroup(), this.role, this.state, this.transferable, this.serverId, this.joinedAt);
    }
}
