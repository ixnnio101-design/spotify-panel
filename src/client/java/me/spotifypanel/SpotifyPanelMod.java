package me.spotifypanel;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class SpotifyPanelMod implements ClientModInitializer {
    public static final String ID = "spotify-panel";

    public static Config config;
    public static SpotifyAuth auth;
    public static SpotifyClient spotify;

    @Override
    public void onInitializeClient() {
        config = Config.load();
        auth = new SpotifyAuth(config);
        spotify = new SpotifyClient(auth);
        spotify.start();

        SpotifyCommands.register();
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.of(ID, "panel"), PanelRenderer::render);
    }

    /** Сообщение игроку в чат (безопасно вызывать из любого потока). */
    public static void say(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("[Spotify] " + msg), false);
            }
        });
    }
}
