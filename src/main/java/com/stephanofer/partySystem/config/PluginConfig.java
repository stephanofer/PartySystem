package com.stephanofer.partySystem.config;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record PluginConfig(
    Redis redis,
    Party party,
    Limits limits,
    Commands commands,
    Cooldowns cooldowns,
    Cache cache,
    Snapshots snapshots,
    Display display,
    Follow follow,
    Feedback feedback,
    Debug debug
) {

    private static final LoaderSettings CONFIG_LOADER_SETTINGS = LoaderSettings.builder()
        .setAutoUpdate(true)
        .build();
    private static final UpdaterSettings CONFIG_UPDATER_SETTINGS = UpdaterSettings.builder()
        .setVersioning(new BasicVersioning("config-version"))
        .build();

    public static PluginConfig load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path configPath = dataDirectory.resolve("config.yml");
        try (InputStream defaults = resource("/config.yml")) {
            YamlDocument document = YamlDocument.create(
                configPath.toFile(),
                defaults,
                CONFIG_LOADER_SETTINGS,
                DumperSettings.DEFAULT,
                CONFIG_UPDATER_SETTINGS
            );
            return new PluginConfig(
                new Redis(
                    document.getString("redis.host"),
                    document.getInt("redis.port"),
                    document.getInt("redis.database"),
                    document.getString("redis.username"),
                    document.getString("redis.password"),
                    document.getBoolean("redis.ssl"),
                    document.getString("redis.key-prefix"),
                    document.getString("redis.environment"),
                    document.getString("redis.server-id")
                ),
                new Party(
                    duration(document.getString("party.invite-expiration")),
                    document.getBoolean("party.disband-when-empty", true),
                    document.getBoolean("party.transfer-leader-on-disconnect", true),
                    positive(document.getInt("party.chat-max-length"), 512)
                ),
                new Limits(new LimitGroup(
                    positive(document.getInt("limits.party-size.default"), positive(document.getInt("party.max-size"), 4)),
                    permissionLimits(document, "limits.party-size.permissions")
                )),
                new Commands(
                    nonBlank(document.getString("commands.primary"), "party"),
                    aliases(document.getStringList("commands.aliases")),
                    aliases(document.getStringList("commands.party-chat-aliases")),
                    new Suggestions(
                        duration(document.getString("commands.suggestions.cache-ttl")),
                        positive(document.getInt("commands.suggestions.empty-input-max-results"), 20),
                        positive(document.getInt("commands.suggestions.filtered-max-results"), 50)
                    )
                ),
                new Cooldowns(
                    duration(document.getString("cooldowns.invite")),
                    duration(document.getString("cooldowns.list")),
                    duration(document.getString("cooldowns.chat")),
                    duration(document.getString("cooldowns.togglechat"))
                ),
                new Cache(
                    duration(document.getString("cache.movement-cause-ttl")),
                    duration(document.getString("cache.luckperms-snapshot-ttl"))
                ),
                new Snapshots(
                    duration(document.getString("snapshots.ttl")),
                    duration(document.getString("snapshots.heartbeat"))
                ),
                new Display(
                    document.getString("display.player-identity-format"),
                    document.getString("display.party-chat-format")
                ),
                new Follow(
                    document.getBoolean("follow.enabled", true),
                    duration(document.getString("follow.anti-loop-ttl")),
                    followDestinations(document)
                ),
                new Feedback(feedbackActions(document)),
                new Debug(
                    document.getBoolean("debug.enabled", false),
                    document.getBoolean("debug.include-stacktraces", false),
                    new DebugCategories(
                        document.getBoolean("debug.categories.lifecycle", true),
                        document.getBoolean("debug.categories.invites", true),
                        document.getBoolean("debug.categories.commands", true),
                        document.getBoolean("debug.categories.chat", true),
                        document.getBoolean("debug.categories.follow", true),
                        document.getBoolean("debug.categories.snapshots", true),
                        document.getBoolean("debug.categories.redis", true),
                        document.getBoolean("debug.categories.permissions", true),
                        document.getBoolean("debug.categories.autocomplete", false)
                    )
                )
            );
        }
    }

    public static Duration duration(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ZERO;
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2)));
        }
        long amount = Long.parseLong(text.substring(0, text.length() - 1));
        return switch (text.charAt(text.length() - 1)) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> Duration.parse(value);
        };
    }

    public List<String> commandLabels() {
        List<String> labels = new ArrayList<>();
        labels.add(this.commands.primary());
        for (String alias : this.commands.aliases()) {
            if (!alias.equalsIgnoreCase(this.commands.primary())) {
                labels.add(alias);
            }
        }
        return List.copyOf(labels);
    }

    private static Map<String, FollowDestinationConfig> followDestinations(YamlDocument document) {
        if (!document.isSection("follow.destinations")) {
            return Map.of();
        }
        Map<String, FollowDestinationConfig> destinations = new HashMap<>();
        for (Object key : document.getSection("follow.destinations").getKeys()) {
            String server = String.valueOf(key);
            String path = "follow.destinations." + server;
            destinations.put(server, new FollowDestinationConfig(
                document.getString(path + ".type", "UNKNOWN"),
                document.getString(path + ".game", ""),
                document.getBoolean(path + ".followable", false)
            ));
        }
        return Map.copyOf(destinations);
    }

    private static Map<String, FeedbackAction> feedbackActions(YamlDocument document) {
        if (!document.isSection("feedback.actions")) {
            return Map.of();
        }
        Map<String, FeedbackAction> actions = new HashMap<>();
        for (Object key : document.getSection("feedback.actions").getKeys()) {
            String action = String.valueOf(key);
            List<FeedbackOutput> outputs = new ArrayList<>();
            for (Map<?, ?> output : document.getMapList("feedback.actions." + action + ".outputs", List.of())) {
                outputs.add(new FeedbackOutput(
                    string(output, "type", "CHAT").toUpperCase(Locale.ROOT),
                    string(output, "message", ""),
                    string(output, "sound", "minecraft:block.note_block.pling"),
                    string(output, "source", "MASTER"),
                    decimal(output, "volume", 1.0f),
                    decimal(output, "pitch", 1.0f),
                    string(output, "title", string(output, "message", "")),
                    string(output, "subtitle", "")
                ));
            }
            actions.put(action, new FeedbackAction(List.copyOf(outputs)));
        }
        return Map.copyOf(actions);
    }

    private static List<PermissionLimit> permissionLimits(YamlDocument document, String path) {
        List<PermissionLimit> limits = new ArrayList<>();
        for (Map<?, ?> entry : document.getMapList(path, List.of())) {
            String permission = string(entry, "permission", "").trim();
            int limit = positive(integer(entry, "limit", 0), 0);
            if (!permission.isEmpty() && limit > 0) {
                limits.add(new PermissionLimit(permission, limit));
            }
        }
        return List.copyOf(limits);
    }

    private static String string(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static float decimal(Map<?, ?> map, String key, float fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.floatValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int integer(Map<?, ?> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static List<String> aliases(List<String> values) {
        return values.stream().map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }

    private static InputStream resource(String path) {
        return Objects.requireNonNull(PluginConfig.class.getResourceAsStream(path), () -> "Missing bundled resource: " + path);
    }

    public record Redis(String host, int port, int database, String username, String password, boolean ssl, String keyPrefix, String environment, String serverId) {}
    public record Party(Duration inviteExpiration, boolean disbandWhenEmpty, boolean transferLeaderOnDisconnect, int chatMaxLength) {}
    public record Limits(LimitGroup partySize) {}
    public record LimitGroup(int defaultLimit, List<PermissionLimit> permissions) {}
    public record PermissionLimit(String permission, int limit) {}
    public record Commands(String primary, List<String> aliases, List<String> partyChatAliases, Suggestions suggestions) {}
    public record Suggestions(Duration cacheTtl, int emptyInputMaxResults, int filteredMaxResults) {}
    public record Cooldowns(Duration invite, Duration list, Duration chat, Duration togglechat) {}
    public record Cache(Duration movementCauseTtl, Duration luckPermsSnapshotTtl) {}
    public record Snapshots(Duration ttl, Duration heartbeat) {}
    public record Display(String playerIdentityFormat, String partyChatFormat) {}
    public record Follow(boolean enabled, Duration antiLoopTtl, Map<String, FollowDestinationConfig> destinations) {}
    public record FollowDestinationConfig(String type, String game, boolean followable) {}
    public record Feedback(Map<String, FeedbackAction> actions) {
        public FeedbackAction action(String key) {
            return this.actions.getOrDefault(key, FeedbackAction.chat(defaultMessage(key)));
        }

        private static String defaultMessage(String action) {
            return switch (action) {
                case "invite-received" -> "invite.received";
                case "invite-sent" -> "invite.sent";
                case "invite-accepted" -> "invite.accepted";
                case "invite-accepted-target" -> "invite.accepted-target";
                case "invite-denied" -> "invite.denied";
                case "invite-denied-target" -> "invite.denied-target";
                case "invite-withdrawn" -> "invite.withdrawn";
                case "party-created" -> "party.created";
                case "party-left" -> "party.left";
                case "member-joined" -> "party.member-joined";
                case "member-left" -> "party.member-left";
                case "member-kicked" -> "party.member-kicked";
                case "member-kicked-target" -> "party.kicked-target";
                case "leader-transferred" -> "party.leader-transferred";
                case "party-disbanded" -> "party.disbanded";
                case "party-chat-toggled" -> "chat.toggled";
                case "command-cooldown" -> "cooldown";
                case "party-full" -> "party.full";
                case "party-not-in-party" -> "party.not-in-party";
                case "party-not-leader" -> "party.not-leader";
                case "party-chat-error" -> "chat.error";
                default -> action;
            };
        }
    }
    public record FeedbackAction(List<FeedbackOutput> outputs) {
        public static FeedbackAction chat(String message) {
            return new FeedbackAction(List.of(new FeedbackOutput("CHAT", message, "", "MASTER", 1.0f, 1.0f, message, "")));
        }
    }
    public record FeedbackOutput(String type, String message, String sound, String source, float volume, float pitch, String title, String subtitle) {}
    public record Debug(boolean enabled, boolean includeStacktraces, DebugCategories categories) {}
    public record DebugCategories(boolean lifecycle, boolean invites, boolean commands, boolean chat, boolean follow, boolean snapshots, boolean redis, boolean permissions, boolean autocomplete) {}
}
