package com.stephanofer.partySystem.service;

import com.stephanofer.partySystem.config.Language;
import com.stephanofer.partySystem.config.Messages;
import com.stephanofer.partySystem.config.PluginConfig;
import com.stephanofer.partySystem.debug.DebugLogger;
import com.stephanofer.partySystem.domain.Party;
import com.stephanofer.partySystem.domain.PartyInvite;
import com.stephanofer.partySystem.domain.PartyMember;
import com.stephanofer.partySystem.domain.PartyMemberState;
import com.stephanofer.partySystem.domain.PartyRole;
import com.stephanofer.partySystem.domain.PlayerIdentity;
import com.stephanofer.partySystem.integration.LuckPermsGateway;
import com.stephanofer.partySystem.integration.ProxySettingsGateway;
import com.stephanofer.partySystem.integration.RedisPartySnapshotStore;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

public final class PartyService {

    private final ProxyServer server;
    private final PluginConfig config;
    private final Messages messages;
    private final ProxySettingsGateway proxySettings;
    private final LuckPermsGateway luckPerms;
    private final RedisPartySnapshotStore snapshots;
    private final FeedbackService feedback;
    private final DebugLogger debug;
    private final CommandCooldowns cooldowns;
    private PartyChatService chat;

    private final Map<String, Party> partyById = new ConcurrentHashMap<>();
    private final Map<UUID, String> partyIdByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, List<PartyInvite>> incomingInvitesByTarget = new ConcurrentHashMap<>();
    private final Map<UUID, List<PartyInvite>> outgoingInvitesBySender = new ConcurrentHashMap<>();

    public PartyService(
        ProxyServer server,
        PluginConfig config,
        Messages messages,
        ProxySettingsGateway proxySettings,
        LuckPermsGateway luckPerms,
        RedisPartySnapshotStore snapshots,
        FeedbackService feedback,
        DebugLogger debug,
        CommandCooldowns cooldowns
    ) {
        this.server = server;
        this.config = config;
        this.messages = messages;
        this.proxySettings = proxySettings;
        this.luckPerms = luckPerms;
        this.snapshots = snapshots;
        this.feedback = feedback;
        this.debug = debug;
        this.cooldowns = cooldowns;
    }

    public void attachChat(PartyChatService chat) {
        this.chat = chat;
    }

    public Optional<Party> party(UUID playerId) {
        String partyId = this.partyIdByPlayer.get(playerId);
        return partyId == null ? Optional.empty() : Optional.ofNullable(this.partyById.get(partyId));
    }

    public List<PartyInvite> incomingInvites(UUID target) {
        this.cleanupExpiredInvites();
        return List.copyOf(this.incomingInvitesByTarget.getOrDefault(target, List.of()));
    }

    public List<PartyInvite> outgoingInvites(UUID sender) {
        this.cleanupExpiredInvites();
        return List.copyOf(this.outgoingInvitesBySender.getOrDefault(sender, List.of()));
    }

