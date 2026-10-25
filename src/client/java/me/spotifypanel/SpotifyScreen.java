package me.spotifypanel;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Пустой экран: его единственная задача — освободить курсор и заблокировать поворот камеры,
 * пока игрок управляет панелью. Сама панель рисуется HUD-слоем, ввод читается в PanelRenderer.
 */
public final class SpotifyScreen extends Screen {
    public SpotifyScreen() {
        super(Text.literal("Spotify"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // ничего не рисуем: без затемнения фона, мир остаётся видимым
    }

    @Override
    public void removed() {
        PanelState.onScreenClosed();
    }
}
