package me.spotifypanel;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

public final class SpotifyCommands {
    private SpotifyCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("spotify")
                        // /spotify — показать/скрыть панель
                        .executes(ctx -> {
                            Config c = SpotifyPanelMod.config;
                            c.visible = !c.visible;
                            c.save();
                            SpotifyPanelMod.say(c.visible ? "Панель показана. Открой чат (T) — появится курсор, кликай по панели." : "Панель скрыта.");
                            return 1;
                        })
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
