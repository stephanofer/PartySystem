package com.stephanofer.partySystem.follow;

import com.stephanofer.partySystem.config.PluginConfig;
import java.util.Set;

public final class FollowDestinationRegistry {

    private final Set<String> destinations;

    public FollowDestinationRegistry(PluginConfig config) {
        this.destinations = Set.copyOf(config.follow().destinations());
    }

    public boolean followAllowed(String serverId) {
        return this.destinations.contains(serverId);
    }
}