    public void invite(Player sender, String targetName) {
        Language language = this.proxySettings.language(sender);
        Duration remaining = this.cooldowns.invite(sender.getUniqueId());
        if (!remaining.isZero()) {
            sendCooldown(sender, language, remaining);
            return;
        }
        Optional<Player> targetOptional = this.server.getPlayer(targetName);
        if (targetOptional.isEmpty()) {
            send(sender, language, "player.not-found", "player_name", targetName);
            return;
        }
        Player target = targetOptional.get();
        if (target.getUniqueId().equals(sender.getUniqueId())) {
            send(sender, language, "player.self");
            return;
        }
        synchronized (this) {
            Party party = this.party(sender.getUniqueId()).orElse(null);
            if (party != null && !party.leader(sender.getUniqueId())) {
                send(sender, language, "party.not-leader");
                return;
            }
            int limit = party == null ? this.luckPerms.partyLimit(sender) : this.partyLimit(party);
            if (party != null && party.size() >= limit) {
                send(sender, language, "party.full", "limit", String.valueOf(limit));
                return;
            }
            if (this.partyIdByPlayer.containsKey(target.getUniqueId())) {
                this.sendWithPlayer(sender, language, "party.target-in-party", target.getUniqueId(), target.getUsername(), "");
                return;
            }
            if (this.inviteExists(sender.getUniqueId(), target.getUniqueId())) {
                this.sendWithPlayer(sender, language, "invite.already", target.getUniqueId(), target.getUsername(), "");
                return;
            }
            if (this.inviteExists(target.getUniqueId(), sender.getUniqueId())) {
                this.sendWithPlayer(sender, language, "invite.crossed", target.getUniqueId(), target.getUsername(), "");
                return;
            }
        }
        this.identity(sender).thenCombine(this.identity(target), (senderIdentity, targetIdentity) -> {
            Instant now = Instant.now();
            return new PartyInvite(
                sender.getUniqueId(), sender.getUsername(), senderIdentity.prefix(),
                target.getUniqueId(), target.getUsername(), targetIdentity.prefix(),
                now, now.plus(this.config.party().inviteExpiration())
            );
        }).thenAccept(invite -> {
            synchronized (this) {
                Party party = this.party(sender.getUniqueId()).orElse(null);
                if (party != null && !party.leader(sender.getUniqueId())) {
                    send(sender, language, "party.not-leader");
                    return;
                }
                int limit = party == null ? this.luckPerms.partyLimit(sender) : this.partyLimit(party);
                if (party != null && party.size() >= limit) {
                    send(sender, language, "party.full", "limit", String.valueOf(limit));
                    return;
                }
                if (this.partyIdByPlayer.containsKey(target.getUniqueId())) {
                    this.sendWithPlayer(sender, language, "party.target-in-party", target.getUniqueId(), target.getUsername(), invite.targetPrefix());
                    return;
                }
                if (this.inviteExists(sender.getUniqueId(), target.getUniqueId()) || this.inviteExists(target.getUniqueId(), sender.getUniqueId())) {
                    this.sendWithPlayer(sender, language, "invite.already", target.getUniqueId(), target.getUsername(), invite.targetPrefix());
                    return;
                }
                this.incomingInvitesByTarget.computeIfAbsent(target.getUniqueId(), ignored -> new ArrayList<>()).add(invite);
                this.outgoingInvitesBySender.computeIfAbsent(sender.getUniqueId(), ignored -> new ArrayList<>()).add(invite);
            }
            TagResolver senderResolver = this.proxySettings.playerResolver(sender.getUniqueId(), sender.getUsername(), invite.senderPrefix());
            this.feedback.send(sender, language, "invite-sent", Map.of("player_name", Messages.escape(target.getUsername())), this.proxySettings.playerResolver(target.getUniqueId(), target.getUsername(), invite.targetPrefix()));
            this.feedback.send(target, this.proxySettings.language(target), "invite-received", Map.of(
                "player_name", Messages.escape(sender.getUsername()),
                "accept", "/" + this.config.commands().primary() + " accept " + sender.getUsername(),
                "deny", "/" + this.config.commands().primary() + " deny " + sender.getUsername()
            ), senderResolver);
            this.debug.invites("Party invite created", Map.of("sender", sender.getUsername(), "target", target.getUsername()));
        });
    }

