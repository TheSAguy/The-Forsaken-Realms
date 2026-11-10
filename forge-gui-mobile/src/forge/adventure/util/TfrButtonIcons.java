package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import forge.adventure.scene.Scene;

/**
 * Round 1007 (Art, 2026-11-10; the user: "Can we now do a full review of TFR1's buttons/menus and let's populate them with
 * icons", then "Yes, install"): pixel icons on the dialog buttons, one map from a button's label to its icon in
 * sprites/button_icons.atlas (Nearest, 32 px art and its _48 twin), shown left of the label with the pair centered
 * (layout A, as TFR 2's Tfr2ButtonIcons). Opt-in per dialog: a dialog asks for icons (decorate / the MapDialog hook /
 * the Card Lost row); Controls.newTextButton is unchanged, so management screens, the HUD and Back/Close/Cancel keep
 * their text and their existing [+Glyph] markup.
 * <ul>
 *   <li>leave (the boot): Leave, (Leave) and any answer ending " (Leave)", Walk away, Turn Back - a walk away, never
 *       Cancel, Back or Not now</li>
 *   <li>enter (the open gate): Enter, Pass through, Open the gate; attack (the crossed swords): Attack, Attack (two
 *       Archmages), Attack again in N days, (Fight), Prepare yourself!</li>
 *   <li>orders (the sealed scroll): Browse quests and every "... (Accept Quest)" / "(Accept)" answer; questdone (the star
 *       medal): every "... Complete Quest)", "(Quest Complete)", "(Take your reward)"</li>
 *   <li>withdraw (the white flag): (Withdraw), (Flee); buy (the coin purse): Pay ..., Buy Back ...; build (the hammer
 *       over stone blocks): Restore town, Rebuild, Repair ..., Restore Armory, Upgrade ...</li>
 *   <li>Card Lost row (Forge's FOptionPane): its OK takes lost (the broken sword), Bronze Coin takes coin (Forge's
 *       Bronze Challenge Coin item sprite)</li>
 * </ul>
 * The art is TFR 2's (Art Library: Projects/The Forsaken Realms II/Game Art/Duel Choice Icons) and two new icons
 * (Projects/The Forsaken Realms/Game Art/Button Icons (round 1007)); dev-tools/item_icons/pack_button_icons.py packs the sheet.
 */
public final class TfrButtonIcons {
    private TfrButtonIcons() {
    }

    public static final String ATLAS = "sprites/button_icons.atlas";
    /** The icon box and the gap to the label, in stage units (Scene's 270-unit-tall stage: 32 + 8 px at 720p, 48 + 12 at
     *  1080p). */
    public static final float BOX = 12, GAP = 3;
    private static Boolean atlasPresent;

