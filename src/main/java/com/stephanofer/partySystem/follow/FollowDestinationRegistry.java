package com.stephanofer.partySystem.follow;

import com.stephanofer.partySystem.config.PluginConfig;
import java.util.Map;
import java.util.Optional;

public final class FollowDestinationRegistry {

    private final Map<String, FollowDestination> destinations;

    public FollowDestinationRegistry(PluginConfig config) {
        this.destinations = config.follow().destinations().entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
            Map.Entry::getKey,
            entry -> new FollowDestination(entry.getKey(), entry.getValue().type(), entry.getValue().game(), entry.getValue().followable())
        ));
    }

    public Optional<FollowDestination> destination(String serverId) {
        return Optional.ofNullable(this.destinations.get(serverId));
    }

    public boolean socialFollowable(String serverId) {
        return this.destination(serverId).map(FollowDestination::socialFollowable).orElse(false);
    }
}
