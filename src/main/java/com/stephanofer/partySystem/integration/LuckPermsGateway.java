package com.stephanofer.partySystem.integration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stephanofer.partySystem.config.PluginConfig;
import com.velocitypowered.api.proxy.Player;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.slf4j.Logger;

public final class LuckPermsGateway {

    private final Logger logger;
    private final LuckPerms luckPerms;
    private final PluginConfig config;
    private final Cache<UUID, LuckPermsSnapshot> snapshotCache;

    public LuckPermsGateway(Logger logger, PluginConfig config) {
        this.logger = logger;
        this.config = config;
        LuckPerms resolved = null;
        try {
            resolved = LuckPermsProvider.get();
        } catch (IllegalStateException exception) {
            this.logger.warn("LuckPerms is not available. PartySystem prefixes will use empty fallback.");
        }
        this.luckPerms = resolved;
        this.snapshotCache = Caffeine.newBuilder().expireAfterWrite(config.cache().luckPermsSnapshotTtl()).build();
    }

    public CompletableFuture<LuckPermsSnapshot> snapshot(Player player) {
        LuckPermsSnapshot cached = this.snapshotCache.getIfPresent(player.getUniqueId());
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        if (this.luckPerms == null) {
            return CompletableFuture.completedFuture(LuckPermsSnapshot.empty());
        }
        User user = this.luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user != null) {
            LuckPermsSnapshot snapshot = snapshot(user);
            this.snapshotCache.put(player.getUniqueId(), snapshot);
            return CompletableFuture.completedFuture(snapshot);
        }
        return this.loadOfflineSnapshot(player.getUniqueId());
    }

    public CompletableFuture<LuckPermsSnapshot> loadOfflineSnapshot(UUID uuid) {
        LuckPermsSnapshot cached = this.snapshotCache.getIfPresent(uuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        if (this.luckPerms == null) {
            return CompletableFuture.completedFuture(LuckPermsSnapshot.empty());
        }
        return this.luckPerms.getUserManager().loadUser(uuid).thenApply(user -> {
            LuckPermsSnapshot snapshot = snapshot(user);
            this.snapshotCache.put(uuid, snapshot);
            return snapshot;
        }).exceptionally(throwable -> {
            this.logger.warn("Unable to resolve LuckPerms snapshot for {}. Using empty prefix.", uuid, throwable);
            return LuckPermsSnapshot.empty();
        });
    }

    public void invalidate(UUID uuid) {
        this.snapshotCache.invalidate(uuid);
    }

    public int partyLimit(Player player) {
        return this.playerLimit(player, this.config.limits().partySize());
    }

    public CompletableFuture<Integer> partyLimit(UUID uuid) {
        return this.userLimit(uuid, this.config.limits().partySize());
    }

    private static LuckPermsSnapshot snapshot(User user) {
        var meta = user.getCachedData().getMetaData();
        return new LuckPermsSnapshot(nullToEmpty(meta.getPrefix()), nullToEmpty(user.getPrimaryGroup()));
    }

    private int playerLimit(Player player, PluginConfig.LimitGroup group) {
        int best = group.defaultLimit();
        for (PluginConfig.PermissionLimit permission : group.permissions()) {
            if (permission.limit() > best && player.hasPermission(permission.permission())) {
                best = permission.limit();
            }
        }
        return best;
    }

    private CompletableFuture<Integer> userLimit(UUID uuid, PluginConfig.LimitGroup group) {
        if (this.luckPerms == null) {
            return CompletableFuture.completedFuture(group.defaultLimit());
        }
        return this.luckPerms.getUserManager().loadUser(uuid).thenApply(user -> {
            int best = group.defaultLimit();
            var permissions = user.getCachedData().getPermissionData(user.getQueryOptions());
            for (PluginConfig.PermissionLimit permission : group.permissions()) {
                if (permission.limit() > best && permissions.checkPermission(permission.permission()).asBoolean()) {
                    best = permission.limit();
                }
            }
            return best;
        }).exceptionally(throwable -> {
            this.logger.warn("Unable to resolve LuckPerms party limit for {}. Using default limit.", uuid, throwable);
            return group.defaultLimit();
        });
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
