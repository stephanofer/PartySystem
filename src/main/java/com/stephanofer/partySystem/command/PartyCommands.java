package com.stephanofer.partySystem.command;

import com.stephanofer.partySystem.config.Language;
import com.stephanofer.partySystem.config.Messages;
import com.stephanofer.partySystem.config.PluginConfig;
import com.stephanofer.partySystem.domain.PartyInvite;
import com.stephanofer.partySystem.integration.ProxySettingsGateway;
import com.stephanofer.partySystem.service.PartyService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.incendo.cloud.velocity.VelocityCommandManager;

import static org.incendo.cloud.parser.standard.StringParser.greedyStringParser;
import static org.incendo.cloud.parser.standard.StringParser.stringParser;

public final class PartyCommands {

    private final VelocityCommandManager<CommandSource> manager;
    private final ProxyServer server;
    private final PartyService parties;
    private final Messages messages;
    private final ProxySettingsGateway proxySettings;
    private final PluginConfig config;

    public PartyCommands(
        VelocityCommandManager<CommandSource> manager,
        ProxyServer server,
        PartyService parties,
        Messages messages,
        ProxySettingsGateway proxySettings,
        PluginConfig config
    ) {
        this.manager = manager;
        this.server = server;
        this.parties = parties;
        this.messages = messages;
        this.proxySettings = proxySettings;
        this.config = config;
    }

    public void register() {
        for (String root : this.config.commandLabels()) {
            this.registerRoot(root);
        }
        for (String alias : this.config.commands().partyChatAliases()) {
            this.registerPartyChatAlias(alias);
        }
    }

    private void registerRoot(String root) {
        this.manager.command(this.manager.commandBuilder(root)
            .permission("partysystem.command.party")
            .handler(context -> this.help(context.sender(), root)));
        this.manager.command(this.manager.commandBuilder(root)
            .literal("create")
            .permission("partysystem.command.party")
            .handler(context -> this.player(context.sender(), this.parties::create)));
        this.manager.command(this.manager.commandBuilder(root)
            .literal("invite")
            .required("player", stringParser(), this.onlineInviteSuggestions())
            .permission("partysystem.command.party")
            .handler(context -> this.player(context.sender(), player -> this.parties.invite(player, context.get("player")))));
        this.manager.command(this.manager.commandBuilder(root)
            .literal("accept")
            .required("player", stringParser(), this.inviteSuggestions(true))
            .permission("partysystem.command.party")
            .handler(context -> this.player(context.sender(), player -> this.parties.accept(player, context.get("player")))));
        this.manager.command(this.manager.commandBuilder(root)
            .literal("deny")
            .required("player", stringParser(), this.inviteSuggestions(true))
            .permission("partysystem.command.party")
            .handler(context -> this.player(context.sender(), player -> this.parties.deny(player, context.get("player")))));
        this.manager.command(this.manager.commandBuilder(root)
            .literal("withdraw")
            .required("player", stringParser(), this.inviteSuggestions(false))
            .permission("partysystem.command.party")
            .handler(context -> this.player(context.sender(), player -> this.parties.withdraw(player, context.get("player")))));
        this.manager.command(this.manager.commandBuilder(root)
            .literal("pending")
            .permission("partysystem.command.party")
            .handler(context -> this.player(context.sender(), this.parties::pending)));
        this.manager.command(this.manager.commandBuilder(root)
            .literal("list")
            .permission("partysystem.command.party")
            .handler(context -> this.player(context.sender(), this.parties::list)));
        this.manager.command(this.manager.commandBuilder(root)
            .literal("leave")
            .permission("partysystem.command.party")
            .handler(context -> this.player(context.sender(), this.parties::leave)));
        this.manager.command(this.manager.commandBuilder(root)
            .literal("kick")
            .required("player", stringParser(), this.memberSuggestions(false))
            .permission("partysystem.command.party")
            .handler(context -> this.player(context.sender(), player -> this.parties.kick(player, context.get("player")))));
        this.manager.command(this.manager.commandBuilder(root)
            .literal("transfer")
            .required("player", stringParser(), this.memberSuggestions(true))
            .permission("partysystem.command.party")
            .handler(context -> this.player(context.sender(), player -> this.parties.transfer(player, context.get("player")))));
        this.manager.command(this.manager.commandBuilder(root)
            .literal("disband")
            .permission("partysystem.command.party")
            .handler(context -> this.player(context.sender(), this.parties::disband)));
        this.manager.command(this.manager.commandBuilder(root)
            .literal("chat")
            .required("message", greedyStringParser())
            .permission("partysystem.command.party")
            .handler(context -> this.player(context.sender(), player -> this.parties.chat(player, context.get("message")))));
        this.manager.command(this.manager.commandBuilder(root)
            .literal("togglechat")
            .permission("partysystem.command.party")
            .handler(context -> this.player(context.sender(), this.parties::toggleChat)));
    }

