package com.stephanofer.partySystem.listener;

import com.stephanofer.partySystem.service.PartyChatService;
import com.stephanofer.partySystem.service.PartyService;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;

public final class PlayerChatListener {

    private final PartyService parties;
    private final PartyChatService chat;

    public PlayerChatListener(PartyService parties, PartyChatService chat) {
        this.parties = parties;
        this.chat = chat;
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        if (!this.chat.toggled(event.getPlayer().getUniqueId())) {
            return;
        }
        event.setResult(PlayerChatEvent.ChatResult.denied());
        this.parties.chat(event.getPlayer(), event.getMessage());
    }
}
