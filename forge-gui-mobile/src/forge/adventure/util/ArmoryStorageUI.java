package forge.adventure.util;

/**
 * Round 163 built the Armory storage as three text dialogs here (the storage, a guard's equipment and a
 * paged item picker). Round 168 replaced them with {@link forge.adventure.scene.ArmoryScene}, a real
 * screen shaped like the inventory (user mock-up 2026-09-10), and this class kept only the label helper
 * the roaming guard dialogs still use.
 */
public final class ArmoryStorageUI {
    private ArmoryStorageUI() {}

    /** Scales a half-button label down with its length so a long name stays inside the half button
     *  (140px landscape / 118px portrait); callers put the full name in a content row when it matters. */
    static String fit(String text) {
        // Round 164 (Android pass): the half button is 118px in portrait, not 140 - cut sooner there.
        int cap = forge.Forge.isLandscapeMode() ? 30 : 25;
        String shown = text.length() > cap ? text.substring(0, cap - 1) + "." : text;
        int len = shown.length();
        return (len <= 14 ? "[%75]" : len <= 20 ? "[%65]" : "[%55]") + shown;
    }
}