    public void accept(Player target, String senderName) {
        Language language = this.proxySettings.language(target);
        PartyInvite invite;
        synchronized (this) {
            invite = this.findIncoming(target.getUniqueId(), senderName).orElse(null);
            if (invite == null || invite.expired(Instant.now())) {
                this.sendWithPlayer(target, language, "invite.none-incoming", null, senderName, "");
                this.cleanupExpiredInvites();
                return;
            }
            if (this.partyIdByPlayer.containsKey(target.getUniqueId())) {
                send(target, language, "party.already-in-party");
                return;
            }
        }
        Optional<Player> senderOptional = this.server.getPlayer(invite.senderUuid());
        if (senderOptional.isEmpty()) {
            this.removeInvite(invite);
            this.sendWithPlayer(target, language, "invite.none-incoming", invite.senderUuid(), invite.senderName(), invite.senderPrefix());
            return;
        }
        Player sender = senderOptional.get();
        this.member(target, PartyRole.MEMBER).thenAccept(targetMember -> {
            Party party;
            boolean created = false;
            synchronized (this) {
                if (this.partyIdByPlayer.containsKey(target.getUniqueId())) {
                    send(target, language, "party.already-in-party");
                    return;
                }
                party = this.party(sender.getUniqueId()).orElse(null);
                if (party != null && !party.leader(sender.getUniqueId())) {
                    this.removeInvite(invite);
                    this.sendWithPlayer(target, language, "invite.none-incoming", invite.senderUuid(), invite.senderName(), invite.senderPrefix());
                    return;
                }
                int limit = party == null ? this.luckPerms.partyLimit(sender) : this.partyLimit(party);
                if (party != null && party.size() >= limit) {
                    send(target, language, "party.full", "limit", String.valueOf(limit));
                    return;
                }
                if (party == null && limit < 2) {
                    send(target, language, "party.full", "limit", String.valueOf(limit));
                    return;
                }
                if (party == null) {
                    PartyMember senderMember = this.memberFromInviteSender(sender, invite);
                    party = new Party(newPartyId(), senderMember, Instant.now());
                    this.partyById.put(party.id(), party);
                    this.partyIdByPlayer.put(sender.getUniqueId(), party.id());
                    created = true;
                }
                party.addMember(targetMember, Instant.now());
                this.partyIdByPlayer.put(target.getUniqueId(), party.id());
                this.removeInvite(invite);
            }
            this.publishSnapshot(party);
            this.feedback.send(target, language, "invite-accepted", Map.of("player_name", Messages.escape(sender.getUsername())), this.proxySettings.playerResolver(sender.getUniqueId(), sender.getUsername(), invite.senderPrefix()));
            this.feedback.send(sender, this.proxySettings.language(sender), "invite-accepted-target", Map.of("player_name", Messages.escape(target.getUsername())), this.proxySettings.playerResolver(target.getUniqueId(), target.getUsername(), targetMember.prefix()));
            this.notifyParty(party, "member-joined", targetMember, target.getUniqueId());
            this.debug.lifecycle("Party member joined", Map.of("party", party.id(), "player", target.getUsername(), "created", Boolean.toString(created)));
        });
    }

    public void deny(Player target, String senderName) {
        Language language = this.proxySettings.language(target);
        PartyInvite invite;
        synchronized (this) {
            invite = this.findIncoming(target.getUniqueId(), senderName).orElse(null);
            if (invite == null || invite.expired(Instant.now())) {
                this.sendWithPlayer(target, language, "invite.none-incoming", null, senderName, "");
                this.cleanupExpiredInvites();
                return;
            }
            this.removeInvite(invite);
        }
        this.feedback.send(target, language, "invite-denied", Map.of("player_name", Messages.escape(invite.senderName())), this.proxySettings.playerResolver(invite.senderUuid(), invite.senderName(), invite.senderPrefix()));
        this.server.getPlayer(invite.senderUuid()).ifPresent(sender -> this.feedback.send(sender, this.proxySettings.language(sender), "invite-denied-target", Map.of("player_name", Messages.escape(target.getUsername())), this.proxySettings.playerResolver(target.getUniqueId(), target.getUsername(), invite.targetPrefix())));
        this.debug.invites("Party invite denied", Map.of("sender", invite.senderName(), "target", target.getUsername()));
    }

    public void withdraw(Player sender, String targetName) {
        Language language = this.proxySettings.language(sender);
        PartyInvite invite;
        synchronized (this) {
            invite = this.findOutgoing(sender.getUniqueId(), targetName).orElse(null);
            if (invite == null || invite.expired(Instant.now())) {
                this.sendWithPlayer(sender, language, "invite.none-outgoing", null, targetName, "");
                this.cleanupExpiredInvites();
                return;
            }
            this.removeInvite(invite);
        }
        this.feedback.send(sender, language, "invite-withdrawn", Map.of("player_name", Messages.escape(invite.targetName())), this.proxySettings.playerResolver(invite.targetUuid(), invite.targetName(), invite.targetPrefix()));
        this.debug.invites("Party invite withdrawn", Map.of("sender", sender.getUsername(), "target", invite.targetName()));
    }

