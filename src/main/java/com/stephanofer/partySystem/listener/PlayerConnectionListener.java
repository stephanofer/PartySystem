package com.stephanofer.partySystem.listener;

import com.stephanofer.partySystem.integration.ProxySettingsGateway;
import com.stephanofer.partySystem.service.PartyService;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;

public final class PlayerConnectionListener {

    private final ProxySettingsGateway proxySettings;
    private final PartyService parties;

    public PlayerConnectionListener(ProxySettingsGateway proxySettings, PartyService parties) {
        this.proxySettings = proxySettings;
        this.parties = parties;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        this.proxySettings.load(event.getPlayer());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        this.parties.disconnect(event.getPlayer());
    }
}
