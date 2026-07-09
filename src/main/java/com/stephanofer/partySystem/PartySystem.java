package com.stephanofer.partySystem;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.hera.craftkit.redis.RedisClient;
import com.hera.craftkit.redis.RedisConfig;
import com.hera.craftkit.redis.RedisClients;
import com.stephanofer.partySystem.command.PartyCommands;
import com.stephanofer.partySystem.config.Messages;
import com.stephanofer.partySystem.config.PluginConfig;
import com.stephanofer.partySystem.debug.DebugLogger;
import com.stephanofer.partySystem.follow.FollowDestinationRegistry;
import com.stephanofer.partySystem.follow.MovementCauseTracker;
import com.stephanofer.partySystem.integration.LuckPermsGateway;
import com.stephanofer.partySystem.integration.ProxySettingsGateway;
import com.stephanofer.partySystem.integration.RedisPartySnapshotStore;
import com.stephanofer.partySystem.listener.PlayerChatListener;
import com.stephanofer.partySystem.listener.PlayerConnectionListener;
import com.stephanofer.partySystem.listener.PlayerServerListener;
import com.stephanofer.partySystem.service.CommandCooldowns;
import com.stephanofer.partySystem.service.FeedbackService;
import com.stephanofer.partySystem.service.PartyChatService;
import com.stephanofer.partySystem.service.PartyFollowService;
import com.stephanofer.partySystem.service.PartyService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.minecraft.extras.MinecraftExceptionHandler;
import org.incendo.cloud.velocity.CloudInjectionModule;
import org.incendo.cloud.velocity.VelocityCommandManager;
import org.slf4j.Logger;

@Plugin(
    id = "partysystem",
    name = "PartySystem",
    version = BuildConstants.VERSION,
    url = "stephanofer.com",
    authors = {"stephanofer"},
    dependencies = {
        @Dependency(id = "proxysettings"),
        @Dependency(id = "luckperms", optional = true)
    }
)
public final class PartySystem {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final Injector injector;
    private final List<ScheduledTask> tasks = new ArrayList<>();

    private RedisClient redis;

    @Inject
    public PartySystem(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory, Injector injector) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.injector = injector;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            PluginConfig config = PluginConfig.load(this.dataDirectory);
            Messages messages = Messages.load(this.dataDirectory);
            this.redis = RedisClients.lettuce(RedisConfig.builder()
                .host(config.redis().host())
                .port(config.redis().port())
                .database(config.redis().database())
                .username(config.redis().username())
                .password(config.redis().password())
                .ssl(config.redis().ssl())
                .keyPrefix(config.redis().keyPrefix())
                .environment(config.redis().environment())
                .serverId(config.redis().serverId())
                .build());
            this.redis.ping().join();

            DebugLogger debug = new DebugLogger(this.logger, config.debug());
            ProxySettingsGateway proxySettings = new ProxySettingsGateway(config);
            LuckPermsGateway luckPerms = new LuckPermsGateway(this.logger, config);
            RedisPartySnapshotStore snapshots = new RedisPartySnapshotStore(this.redis, config, debug);
            FeedbackService feedback = new FeedbackService(messages, config, this.logger);
            CommandCooldowns cooldowns = new CommandCooldowns(config.cooldowns());
            PartyService parties = new PartyService(this.server, config, messages, proxySettings, luckPerms, snapshots, feedback, debug, cooldowns);
            PartyChatService chat = new PartyChatService(this.server, config, messages, feedback, proxySettings, cooldowns);
            parties.attachChat(chat);
            MovementCauseTracker movementCauses = new MovementCauseTracker(config.follow().antiLoopTtl());
            FollowDestinationRegistry destinations = new FollowDestinationRegistry(config);
            PartyFollowService follow = new PartyFollowService(this.server, config, parties, destinations, movementCauses, debug);

            this.registerCommands(config, messages, proxySettings, parties);
            this.server.getEventManager().register(this, new PlayerConnectionListener(proxySettings, parties));
            this.server.getEventManager().register(this, new PlayerServerListener(follow));
            this.server.getEventManager().register(this, new PlayerChatListener(parties, chat));
            this.tasks.add(this.server.getScheduler()
                .buildTask(this, parties::refreshSnapshots)
                .repeat(config.snapshots().heartbeat())
                .schedule());

            this.logger.info("PartySystem enabled.");
        } catch (Exception exception) {
            this.closeQuietly();
            throw new IllegalStateException("Unable to enable PartySystem", exception);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        this.closeQuietly();
    }

    private void registerCommands(PluginConfig config, Messages messages, ProxySettingsGateway proxySettings, PartyService parties) {
        Injector childInjector = this.injector.createChildInjector(new CloudInjectionModule<>(
            CommandSource.class,
            ExecutionCoordinator.simpleCoordinator(),
            SenderMapper.identity()
        ));
        VelocityCommandManager<CommandSource> manager = childInjector.getInstance(
            Key.get(new TypeLiteral<VelocityCommandManager<CommandSource>>() {})
        );
        MinecraftExceptionHandler.<CommandSource>createNative()
            .defaultHandlers()
            .registerTo(manager);
        new PartyCommands(manager, this.server, parties, messages, proxySettings, config).register();
    }

    private void closeQuietly() {
        for (ScheduledTask task : this.tasks) {
            task.cancel();
        }
        this.tasks.clear();
        if (this.redis != null) {
            this.redis.close();
        }
    }
}