    public void leave(Player player) {
        Language language = this.proxySettings.language(player);
        Party party;
        PartyMember removed;
        boolean wasLeader;
        synchronized (this) {
            party = this.party(player.getUniqueId()).orElse(null);
            if (party == null) {
                send(player, language, "party.not-in-party");
                return;
            }
            wasLeader = party.leader(player.getUniqueId());
            removed = party.removeMember(player.getUniqueId(), Instant.now()).orElse(null);
            this.partyIdByPlayer.remove(player.getUniqueId());
            if (party.size() <= 1) {
                this.partyById.remove(party.id());
            } else if (wasLeader) {
                party.oldestOnlineMemberExcept(player.getUniqueId()).ifPresent(newLeader -> party.transferLeader(newLeader.uuid(), Instant.now()));
            }
        }
        this.afterMemberRemoved(party, removed, false);
        this.feedback.send(player, language, "party-left", Map.of());
    }

    public void kick(Player leader, String memberName) {
        Language language = this.proxySettings.language(leader);
        Party party;
        PartyMember removed;
        synchronized (this) {
            party = this.party(leader.getUniqueId()).orElse(null);
            if (party == null) {
                send(leader, language, "party.not-in-party");
                return;
            }
            if (!party.leader(leader.getUniqueId())) {
                send(leader, language, "party.not-leader");
                return;
            }
            Optional<PartyMember> target = party.members().stream().filter(member -> member.username().equalsIgnoreCase(memberName)).findFirst();
            if (target.isEmpty() || target.get().uuid().equals(leader.getUniqueId()) || party.leader(target.get().uuid())) {
                this.sendWithPlayer(leader, language, "party.member-not-found", null, memberName, "");
                return;
            }
            removed = party.removeMember(target.get().uuid(), Instant.now()).orElse(null);
            this.partyIdByPlayer.remove(target.get().uuid());
        }
        if (removed != null) {
            this.server.getPlayer(removed.uuid()).ifPresent(player -> this.feedback.send(player, this.proxySettings.language(player), "member-kicked-target", Map.of()));
        }
        this.afterMemberRemoved(party, removed, true);
    }

    public void transfer(Player leader, String memberName) {
        Language language = this.proxySettings.language(leader);
        Party party;
        PartyMember newLeader;
        synchronized (this) {
            party = this.party(leader.getUniqueId()).orElse(null);
            if (party == null) {
                send(leader, language, "party.not-in-party");
                return;
            }
            if (!party.leader(leader.getUniqueId())) {
                send(leader, language, "party.not-leader");
                return;
            }
            newLeader = party.members().stream().filter(member -> member.username().equalsIgnoreCase(memberName)).findFirst().orElse(null);
            if (newLeader == null || newLeader.uuid().equals(leader.getUniqueId())) {
                this.sendWithPlayer(leader, language, "party.member-not-found", null, memberName, "");
                return;
            }
            party.transferLeader(newLeader.uuid(), Instant.now());
            newLeader = party.member(newLeader.uuid()).orElse(newLeader.withRole(PartyRole.LEADER));
        }
        this.publishSnapshot(party);
        this.notifyParty(party, "leader-transferred", newLeader, null);
    }

    public void disband(Player leader) {
        Language language = this.proxySettings.language(leader);
        Party party;
        synchronized (this) {
            party = this.party(leader.getUniqueId()).orElse(null);
            if (party == null) {
                send(leader, language, "party.not-in-party");
                return;
            }
            if (!party.leader(leader.getUniqueId())) {
                send(leader, language, "party.not-leader");
                return;
            }
            this.removeParty(party);
        }
        this.snapshots.delete(party);
        this.notifyDisband(party);
    }

    public void list(Player viewer) {
        Language language = this.proxySettings.language(viewer);
        Duration remaining = this.cooldowns.list(viewer.getUniqueId());
        if (!remaining.isZero()) {
            sendCooldown(viewer, language, remaining);
            return;
        }
        Party party = this.party(viewer.getUniqueId()).orElse(null);
        if (party == null) {
            send(viewer, language, "party.not-in-party");
            return;
        }
        this.renderList(viewer, language, party);
    }

