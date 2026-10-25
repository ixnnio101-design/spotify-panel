package me.spotifypanel.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.Locale;
import java.util.function.BiConsumer;

public final class SpotifyCommands {
	private SpotifyCommands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> d) {
		Config cfg = SpotifyPanelClient.config();
		SpotifyAuth auth = SpotifyPanelClient.auth();
		SpotifyClient sp = SpotifyPanelClient.spotify();
		PanelRenderer panel = SpotifyPanelClient.panel();

		LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommandManager.literal("spotify");

		root.then(ClientCommandManager.literal("setup")
			.then(ClientCommandManager.argument("clientId", StringArgumentType.word()).executes(c -> {
				cfg.clientId = StringArgumentType.getString(c, "clientId").trim();
				cfg.save();
				sp.reset();
				SpotifyPanelClient.say("Client ID сохранён. Теперь: /spotify login");
				return 1;
			})));

		root.then(ClientCommandManager.literal("login").executes(c -> {
			if (!auth.hasClientId()) {
				SpotifyPanelClient.say("Сначала: /spotify setup <client_id>");
				return 0;
			}
			sp.reset();
			auth.login(SpotifyPanelClient::say);
			return 1;
		}));

		root.then(ClientCommandManager.literal("logout").executes(c -> {
			auth.logout();
			sp.reset();
			SpotifyPanelClient.say("Вышел из Spotify.");
			return 1;
		}));

		root.then(ClientCommandManager.literal("play").executes(c -> {
			if (!sp.state.playing) sp.togglePlay();
			return 1;
		}));
		root.then(ClientCommandManager.literal("pause").executes(c -> {
			if (sp.state.playing) sp.togglePlay();
			return 1;
		}));
		root.then(ClientCommandManager.literal("toggle").executes(c -> {
			sp.togglePlay();
			return 1;
		}));
		root.then(ClientCommandManager.literal("next").executes(c -> {
			sp.next();
			return 1;
		}));
		root.then(ClientCommandManager.literal("prev").executes(c -> {
			sp.previous();
			return 1;
		}));
		root.then(ClientCommandManager.literal("volume")
			.then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(0, 100)).executes(c -> {
				sp.setVolume(IntegerArgumentType.getInteger(c, "percent"));
				return 1;
			})));

		root.then(ClientCommandManager.literal("lock").executes(c -> {
			panel.toggleFrozen();
			return 1;
		}));
		root.then(ClientCommandManager.literal("cursor").executes(c -> {
			panel.toggleCursor();
			return 1;
		}));
		root.then(ClientCommandManager.literal("show").executes(c -> {
			panel.setVisible(!cfg.visible);
			SpotifyPanelClient.say(cfg.visible ? "Панель показана." : "Панель скрыта.");
			return 1;
		}));

		LiteralArgumentBuilder<FabricClientCommandSource> set = ClientCommandManager.literal("set");
		set.then(setting("angle", -90, 90, (c, v) -> c.angle = v));
		set.then(setting("distance", 0.6, 4, (c, v) -> c.distance = v));
		set.then(setting("width", 0.3, 4, (c, v) -> c.width = v));
		set.then(setting("tilt", -30, 60, (c, v) -> c.tilt = v));
		root.then(set);

		d.register(root);
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> setting(String name, double min, double max, BiConsumer<Config, Double> apply) {
		return ClientCommandManager.literal(name)
			.then(ClientCommandManager.argument("value", DoubleArgumentType.doubleArg(min, max)).executes(c -> {
				double v = DoubleArgumentType.getDouble(c, "value");
				Config cfg = SpotifyPanelClient.config();
				apply.accept(cfg, v);
				cfg.save();
				SpotifyPanelClient.say("Готово: " + name + " = " + String.format(Locale.ROOT, "%.2f", v));
				return 1;
			}));
	}
}
