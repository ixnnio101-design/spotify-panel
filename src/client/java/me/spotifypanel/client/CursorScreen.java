package me.spotifypanel.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Пустой экран: освобождает мышь, чтобы кликать по панели.
 * Игра не ставится на паузу, фон не затемняется.
 */
public class CursorScreen extends Screen {
	public CursorScreen() {
		super(Component.literal("Spotify"));
	}

	public boolean isPauseScreen() {
		return false;
	}

	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
	}

	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		SpotifyPanelClient.panel().onScroll(scrollY);
		return true;
	}
}