    private void renderList(Player viewer, Language language, Party party) {
        List<UUID> ids = party.members().stream().map(PartyMember::uuid).toList();
        this.proxySettings.loadMany(ids).thenRun(() -> {
            PartyMember leader = party.member(party.leaderId()).orElse(party.members().get(0));
            viewer.sendMessage(this.messages.component(language, "party.list-header", Map.of(
                "count", String.valueOf(party.size()),
                "limit", String.valueOf(this.partyLimit(party)),
                "player_name", Messages.escape(leader.username())
            ), this.proxySettings.playerResolver(leader.uuid(), leader.username(), leader.prefix())));
            for (PartyMember member : party.members()) {
                viewer.sendMessage(this.messages.component(language, "party.list-entry", memberPlaceholders(member), this.proxySettings.playerResolver(member.uuid(), member.username(), member.prefix())));
            }
        });
    }

    public void pending(Player player) {
        Language language = this.proxySettings.language(player);
        List<PartyInvite> incoming = this.incomingInvites(player.getUniqueId());
        List<PartyInvite> outgoing = this.outgoingInvites(player.getUniqueId());
        player.sendMessage(this.messages.component(language, "pending.header", Map.of()));
        if (incoming.isEmpty()) {
            player.sendMessage(this.messages.component(language, "pending.incoming-empty", Map.of()));
        } else {
            for (PartyInvite invite : incoming) {
                player.sendMessage(this.messages.component(language, "pending.incoming-entry", Map.of(
                    "accept_command", "/" + this.config.commands().primary() + " accept " + invite.senderName(),
                    "deny_command", "/" + this.config.commands().primary() + " deny " + invite.senderName()
                ), this.proxySettings.playerResolver(invite.senderUuid(), invite.senderName(), invite.senderPrefix())));
            }
        }
        if (outgoing.isEmpty()) {
            player.sendMessage(this.messages.component(language, "pending.outgoing-empty", Map.of()));
        } else {
            for (PartyInvite invite : outgoing) {
                player.sendMessage(this.messages.component(language, "pending.outgoing-entry", Map.of(
                    "withdraw_command", "/" + this.config.commands().primary() + " withdraw " + invite.targetName()
                ), this.proxySettings.playerResolver(invite.targetUuid(), invite.targetName(), invite.targetPrefix())));
            }
        }
    }

    public void disconnect(Player player) {
        this.cleanupInvitesFor(player.getUniqueId());
        this.proxySettings.evict(player.getUniqueId());
        this.luckPerms.invalidate(player.getUniqueId());
        Party party;
        PartyMember removed;
        boolean wasLeader;
        synchronized (this) {
            party = this.party(player.getUniqueId()).orElse(null);
            if (party == null) {
                return;
            }
            wasLeader = party.leader(player.getUniqueId());
            removed = party.removeMember(player.getUniqueId(), Instant.now()).orElse(null);
            this.partyIdByPlayer.remove(player.getUniqueId());
            if (party.size() <= 1) {
                this.partyById.remove(party.id());
            } else if (wasLeader && this.config.party().transferLeaderOnDisconnect()) {
                party.oldestOnlineMemberExcept(player.getUniqueId()).ifPresent(newLeader -> party.transferLeader(newLeader.uuid(), Instant.now()));
            }
        }
        this.afterMemberRemoved(party, removed, false);
        if (this.chat != null) {
            this.chat.clear(player.getUniqueId());
        }
    }

    public void updateServer(Player player, String serverId) {
        Party party;
        synchronized (this) {
            party = this.party(player.getUniqueId()).orElse(null);
            if (party == null) {
                return;
            }
            party.member(player.getUniqueId()).ifPresent(member -> party.updateMember(member.withState(PartyMemberState.ONLINE, true, serverId), Instant.now()));
        }
        this.publishSnapshot(party);
    }

    public void refreshSnapshots() {
        for (Party party : this.partyById.values()) {
            this.publishSnapshot(party);
        }
    }

