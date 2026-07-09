package com.stephanofer.partySystem.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stephanofer.partySystem.config.Language;
import com.stephanofer.partySystem.config.Messages;
import com.stephanofer.partySystem.config.PluginConfig;
import com.stephanofer.partySystem.domain.Party;
import com.stephanofer.partySystem.domain.PartyMember;
import com.stephanofer.partySystem.integration.ProxySettingsGateway;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

public final class PartyChatService {

    private final ProxyServer server;
    private final PluginConfig config;
    private final Messages messages;
    private final FeedbackService feedback;
    private final ProxySettingsGateway proxySettings;
    private final CommandCooldowns cooldowns;
    private final Cache<UUID, Boolean> toggled = Caffeine.newBuilder().build();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public PartyChatService(ProxyServer server, PluginConfig config, Messages messages, FeedbackService feedback, ProxySettingsGateway proxySettings, CommandCooldowns cooldowns) {
        this.server = server;
        this.config = config;
        this.messages = messages;
        this.feedback = feedback;
        this.proxySettings = proxySettings;
        this.cooldowns = cooldowns;
    }

    public boolean toggled(UUID uuid) {
        return Boolean.TRUE.equals(this.toggled.getIfPresent(uuid));
    }

    public void clear(UUID uuid) {
        this.toggled.invalidate(uuid);
    }

    public void toggle(Player player) {
        Language language = this.proxySettings.language(player);
        var remaining = this.cooldowns.toggleChat(player.getUniqueId());
        if (!remaining.isZero()) {
            sendCooldown(player, language, remaining);
            return;
        }
        boolean enabled = !this.toggled(player.getUniqueId());
        if (enabled) {
            this.toggled.put(player.getUniqueId(), true);
        } else {
            this.toggled.invalidate(player.getUniqueId());
        }
        this.feedback.send(player, language, "party-chat-toggled", Map.of("state", this.messages.raw(language, enabled ? "chat.enabled" : "chat.disabled", Map.of())));
    }

    public void send(Player sender, Party party, String message) {
        Language language = this.proxySettings.language(sender);
        var remaining = this.cooldowns.chat(sender.getUniqueId());
        if (!remaining.isZero()) {
            sendCooldown(sender, language, remaining);
            return;
        }
        String normalized = message == null ? "" : message.trim();
        if (normalized.isEmpty()) {
            this.feedback.send(sender, language, "party-chat-error", Map.of());
            return;
        }
        if (normalized.length() > this.config.party().chatMaxLength()) {
            this.feedback.send(sender, language, "chat.too-long", Map.of("limit", String.valueOf(this.config.party().chatMaxLength())));
            return;
        }
        PartyMember actor = party.member(sender.getUniqueId()).orElse(null);
        if (actor == null) {
            this.feedback.send(sender, language, "party-not-in-party", Map.of());
            return;
        }
        var component = this.miniMessage.deserialize(
            this.config.display().partyChatFormat(),
            this.proxySettings.resolvers(
                this.proxySettings.playerResolver(actor.uuid(), actor.username(), actor.prefix()),
                this.proxySettings.messageResolver(actor.uuid(), normalized),
                Placeholder.unparsed("player_name", Messages.escape(actor.username()))
            )
        );
        for (PartyMember member : party.members()) {
            this.server.getPlayer(member.uuid()).ifPresent(player -> player.sendMessage(component));
        }
    }

    private void sendCooldown(Player player, Language language, java.time.Duration remaining) {
        long seconds = Math.max(1, (long) Math.ceil(remaining.toMillis() / 1000.0));
        this.feedback.send(player, language, "command-cooldown", Map.of("seconds", String.valueOf(seconds)));
    }
}