    private void registerPartyChatAlias(String alias) {
        this.manager.command(this.manager.commandBuilder(alias)
            .required("message", greedyStringParser())
            .permission("partysystem.command.party")
            .handler(context -> this.player(context.sender(), player -> this.parties.chat(player, context.get("message")))));
    }

    private SuggestionProvider<CommandSource> onlineInviteSuggestions() {
        return (context, input) -> {
            if (!(context.sender() instanceof Player player)) {
                return CompletableFuture.completedFuture(List.of());
            }
            List<String> names = this.server.getAllPlayers().stream()
                .filter(candidate -> !candidate.getUniqueId().equals(player.getUniqueId()))
                .filter(candidate -> this.parties.party(player.getUniqueId()).map(party -> !party.contains(candidate.getUniqueId())).orElse(true))
                .map(Player::getUsername)
                .toList();
            return CompletableFuture.completedFuture(this.toSuggestions(names, input));
        };
    }

    private SuggestionProvider<CommandSource> inviteSuggestions(boolean incoming) {
        return (context, input) -> {
            if (!(context.sender() instanceof Player player)) {
                return CompletableFuture.completedFuture(List.of());
            }
            List<String> names = (incoming ? this.parties.incomingInvites(player.getUniqueId()) : this.parties.outgoingInvites(player.getUniqueId())).stream()
                .map(incoming ? PartyInvite::senderName : PartyInvite::targetName)
                .toList();
            return CompletableFuture.completedFuture(this.toSuggestions(names, input));
        };
    }

    private SuggestionProvider<CommandSource> memberSuggestions(boolean onlineOnly) {
        return (context, input) -> {
            if (!(context.sender() instanceof Player player)) {
                return CompletableFuture.completedFuture(List.of());
            }
            return CompletableFuture.completedFuture(this.toSuggestions(this.parties.partyMemberNames(player.getUniqueId(), onlineOnly, true), input));
        };
    }

    private List<Suggestion> toSuggestions(List<String> names, CommandInput input) {
        String prefix = input.lastRemainingToken().toLowerCase();
        int limit = prefix.isBlank()
            ? this.config.commands().suggestions().emptyInputMaxResults()
            : this.config.commands().suggestions().filteredMaxResults();
        return names.stream()
            .filter(name -> prefix.isBlank() || name.toLowerCase().startsWith(prefix))
            .sorted(Comparator.comparing(String::toLowerCase))
            .limit(limit)
            .map(Suggestion::suggestion)
            .toList();
    }

    private void help(CommandSource source, String root) {
        Map<String, String> placeholders = Map.ofEntries(
            Map.entry("command", root),
            Map.entry("create_command", "/" + root + " create"),
            Map.entry("invite_command", "/" + root + " invite "),
            Map.entry("accept_command", "/" + root + " accept "),
            Map.entry("deny_command", "/" + root + " deny "),
            Map.entry("withdraw_command", "/" + root + " withdraw "),
            Map.entry("pending_command", "/" + root + " pending"),
            Map.entry("list_command", "/" + root + " list"),
            Map.entry("leave_command", "/" + root + " leave"),
            Map.entry("kick_command", "/" + root + " kick "),
            Map.entry("transfer_command", "/" + root + " transfer "),
            Map.entry("disband_command", "/" + root + " disband"),
            Map.entry("chat_command", "/" + root + " chat "),
            Map.entry("togglechat_command", "/" + root + " togglechat")
        );
        Language language = source instanceof Player player ? this.proxySettings.language(player) : Language.EN;
        source.sendMessage(this.messages.component(language, "help.header", placeholders));
        for (String key : List.of("help.create", "help.invite", "help.accept", "help.deny", "help.withdraw", "help.pending", "help.list", "help.leave", "help.kick", "help.transfer", "help.disband", "help.chat", "help.togglechat")) {
            source.sendMessage(this.messages.component(language, key, placeholders));
        }
    }

    private void player(CommandSource source, PlayerAction action) {
        if (!(source instanceof Player player)) {
            this.messages.send(source, Language.EN, "player-only", Map.of());
            return;
        }
        action.execute(player);
    }

    @FunctionalInterface
    private interface PlayerAction {
        void execute(Player player);
    }
}