    public void chat(Player sender, String message) {
        Party party = this.party(sender.getUniqueId()).orElse(null);
        if (party == null) {
            this.feedback.send(sender, this.proxySettings.language(sender), "party-not-in-party", Map.of());
            return;
        }
        this.chat.send(sender, party, message);
    }

    public void toggleChat(Player player) {
        if (this.party(player.getUniqueId()).isEmpty()) {
            this.feedback.send(player, this.proxySettings.language(player), "party-not-in-party", Map.of());
            return;
        }
        this.chat.toggle(player);
    }

    private CompletableFuture<PartyMember> member(Player player, PartyRole role) {
        return this.identity(player).thenApply(identity -> new PartyMember(
            player.getUniqueId(),
            player.getUsername(),
            identity.prefix(),
            identity.primaryGroup(),
            role,
            PartyMemberState.ONLINE,
            true,
            serverId(player),
            Instant.now()
        ));
    }

    private PartyMember memberFromInviteSender(Player player, PartyInvite invite) {
        return new PartyMember(player.getUniqueId(), player.getUsername(), invite.senderPrefix(), "", PartyRole.LEADER, PartyMemberState.ONLINE, true, serverId(player), Instant.now());
    }

    private CompletableFuture<PlayerIdentity> identity(Player player) {
        return this.luckPerms.snapshot(player).thenApply(snapshot -> new PlayerIdentity(player.getUniqueId(), player.getUsername(), snapshot.prefix(), snapshot.primaryGroup()));
    }

    private void afterMemberRemoved(Party party, PartyMember removed, boolean kicked) {
        if (removed != null) {
            this.snapshots.deletePlayer(removed.uuid());
            if (this.chat != null) {
                this.chat.clear(removed.uuid());
            }
        }
        if (party.size() <= 1) {
            List<PartyMember> remaining = party.members();
            this.removeParty(party);
            this.snapshots.delete(party);
            for (PartyMember member : remaining) {
                this.server.getPlayer(member.uuid()).ifPresent(player -> this.feedback.send(player, this.proxySettings.language(player), "party-disbanded-alone", Map.of()));
            }
            return;
        }
        this.publishSnapshot(party);
        if (removed != null) {
            this.notifyParty(party, kicked ? "member-kicked" : "member-left", removed, removed.uuid());
            if (removed.role() == PartyRole.LEADER) {
                party.member(party.leaderId()).ifPresent(newLeader -> this.notifyParty(party, "leader-transferred", newLeader, null));
            }
        }
    }

    private void notifyParty(Party party, String action, PartyMember actor, UUID skip) {
        for (PartyMember member : party.members()) {
            if (skip != null && member.uuid().equals(skip)) {
                continue;
            }
            this.server.getPlayer(member.uuid()).ifPresent(player -> this.feedback.send(
                player,
                this.proxySettings.language(player),
                action,
                Map.of("player_name", Messages.escape(actor.username())),
                this.proxySettings.playerResolver(actor.uuid(), actor.username(), actor.prefix())
            ));
        }
    }

    private void notifyDisband(Party party) {
        for (PartyMember member : party.members()) {
            this.server.getPlayer(member.uuid()).ifPresent(player -> this.feedback.send(player, this.proxySettings.language(player), "party-disbanded", Map.of()));
        }
    }

    private synchronized void removeParty(Party party) {
        this.partyById.remove(party.id());
        for (PartyMember member : party.members()) {
            this.partyIdByPlayer.remove(member.uuid());
            if (this.chat != null) {
                this.chat.clear(member.uuid());
            }
        }
    }

    private void cleanupExpiredInvites() {
        Instant now = Instant.now();
        List<PartyInvite> expired = this.incomingInvitesByTarget.values().stream().flatMap(List::stream).filter(invite -> invite.expired(now)).toList();
        for (PartyInvite invite : expired) {
            this.removeInvite(invite);
        }
    }

    private void cleanupInvitesFor(UUID uuid) {
        List<PartyInvite> invites = new ArrayList<>();
        invites.addAll(this.incomingInvitesByTarget.getOrDefault(uuid, List.of()));
        invites.addAll(this.outgoingInvitesBySender.getOrDefault(uuid, List.of()));
        for (PartyInvite invite : invites) {
            this.removeInvite(invite);
        }
    }

