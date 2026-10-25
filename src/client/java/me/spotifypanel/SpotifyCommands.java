package me.spotifypanel;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

public final class SpotifyCommands {
    private SpotifyCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("spotify")
                        .executes(ctx -> {
                            PanelState.toggleVisible();
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
                        .then(ClientCommandManager.literal("mode").executes(ctx -> {
                            PanelState.toggleFixed();
                            SpotifyPanelMod.say(PanelState.fixed ? "Режим: фиксация в пространстве." : "Режим: следование за игроком.");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("angle")
                                .then(ClientCommandManager.argument("degrees", DoubleArgumentType.doubleArg(0, 120))
                                        .executes(ctx -> {
                                            Config c = SpotifyPanelMod.config;
                                            c.angle = DoubleArgumentType.getDouble(ctx, "degrees");
                                            c.save();
                                            SpotifyPanelMod.say("Угол справа от взгляда: " + Math.round(c.angle) + "°");
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
