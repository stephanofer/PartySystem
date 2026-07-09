package com.stephanofer.partySystem.service;

import com.stephanofer.partySystem.config.PluginConfig;
import com.stephanofer.partySystem.debug.DebugLogger;
import com.stephanofer.partySystem.domain.Party;
import com.stephanofer.partySystem.domain.PartyMember;
import com.stephanofer.partySystem.follow.FollowDestinationRegistry;
import com.stephanofer.partySystem.follow.MovementCauseTracker;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Map;

public final class PartyFollowService {

    private final ProxyServer server;
    private final PluginConfig config;
    private final PartyService parties;
    private final FollowDestinationRegistry destinations;
    private final MovementCauseTracker movementCauses;
    private final DebugLogger debug;

    public PartyFollowService(
        ProxyServer server,
        PluginConfig config,
        PartyService parties,
        FollowDestinationRegistry destinations,
        MovementCauseTracker movementCauses,
        DebugLogger debug
    ) {
        this.server = server;
        this.config = config;
        this.parties = parties;
        this.destinations = destinations;
        this.movementCauses = movementCauses;
        this.debug = debug;
    }

    public void handleServerConnected(Player player, String targetServerId) {
        this.parties.updateServer(player, targetServerId);
        if (!this.config.follow().enabled()) {
            return;
        }
        if (this.movementCauses.consumePartyFollow(player.getUniqueId(), targetServerId)) {
            this.debug.follow("Party follow skipped", Map.of(
                "player", player.getUsername(),
                "target", targetServerId,
                "reason", "movement-caused-by-party-system"
            ));
            return;
        }
        if (!this.destinations.followAllowed(targetServerId)) {
            this.debug.follow("Party follow skipped", Map.of(
                "player", player.getUsername(),
                "target", targetServerId,
                "reason", "destination-not-allowed"
            ));
            return;
        }
        Party party = this.parties.party(player.getUniqueId()).orElse(null);
        if (party == null || !party.leader(player.getUniqueId())) {
            return;
        }
        this.server.getServer(targetServerId).ifPresent(targetServer -> {
            for (PartyMember member : party.members()) {
                if (member.uuid().equals(player.getUniqueId())) {
                    continue;
                }
                if (!member.transferable()) {
                    this.debug.follow("Party follow skipped", Map.of("player", member.username(), "target", targetServerId, "reason", "member-not-transferable"));
                    continue;
                }
                this.server.getPlayer(member.uuid()).ifPresentOrElse(follower -> {
                    String current = follower.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
                    if (current.equalsIgnoreCase(targetServerId)) {
                        this.debug.follow("Party follow skipped", Map.of("player", follower.getUsername(), "target", targetServerId, "reason", "already-at-destination"));
                        return;
                    }
                    this.movementCauses.markPartyFollow(follower.getUniqueId(), targetServerId);
                    follower.createConnectionRequest(targetServer).connect().thenAccept(result -> {
                        if (!result.isSuccessful()) {
                            this.movementCauses.clearPartyFollow(follower.getUniqueId(), targetServerId);
                        }
                        this.debug.follow("Party follower moved", Map.of(
                            "player", follower.getUsername(),
                            "target", targetServerId,
                            "success", Boolean.toString(result.isSuccessful()),
                            "status", result.getStatus().name()
                        ));
                    });
                }, () -> this.debug.follow("Party follow skipped", Map.of("player", member.username(), "target", targetServerId, "reason", "member-offline")));
            }
        });
    }
}
