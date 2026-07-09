package com.stephanofer.partySystem.listener;

import com.stephanofer.partySystem.service.PartyFollowService;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;

public final class PlayerServerListener {

    private final PartyFollowService follow;

    public PlayerServerListener(PartyFollowService follow) {
        this.follow = follow;
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        this.follow.handleServerConnected(event.getPlayer(), event.getServer().getServerInfo().getName());
    }
}
