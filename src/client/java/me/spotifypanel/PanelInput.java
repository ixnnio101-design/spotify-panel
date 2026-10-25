package me.spotifypanel;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/** Клавиши панели (настраиваются в «Управление») и обработка их нажатий. */
public final class PanelInput {
    public static KeyBinding interact;
    public static KeyBinding hide;
    public static KeyBinding mode;

    private static boolean pInteract, pHide, pMode;

    private PanelInput() {}

    public static void register() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(SpotifyPanelMod.ID, "main"));
        interact = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.spotify-panel.interact", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z, category));
        hide = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.spotify-panel.hide", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, category));
        mode = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.spotify-panel.mode", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, category));
    }

    /** Читает клавиши напрямую из GLFW: так они работают и пока открыт наш экран, и в обычной игре. */
    public static void update(MinecraftClient mc) {
        Screen scr = mc.currentScreen;
        boolean allowed = scr == null || scr instanceof SpotifyScreen;
        long handle = mc.getWindow().getHandle();

        boolean kInteract = isDown(handle, interact);
        boolean kHide = isDown(handle, hide);
        boolean kMode = isDown(handle, mode);

        if (allowed) {
            if (kHide && !pHide) PanelState.toggleVisible();
            if (kMode && !pMode) PanelState.toggleFixed();
            if (kInteract && !pInteract) {
                if (PanelState.interacting) PanelState.endInteraction(mc);
                else PanelState.startInteraction(mc);
            }
        }
        pInteract = kInteract;
        pHide = kHide;
        pMode = kMode;
    }

    private static boolean isDown(long handle, KeyBinding kb) {
        if (kb == null) return false;
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(kb);
        if (key == null || key.getCategory() != InputUtil.Type.KEYSYM || key.getCode() <= 0) return false;
        return GLFW.glfwGetKey(handle, key.getCode()) == GLFW.GLFW_PRESS;
    }

    public static String keyName(KeyBinding kb) {
        return kb == null ? "?" : kb.getBoundKeyLocalizedText().getString();
    }
}
