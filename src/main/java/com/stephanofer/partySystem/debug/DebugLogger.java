package com.stephanofer.partySystem.debug;

import com.stephanofer.partySystem.config.PluginConfig;
import java.util.Map;
import org.slf4j.Logger;

public final class DebugLogger {

    private final Logger logger;
    private final PluginConfig.Debug config;

    public DebugLogger(Logger logger, PluginConfig.Debug config) {
        this.logger = logger;
        this.config = config;
    }

    public void lifecycle(String message, Map<String, String> context) {
        this.log(this.config.categories().lifecycle(), "lifecycle", message, context, null);
    }

    public void invites(String message, Map<String, String> context) {
        this.log(this.config.categories().invites(), "invites", message, context, null);
    }

    public void commands(String message, Map<String, String> context) {
        this.log(this.config.categories().commands(), "commands", message, context, null);
    }

    public void chat(String message, Map<String, String> context) {
        this.log(this.config.categories().chat(), "chat", message, context, null);
    }

    public void follow(String message, Map<String, String> context) {
        this.log(this.config.categories().follow(), "follow", message, context, null);
    }

    public void snapshots(String message, Map<String, String> context) {
        this.log(this.config.categories().snapshots(), "snapshots", message, context, null);
    }

    public void redis(String message, Map<String, String> context, Throwable throwable) {
        this.log(this.config.categories().redis(), "redis", message, context, throwable);
    }

    public void permissions(String message, Map<String, String> context) {
        this.log(this.config.categories().permissions(), "permissions", message, context, null);
    }

    public void autocomplete(String message, Map<String, String> context) {
        this.log(this.config.categories().autocomplete(), "autocomplete", message, context, null);
    }

    private void log(boolean categoryEnabled, String category, String message, Map<String, String> context, Throwable throwable) {
        if (!this.config.enabled() || !categoryEnabled) {
            return;
        }
        StringBuilder builder = new StringBuilder("[PartySystem:").append(category).append("] ").append(message);
        for (Map.Entry<String, String> entry : context.entrySet()) {
            builder.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
        }
        if (throwable != null && this.config.includeStacktraces()) {
            this.logger.warn(builder.toString(), throwable);
            return;
        }
        if (throwable != null) {
            builder.append(" error=").append(throwable.getClass().getSimpleName()).append(':').append(throwable.getMessage());
        }
        this.logger.info(builder.toString());
    }
}
