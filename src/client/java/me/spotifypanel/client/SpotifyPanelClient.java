package me.spotifypanel.client;

import me.spotifypanel.SpotifyPanel;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class SpotifyPanelClient implements ClientModInitializer {
	private static Config config;
	private static SpotifyAuth auth;
	private static SpotifyClient spotify;
	private static PanelRenderer panel;

	public static Config config() {
		return config;
	}

	public static SpotifyAuth auth() {
		return auth;
	}

	public static SpotifyClient spotify() {
		return spotify;
	}

	public static PanelRenderer panel() {
		return panel;
	}

	/** Сообщение в чат (безопасно из любого потока). */
	public static void say(String msg) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Spotify] " + msg), false);
		});
	}

	@Override
	public void onInitializeClient() {
		config = Config.load();
		auth = new SpotifyAuth(config);
		spotify = new SpotifyClient(config, auth);
		panel = new PanelRenderer(config, spotify, new CoverCache());
		spotify.start();

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, SpotifyPanel.id("panel"), panel::render);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> SpotifyCommands.register(dispatcher));
		SpotifyPanel.LOGGER.info("Spotify Panel loaded");
	}
}