    private void removeInvite(PartyInvite invite) {
        this.incomingInvitesByTarget.computeIfPresent(invite.targetUuid(), (uuid, list) -> removeInviteFrom(list, invite));
        this.outgoingInvitesBySender.computeIfPresent(invite.senderUuid(), (uuid, list) -> removeInviteFrom(list, invite));
    }

    private static List<PartyInvite> removeInviteFrom(List<PartyInvite> list, PartyInvite invite) {
        List<PartyInvite> copy = new ArrayList<>(list);
        copy.remove(invite);
        return copy.isEmpty() ? null : copy;
    }

    private Optional<PartyInvite> findIncoming(UUID target, String senderName) {
        return this.incomingInvitesByTarget.getOrDefault(target, List.of()).stream()
            .filter(invite -> invite.senderName().equalsIgnoreCase(senderName))
            .findFirst();
    }

    private Optional<PartyInvite> findOutgoing(UUID sender, String targetName) {
        return this.outgoingInvitesBySender.getOrDefault(sender, List.of()).stream()
            .filter(invite -> invite.targetName().equalsIgnoreCase(targetName))
            .findFirst();
    }

    private boolean inviteExists(UUID sender, UUID target) {
        return this.outgoingInvitesBySender.getOrDefault(sender, List.of()).stream().anyMatch(invite -> invite.targetUuid().equals(target) && !invite.expired(Instant.now()));
    }

    private Map<String, String> memberPlaceholders(PartyMember member) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player_name", Messages.escape(member.username()));
        placeholders.put("role", member.role().name());
        placeholders.put("state", member.state().name());
        placeholders.put("server", member.serverId() == null ? "" : Messages.escape(member.serverId()));
        return placeholders;
    }

    private void publishSnapshot(Party party) {
        this.snapshots.publish(party, this.partyLimit(party));
    }

    private int partyLimit(Party party) {
        return this.server.getPlayer(party.leaderId())
            .map(this.luckPerms::partyLimit)
            .orElse(this.config.limits().partySize().defaultLimit());
    }

    private void send(Player player, Language language, String key) {
        this.feedback.send(player, language, actionFor(key), Map.of());
    }

    private void send(Player player, Language language, String key, String placeholder, String value) {
        this.feedback.send(player, language, actionFor(key), Map.of(placeholder, Messages.escape(value)));
    }

    private void sendWithPlayer(Player player, Language language, String key, UUID targetId, String targetName, String prefix) {
        TagResolver resolver = targetId == null
            ? Placeholder.component("player_identity", Component.text(targetName == null ? "" : targetName))
            : this.proxySettings.playerResolver(targetId, targetName, prefix);
        this.feedback.send(player, language, actionFor(key), Map.of("player_name", Messages.escape(targetName)), resolver);
    }

    private void sendCooldown(Player player, Language language, Duration remaining) {
        long seconds = Math.max(1, (long) Math.ceil(remaining.toMillis() / 1000.0));
        this.feedback.send(player, language, "command-cooldown", Map.of("seconds", String.valueOf(seconds)));
    }

    private static String actionFor(String key) {
        return switch (key) {
            case "party.full" -> "party-full";
            case "party.not-in-party" -> "party-not-in-party";
            case "party.not-leader" -> "party-not-leader";
            default -> key;
        };
    }

    private static String serverId(Player player) {
        return player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
    }

    private static String newPartyId() {
        return "party_" + UUID.randomUUID().toString().replace("-", "");
    }

    public List<String> partyMemberNames(UUID playerId, boolean onlineOnly, boolean excludeSelf) {
        Party party = this.party(playerId).orElse(null);
        if (party == null) {
            return List.of();
        }
        return party.members().stream()
            .filter(member -> !excludeSelf || !member.uuid().equals(playerId))
            .filter(member -> !onlineOnly || this.server.getPlayer(member.uuid()).isPresent())
            .sorted(Comparator.comparing(member -> member.username().toLowerCase()))
            .map(PartyMember::username)
            .toList();
    }
}
