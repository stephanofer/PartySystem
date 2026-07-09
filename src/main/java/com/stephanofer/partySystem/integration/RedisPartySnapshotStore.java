package com.stephanofer.partySystem.integration;

import com.google.gson.Gson;
import com.hera.craftkit.redis.RedisClient;
import com.stephanofer.partySystem.config.PluginConfig;
import com.stephanofer.partySystem.debug.DebugLogger;
import com.stephanofer.partySystem.domain.Party;
import com.stephanofer.partySystem.domain.PartyMember;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class RedisPartySnapshotStore {

    private final RedisClient redis;
    private final PluginConfig config;
    private final DebugLogger debug;
    private final Gson gson = new Gson();

    public RedisPartySnapshotStore(RedisClient redis, PluginConfig config, DebugLogger debug) {
        this.redis = redis;
        this.config = config;
        this.debug = debug;
    }

    public CompletableFuture<Void> publish(Party party) {
        return this.publish(party, this.config.limits().partySize().defaultLimit());
    }

    public CompletableFuture<Void> publish(Party party, int maxSize) {
        PartySnapshot snapshot = PartySnapshot.from(party, maxSize);
        String payload = this.gson.toJson(snapshot);
        String partyKey = this.partyKey(party.id());
        String versionKey = this.versionKey(party.id());
        CompletableFuture<Boolean> partyWrite = this.redis.cache().set(partyKey, payload, this.config.snapshots().ttl());
        CompletableFuture<Boolean> versionWrite = this.redis.cache().set(versionKey, String.valueOf(party.version()), this.config.snapshots().ttl());
        List<CompletableFuture<Boolean>> playerWrites = party.members().stream()
            .map(member -> this.redis.cache().set(this.playerKey(member.uuid()), party.id(), this.config.snapshots().ttl()))
            .toList();
        return CompletableFuture.allOf(playerWrites.toArray(CompletableFuture[]::new))
            .thenCombine(partyWrite, (ignored, stored) -> stored)
            .thenCombine(versionWrite, (left, right) -> left && right)
            .thenAccept(stored -> this.debug.snapshots("Party snapshot published", java.util.Map.of(
                "party", party.id(),
                "version", String.valueOf(party.version()),
                "stored", Boolean.toString(stored)
            )))
            .exceptionally(throwable -> {
                this.debug.redis("Party snapshot publish failed", java.util.Map.of("party", party.id(), "reason", "redis-write-failed"), throwable);
                return null;
            });
    }

    public CompletableFuture<Void> delete(Party party) {
        String[] keys = party.members().stream()
            .map(member -> this.playerKey(member.uuid()))
            .toList()
            .toArray(String[]::new);
        String[] allKeys = new String[keys.length + 2];
        System.arraycopy(keys, 0, allKeys, 0, keys.length);
        allKeys[allKeys.length - 2] = this.partyKey(party.id());
        allKeys[allKeys.length - 1] = this.versionKey(party.id());
        return this.redis.cache().unlink(allKeys)
            .thenAccept(count -> this.debug.snapshots("Party snapshot deleted", java.util.Map.of("party", party.id(), "keys", String.valueOf(count))))
            .exceptionally(throwable -> {
                this.debug.redis("Party snapshot delete failed", java.util.Map.of("party", party.id(), "reason", "redis-delete-failed"), throwable);
                return null;
            });
    }

    public CompletableFuture<Void> deletePlayer(UUID playerId) {
        return this.redis.cache().delete(this.playerKey(playerId)).thenApply(deleted -> null);
    }

    private String playerKey(UUID uuid) {
        return this.redis.key("party", "player", uuid.toString());
    }

    private String partyKey(String partyId) {
        return this.redis.key("party", "snapshot", partyId);
    }

    private String versionKey(String partyId) {
        return this.redis.key("party", "version", partyId);
    }

    private record PartySnapshot(String partyId, String leaderId, int maxSize, List<MemberSnapshot> members, String createdAt, String updatedAt, long version) {
        private static PartySnapshot from(Party party, int maxSize) {
            return new PartySnapshot(
                party.id(),
                party.leaderId().toString(),
                maxSize,
                party.members().stream().map(MemberSnapshot::from).toList(),
                party.createdAt().toString(),
                party.updatedAt().toString(),
                party.version()
            );
        }
    }

    private record MemberSnapshot(String playerId, String role, String state, boolean transferable, String serverId) {
        private static MemberSnapshot from(PartyMember member) {
            return new MemberSnapshot(member.uuid().toString(), member.role().name(), member.state().name(), member.transferable(), member.serverId());
        }
    }
}
