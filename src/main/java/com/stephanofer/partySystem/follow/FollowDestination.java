package com.stephanofer.partySystem.follow;

public record FollowDestination(String serverId, String type, String game, boolean followable) {
    public boolean socialFollowable() {
        String normalized = this.type == null ? "" : this.type.toUpperCase(java.util.Locale.ROOT);
        return this.followable && ("GLOBAL_LOBBY".equals(normalized) || "MODALITY_LOBBY".equals(normalized));
    }
}
