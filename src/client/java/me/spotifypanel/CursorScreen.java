package me.spotifypanel;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Прозрачный экран: показывает курсор мыши, не ставит игру на паузу и ничего не затемняет. */
public class CursorScreen extends Screen {
    public CursorScreen() {
        super(Text.literal("Spotify"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // без затемнения фона
    }
}