    /** A label without its markup ([%88], [GOLD], [+Gold]...) and with its line breaks as spaces. */
    public static String plain(String label) {
        return label == null ? "" : label.replaceAll("\\[[^\\]]*\\]", "").replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    /** The label's icon region, or null: text only. */
    public static String regionOf(String label) {
        String t = plain(label);
        switch (t) {
            case "Leave":
            case "(Leave)":
            case "Walk away":
            case "Turn Back":
                return "leave";
            case "Enter":
            case "Pass through":
            case "Open the gate":
                return "enter";
            case "Attack":
            case "(Fight)":
            case "Prepare yourself!":
                return "attack";
            case "(Withdraw)":
            case "(Flee)":
                return "withdraw";
            case "Browse quests":
            case "(Accept)":
                return "orders";
            case "(Quest Complete)":
            case "(Take your reward)":
                return "questdone";
            case "Rebuild":
                return "build";
            default:
                break;
        }
        if (t.startsWith("Attack (") || t.startsWith("Attack again"))
            return "attack";
        if (t.endsWith(" (Leave)"))
            return "leave";       // a spoken answer that walks away: "Okay." (Leave)
        if (t.endsWith("(Accept Quest)"))
            return "orders";
        if (t.endsWith("Complete Quest)"))
            return "questdone";
        if (t.startsWith("Pay ") || t.startsWith("Buy Back"))
            return "buy";
        if (t.startsWith("Restore town") || t.startsWith("Rebuild ") || t.startsWith("Repair ") || t.startsWith("Restore Armory")
                || t.startsWith("Upgrade "))
            return "build";
        if (t.endsWith("Bronze Coin"))
            return "coin";
        return null;
    }

    /** True when the screen draws the 48 px art: the 12-unit box comes out nearer a whole multiple of 48 than of 32
     *  (TFR 2's rule, Tfr2Levels.rankBig): 1080p and 4K take 48, 720p and 1440p take 32. */
    static boolean big() {
        float box = BOX * Math.min(Gdx.graphics.getBackBufferWidth() / (float) Scene.getIntendedWidth(),
                Gdx.graphics.getBackBufferHeight() / (float) Scene.getIntendedHeight());
        return box >= 46 && offWhole(box / 48) <= offWhole(box / 32);
    }

    private static float offWhole(float scale) {
        return Math.abs(scale - Math.round(scale));
    }

    /** region's art at the screen's size, or null (the atlas or the region missing: the button keeps its text). */
    public static TextureRegion icon(String region) {
        if (region == null)
            return null;
        if (atlasPresent == null) {
            com.badlogic.gdx.files.FileHandle f = Config.instance().getFile(ATLAS);
            atlasPresent = f != null && f.exists();
            if (!atlasPresent)
                System.out.println("[TFR-ButtonIcons] no " + ATLAS + ": every button stays text only");
        }
        if (!atlasPresent)
            return null;
        try {
            return Config.instance().getAtlasSprite(ATLAS, region + (big() ? "_48" : ""));
        } catch (RuntimeException e) {
            System.out.println("[TFR-ButtonIcons] " + region + " unavailable (" + e + "): text only");
            return null;
        }
    }

    /** b, whose label is text, with that label's icon left of it (regionOf), the pair centered; b as it was when the
     *  label has none. where: the dialog, for the log line. */
    public static TextraButton decorate(String where, TextraButton b, String text) {
        return decorate(where, b, regionOf(text), text, 0);
    }

    /** The same for a button whose label wraps (MapDialog's options): wrapWidth is the button's inner width in stage
     *  units. A label that fits beside the icon stays one line and the pair is centered; a longer one wraps in the room
     *  beside the icon. */
    public static TextraButton decorate(String where, TextraButton b, String text, float wrapWidth) {
        return decorate(where, b, regionOf(text), text, wrapWidth);
    }

    /** b with region's icon (null: b as it was). text: the label, for the log line. */
    public static TextraButton decorate(String where, TextraButton b, String region, String text, float wrapWidth) {
        TextureRegion art = b == null ? null : icon(region);
        if (art == null)
            return b;
        TextraLabel label = b.getTextraLabel();
        b.clearChildren();
        b.add(new Image(art)).size(BOX).padRight(GAP);
        Cell<TextraLabel> cell = b.add(label);
        if (wrapWidth > 0) {
            float room = wrapWidth - BOX - GAP;
            label.setWrap(false);
            float natural = label.getPrefWidth();
            if (natural > room) {
                label.setWrap(true);
                cell.width(room);
            }
        }
        System.out.println("[TFR-ButtonIcons] " + where + ": \"" + plain(text) + "\" -> " + region + " (" + art.getRegionWidth() + " px)");
        return b;
    }

    /**
     * The Card Lost row (DuelScene.showAnteCardPopup, Forge's FOptionPane): its OK takes the broken sword, Buy Back the
     * purse, Bronze Coin the coin; a won card's row (Card Gained: OK, Auto-Sell) stays text. Sized as the dialogs' icons:
     * 12 stage units in screen pixels.
     */
    public static void anteRow(forge.toolbox.FDialog popup, String title, java.util.List<String> buttons) {
        if (popup == null || !"Card Lost".equals(title))
            return;
        float px = BOX * Gdx.graphics.getBackBufferHeight() / (float) Scene.getIntendedHeight();
        float gap = GAP * Gdx.graphics.getBackBufferHeight() / (float) Scene.getIntendedHeight();
        StringBuilder line = new StringBuilder();
        for (int k = 0; k < buttons.size(); k++) {
            String label = buttons.get(k);
            String region = k == 0 ? "lost" : regionOf(label);
            TextureRegion art = icon(region);
            if (art == null)
                continue;
            popup.setButtonIcon(k, art, px, gap);
            line.append(line.length() > 0 ? ", " : "").append('"').append(plain(label)).append("\" -> ").append(region);
        }
        if (line.length() > 0)
            System.out.println("[TFR-ButtonIcons] the Card Lost row: " + line + " (" + Math.round(px) + " px)");
    }
}
