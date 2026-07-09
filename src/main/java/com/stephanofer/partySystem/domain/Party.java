package com.stephanofer.partySystem.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class Party {

    private final String id;
    private UUID leaderId;
    private final LinkedHashMap<UUID, PartyMember> members = new LinkedHashMap<>();
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    public Party(String id, PartyMember leader, Instant now) {
        this.id = id;
        this.leaderId = leader.uuid();
        this.members.put(leader.uuid(), leader.withRole(PartyRole.LEADER));
        this.createdAt = now;
        this.updatedAt = now;
        this.version = 1;
    }

    public String id() {
        return this.id;
    }

    public UUID leaderId() {
        return this.leaderId;
    }

    public Instant createdAt() {
        return this.createdAt;
    }

    public Instant updatedAt() {
        return this.updatedAt;
    }

    public long version() {
        return this.version;
    }

    public List<PartyMember> members() {
        return List.copyOf(this.members.values());
    }

    public Optional<PartyMember> member(UUID uuid) {
        return Optional.ofNullable(this.members.get(uuid));
    }

    public boolean contains(UUID uuid) {
        return this.members.containsKey(uuid);
    }

    public int size() {
        return this.members.size();
    }

    public void addMember(PartyMember member, Instant now) {
        this.members.put(member.uuid(), member.withRole(PartyRole.MEMBER));
        this.touch(now);
    }

    public Optional<PartyMember> removeMember(UUID uuid, Instant now) {
        PartyMember removed = this.members.remove(uuid);
        if (removed != null) {
            this.touch(now);
        }
        return Optional.ofNullable(removed);
    }

    public void updateMember(PartyMember member, Instant now) {
        if (this.members.containsKey(member.uuid())) {
            this.members.put(member.uuid(), member);
            this.touch(now);
        }
    }

    public void transferLeader(UUID newLeaderId, Instant now) {
        PartyMember oldLeader = this.members.get(this.leaderId);
        PartyMember newLeader = this.members.get(newLeaderId);
        if (newLeader == null) {
            return;
        }
        if (oldLeader != null) {
            this.members.put(oldLeader.uuid(), oldLeader.withRole(PartyRole.MEMBER));
        }
        this.members.put(newLeader.uuid(), newLeader.withRole(PartyRole.LEADER));
        this.leaderId = newLeaderId;
        this.touch(now);
    }

    public Optional<PartyMember> oldestOnlineMemberExcept(UUID excluded) {
        return this.members.values().stream()
            .filter(member -> !member.uuid().equals(excluded))
            .filter(member -> member.state() == PartyMemberState.ONLINE)
            .min(Comparator.comparing(PartyMember::joinedAt));
    }

    public boolean leader(UUID uuid) {
        return this.leaderId.equals(uuid);
    }

    public Map<UUID, PartyMember> memberMap() {
        return Map.copyOf(this.members);
    }

    private void touch(Instant now) {
        this.updatedAt = now;
        this.version++;
    }
}
