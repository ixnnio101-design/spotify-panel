package me.spotifypanel;

import net.minecraft.client.MinecraftClient;

/** Общее состояние панели: видимость, режим Follow/Fixed, режим взаимодействия. */
public final class PanelState {
    /** Желаемая видимость (анимация идёт к ней). */
    public static boolean visible = true;
    /** Текущая «степень видимости» 0..1, управляется анимацией. */
    public static float vis = 1f;
    public static boolean fixed = false;
    public static boolean interacting = false;

    private PanelState() {}

    public static void init() {
        visible = SpotifyPanelMod.config.visible;
        vis = visible ? 1f : 0f;
        fixed = SpotifyPanelMod.config.fixed;
    }

    public static void toggleVisible() {
        visible = !visible;
        SpotifyPanelMod.config.visible = visible;
        SpotifyPanelMod.config.save();
        if (!visible) endInteraction(MinecraftClient.getInstance());
    }

    public static void toggleFixed() {
        fixed = !fixed;
        SpotifyPanelMod.config.fixed = fixed;
        SpotifyPanelMod.config.save();
    }

    public static void startInteraction(MinecraftClient mc) {
        if (!visible || vis < 0.9f || mc.currentScreen != null) return;
        interacting = true;
        mc.setScreen(new SpotifyScreen());
    }

    public static void endInteraction(MinecraftClient mc) {
        if (mc.currentScreen instanceof SpotifyScreen) {
            mc.setScreen(null);
        }
        interacting = false;
    }

    /** Вызывается самим экраном, когда он закрыт (в том числе по Esc). */
    static void onScreenClosed() {
        interacting = false;
    }
}
