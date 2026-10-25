package me.spotifypanel;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.function.BiConsumer;

public final class SpotifyCommands {
    private SpotifyCommands() {}

    private static LiteralArgumentBuilder<FabricClientCommandSource> setting(String name, double min, double max,
                                                                            BiConsumer<Config, Double> apply) {
        return ClientCommandManager.literal(name)
                .then(ClientCommandManager.argument("value", DoubleArgumentType.doubleArg(min, max))
                        .executes(ctx -> {
                            double v = DoubleArgumentType.getDouble(ctx, "value");
                            apply.accept(SpotifyPanelMod.config, v);
                            SpotifyPanelMod.config.save();
                            SpotifyPanelMod.say("Готово: " + name + " = " + v);
                            return 1;
                        }));
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("spotify")
                        // /spotify — показать/скрыть панель (с анимацией)
                        .executes(ctx -> {
                            PanelRenderer.setVisible(!SpotifyPanelMod.config.visible);
                            return 1;
                        })
                        .then(ClientCommandManager.literal("lock").executes(ctx -> {
                            PanelRenderer.toggleFrozen();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("cursor").executes(ctx -> {
                            PanelRenderer.toggleCursor();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("set")
                                .then(setting("angle", -170, 170, (c, v) -> c.angle = v))
                                .then(setting("distance", 0.6, 6, (c, v) -> c.distance = v))
                                .then(setting("width", 0.4, 4, (c, v) -> c.width = v))
                                .then(setting("tilt", -60, 60, (c, v) -> c.tilt = v)))
                        .then(ClientCommandManager.literal("setup")
                                .then(ClientCommandManager.argument("clientId", StringArgumentType.word())
                                        .executes(ctx -> {
                                            Config c = SpotifyPanelMod.config;
                                            c.clientId = StringArgumentType.getString(ctx, "clientId");
                                            c.save();
                                            SpotifyPanelMod.say("Client ID сохранён. Теперь: /spotify login");
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("login").executes(ctx -> {
                            if (SpotifyPanelMod.config.clientId.isEmpty()) {
                                SpotifyPanelMod.say("Сначала: /spotify setup <client_id>");
                                return 0;
                            }
                            SpotifyPanelMod.auth.login(SpotifyPanelMod::say);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("logout").executes(ctx -> {
                            SpotifyPanelMod.auth.logout();
                            SpotifyPanelMod.say("Вышел из Spotify.");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("play").executes(ctx -> {
                            SpotifyPanelMod.spotify.play(SpotifyPanelMod::say);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("pause").executes(ctx -> {
                            SpotifyPanelMod.spotify.pause(SpotifyPanelMod::say);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                            SpotifyPanelMod.spotify.togglePlay(SpotifyPanelMod::say);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("next").executes(ctx -> {
                            SpotifyPanelMod.spotify.next(SpotifyPanelMod::say);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("prev").executes(ctx -> {
                            SpotifyPanelMod.spotify.previous(SpotifyPanelMod::say);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("volume")
                                .then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(0, 100))
                                        .executes(ctx -> {
                                            SpotifyPanelMod.spotify.volume(IntegerArgumentType.getInteger(ctx, "percent"),
                                                    SpotifyPanelMod::say);
                                            return 1;
                                        })))
        ));
    }
}
