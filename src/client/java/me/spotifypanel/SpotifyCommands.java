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
                        .then(ClientCommandManager.literal("playlist")
                                // /spotify playlist add <ссылка|uri|id>
                                .then(ClientCommandManager.literal("add")
                                        .then(ClientCommandManager.argument("link", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    String id = SpotifyClient.parsePlaylistId(StringArgumentType.getString(ctx, "link"));
                                                    if (id == null) {
                                                        SpotifyPanelMod.say("Не похоже на плейлист. Нужна ссылка вида https://open.spotify.com/playlist/...");
                                                        return 0;
                                                    }
                                                    Config c = SpotifyPanelMod.config;
                                                    if (!c.extraPlaylists.contains(id)) c.extraPlaylists.add(id);
                                                    c.save();
                                                    SpotifyPanelMod.spotify.reloadPlaylists();
                                                    SpotifyPanelMod.say("Плейлист добавлен, появится в Медиатеке через пару секунд.");
                                                    return 1;
                                                })))
                                // /spotify playlist remove <ссылка|uri|id>
                                .then(ClientCommandManager.literal("remove")
                                        .then(ClientCommandManager.argument("link", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    String id = SpotifyClient.parsePlaylistId(StringArgumentType.getString(ctx, "link"));
                                                    Config c = SpotifyPanelMod.config;
                                                    if (id == null || !c.extraPlaylists.remove(id)) {
                                                        SpotifyPanelMod.say("Такого плейлиста нет в добавленных по ссылке.");
                                                        return 0;
                                                    }
                                                    c.save();
                                                    SpotifyPanelMod.spotify.reloadPlaylists();
                                                    SpotifyPanelMod.say("Плейлист убран.");
                                                    return 1;
                                                }))))
                        // /spotify proxy host:port  |  /spotify proxy off
                        .then(ClientCommandManager.literal("proxy")
                                .then(ClientCommandManager.argument("hostport", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String v = StringArgumentType.getString(ctx, "hostport").trim();
                                            if (v.equalsIgnoreCase("off")) v = "";
                                            try {
                                                Net.setProxy(v);
                                            } catch (IllegalArgumentException e) {
                                                SpotifyPanelMod.say("Прокси не принят: " + e.getMessage());
                                                return 0;
                                            }
                                            Config c = SpotifyPanelMod.config;
                                            c.proxy = v;
                                            c.save();
                                            SpotifyPanelMod.say(v.isEmpty() ? "Прокси выключен." : "Прокси включён: " + v);
                                            return 1;
                                        })))
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
