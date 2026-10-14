package forge.adventure.util;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import forge.Forge;
import forge.adventure.data.EnemyData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.MapStage;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.card.CardRarity;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Round 559 - the Capitol Temple (the user: "After you build it and you enter it, it will show you a kill count by color
 * for the 5 colors ... Multi color enemies will be split, so a red/green will be .5 per color. 3 color will be split 3
 * ways ... At the end of the week, you get a rare card for the color with the most kills. 2 uncommons for the second
 * place and 4 commons for 3rd. Randomly All from the sets you've unlocked ... at the start of day 8 (If that was the
 * first week) then it starts over. - Player Cap only").
 * <p>
 * The tally (World.getTempleKills) counts every duel the player wins except the Arena and an Inn tournament - from the
 * first day, whether or not a Temple stands; it pays only if the Capitol has one when the week turns. Ties rank in WUBRG
 * order, the order of the chart. Round 582: the cards wait at the Temple, week after week, until the player collects
 * them there (HeldCards) - opened as a booster is.
 */
public final class TempleOfferings {
    static final String[] LETTERS = {"W", "U", "B", "R", "G"};
    static final String[] NAMES = {"white", "blue", "black", "red", "green"};
    private static final byte[] BITS = {MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK, MagicColor.RED, MagicColor.GREEN};
    /** The bars' colors, W U B R G. */
    private static final Color[] BAR_COLORS = {new Color(0.93f, 0.89f, 0.72f, 1f), new Color(0.24f, 0.49f, 0.84f, 1f),
            new Color(0.36f, 0.30f, 0.38f, 1f), new Color(0.84f, 0.26f, 0.17f, 1f), new Color(0.22f, 0.60f, 0.29f, 1f)};
    /** Cards for 1st, 2nd and 3rd place. */
    private static final int[] PLACE_COUNT = {1, 2, 4};
    private static final CardRarity[] PLACE_RARITY = {CardRarity.Rare, CardRarity.Uncommon, CardRarity.Common};
    private static final String[] PLACE_NAMES = {"1st", "2nd", "3rd"};
    private static final float BAR_MAX = 120f;
    private static final Random RAND = new Random();
    private static Texture white;

    private TempleOfferings() {}

    /** Does the Capitol have a Temple? */
    public static boolean capitolHasTemple() {
        for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest()) {
            if (TownRestoration.CAPITOL_POI_NAME.equals(poi.getData().name)) {
                PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
                return changes != null && changes.hasEconomyBuildingOfType(EconomyBuildings.TEMPLE);
            }
        }
        return false;
    }

    /** DuelScene: the player won a duel (not the Arena, not an Inn tournament) - the enemy's colors share one kill. */
    public static void recordKill(EnemyData enemy) {
        recordKill(enemy, "you");
    }

    /** Round 594 (the user: "Your Guard victories, both local and roaming should count towards the temple counts"): the
     *  same count for a win by one of the player's guards - a town guard repelling a mage (TerritoryControl), a roaming
     *  guard beating a mage or a raider, watched or simulated (RoamingGuardRuntime). {@code by} is for the log only. */
    public static void recordKill(EnemyData enemy, String by) {
        if (enemy == null)
            return;
        World world = WorldSave.getCurrentSave().getWorld();
        int week = world.getCurrentWeek();
        if (world.getTempleWeek() != week) { // the first count of a save, or a week the day tick did not turn
            if (world.getTempleWeek() >= 0)
                System.out.println("[TFR-Temple] the tally was still on week " + world.getTempleWeek() + " at a kill in week "
                        + week + " - cleared unpaid");
            java.util.Arrays.fill(world.getTempleKills(), 0f);
            world.setTempleWeek(week);
        }
        String colors = enemy.colors == null ? "" : enemy.colors.toUpperCase();
        int n = 0;
        for (String letter : LETTERS)
            if (colors.contains(letter))
                n++;
        if (n == 0) {
            System.out.println("[TFR-Temple] " + enemy.getName() + " (colorless) - not counted");
            return;
        }
        float[] kills = world.getTempleKills();
        for (int c = 0; c < LETTERS.length; c++)
            if (colors.contains(LETTERS[c]))
                kills[c] += 1f / n;
        System.out.println("[TFR-Temple] " + enemy.getName() + " (" + colors + ") +" + fmt(1f / n) + " each - week "
                + (week + 1) + " tally " + tally(kills) + " - beaten by " + by);
    }

    /** EconomyBuildings.processPaydays: a new day. When it starts a new week, the Temple pays and the tally clears. */
    public static void onDaysPassed(int newDay) {
        World world = WorldSave.getCurrentSave().getWorld();
        int week = World.weekOf(newDay);
        int tallied = world.getTempleWeek();
        if (tallied == week)
            return;
        if (tallied >= 0 && tallied < week)
            pay(world.getTempleKills(), tallied);
        java.util.Arrays.fill(world.getTempleKills(), 0f);
        world.setTempleWeek(week);
    }

    /** The colors with kills, most first; ties in WUBRG order. */
    static List<Integer> ranking(float[] kills) {
        List<Integer> ranked = new ArrayList<>();
        for (int c = 0; c < kills.length; c++)
            if (kills[c] > 0.001f)
                ranked.add(c);
        ranked.sort((a, b) -> Float.compare(kills[b], kills[a])); // stable: a tie keeps WUBRG order
        return ranked;
    }

    private static void pay(float[] kills, int week) {
        List<Integer> ranked = ranking(kills);
        if (ranked.isEmpty())
            return;
        if (!capitolHasTemple()) {
            System.out.println("[TFR-Temple] week " + (week + 1) + " ended (" + tally(kills) + ") - no Temple, nothing paid");
            return;
        }
        Set<String> unlocked = new HashSet<>(WorldSave.getCurrentSave().getPlayer().getUnlockedEditions());
        List<PaperCard> inSets = new ArrayList<>();
        for (PaperCard card : forge.model.FModel.getMagicDb().getCommonCards().getAllCards())
            if (unlocked.contains(card.getEdition()) && !card.getRules().getType().isLand())
                inSets.add(card);
        List<PaperCard> offering = new ArrayList<>();
        StringBuilder log = new StringBuilder();
        StringBuilder notice = new StringBuilder();
        int times = Ascendance.templeCardMultiplier(); // round 566: Temple Priest
        for (int place = 0; place < PLACE_COUNT.length && place < ranked.size(); place++) {
            int c = ranked.get(place);
            List<PaperCard> pool = pool(inSets, BITS[c], PLACE_RARITY[place]);
            int got = 0;
            for (int i = 0; i < PLACE_COUNT[place] * times && !pool.isEmpty(); i++) {
                PaperCard card = pool.remove(RAND.nextInt(pool.size()));
                offering.add(card);
                log.append(log.length() == 0 ? "" : ", ").append(card.getName());
                got++;
            }
            String kind = PLACE_RARITY[place].name().toLowerCase();
            notice.append(notice.length() == 0 ? "" : ", ").append(got).append(' ').append(NAMES[c]).append(' ')
                    .append(kind).append(got == 1 ? "" : "s");
            log.append(" (").append(PLACE_NAMES[place]).append(' ').append(LETTERS[c]).append(' ').append(fmt(kills[c]))
                    .append(", ").append(got).append(' ').append(kind).append(got == PLACE_COUNT[place] * times ? "" : " - pool ran out")
                    .append(")");
        }
        System.out.println("[TFR-Temple] week " + (week + 1) + " ended (" + tally(kills) + ") - " + offering.size() + " card(s)"
                + (times > 1 ? " (Temple Priest x" + times + ")" : "") + ": " + log);
        if (offering.isEmpty())
            return;
        // Round 582: the offering waits at the Temple, with any earlier weeks', until the player collects it there.
        HeldCards.holdTempleOffering(offering);
        GameHUD.getInstance().addNotification("The Temple's offering for week " + (week + 1) + " is ready: " + notice
                + ". Collect it at the Temple.");
    }

    /** One color's cards of a rarity in the unlocked sets, one printing a name - mono-colored first, else any with it. */
    private static List<PaperCard> pool(List<PaperCard> inSets, byte color, CardRarity rarity) {
        for (boolean monoOnly : new boolean[]{true, false}) {
            List<PaperCard> pool = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (PaperCard card : inSets) {
                ColorSet colors = card.getRules().getColor();
                if (card.getRarity() != rarity || !colors.hasAnyColor(color) || (monoOnly && !colors.isMonoColor()))
                    continue;
                if (names.add(card.getName()))
                    pool.add(card);
            }
            if (!pool.isEmpty())
                return pool;
        }
        return new ArrayList<>();
    }

    // ---------------------------------------------------------------------------------------------------- the screen

    /** ShopActor: the player walked into the Capitol's Temple - this week's kills by color, as a bar chart. */
    public static void openTempleDialog(MapStage stage, int objectId) {
        refreshTempleDialog(stage, objectId);
        stage.showDialog();
    }

    private static void refreshTempleDialog(MapStage stage, int objectId) {
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        World world = WorldSave.getCurrentSave().getWorld();
        float[] kills = world.getTempleWeek() == world.getCurrentWeek() ? world.getTempleKills() : new float[5];
        int day = world.getCurrentDay();
        int daysLeft = World.nextWeekBoundary(day) - day;
        EconomyBuildings.addContentRow(dialog, "Temple");
        int times = Ascendance.templeCardMultiplier(); // round 566: Temple Priest
        EconomyBuildings.addContentRow(dialog, "[%80]Defeat enemies to honor their colors. When the week ends, the most "
                + "honored color gives " + cardsText(PLACE_COUNT[0] * times, "rare card") + ", the second "
                + cardsText(PLACE_COUNT[1] * times, "uncommon") + " and the third " + cardsText(PLACE_COUNT[2] * times, "common")
                + ", from the sets you have unlocked" + (times > 1 ? " (Temple Priest)" : "") + ".[%]");
        List<Integer> ranked = ranking(kills);
        float most = 0f;
        for (float k : kills)
            most = Math.max(most, k);
        // round 576: rows most honored first (the payout's order, so the place tags read top-down), then the
        // colors with no honor in WUBRG order
        List<Integer> rows = new ArrayList<>(ranked);
        for (int c = 0; c < LETTERS.length; c++)
            if (!rows.contains(c))
                rows.add(c);
        Table chart = new Table();
        for (int c : rows) {
            chart.add(Controls.newTextraLabel("[+" + LETTERS[c] + "]")).padRight(4f).padBottom(2f);
            Image bar = new Image(new TextureRegionDrawable(whitePixel()));
            bar.setColor(BAR_COLORS[c]);
            float width = most <= 0f || kills[c] <= 0f ? 1f : Math.max(3f, BAR_MAX * kills[c] / most);
            Table track = new Table();
            track.left();
            track.add(bar).width(width).height(9f);
            chart.add(track).width(BAR_MAX).left().padBottom(2f);
            int place = ranked.indexOf(c);
            String count = fmt(kills[c]) + (place >= 0 && place < PLACE_NAMES.length ? "  [%75]" + PLACE_NAMES[place] + "[%]" : "");
            chart.add(Controls.newTextraLabel(count)).width(56f).left().padLeft(4f).padBottom(2f).row();
        }
        dialog.getContentTable().add(chart).padTop(4f).padBottom(4f).row();
        // Round 582: the offerings not yet collected - every week's since the last visit. On the week's line and beside
        // Destroy Building, so the dialog grows no taller: it already fills a landscape screen.
        int waiting = HeldCards.temple().countAll();
        int weeks = HeldCards.templeWeeks();
        EconomyBuildings.addContentRow(dialog, "[%80]The week ends in " + daysLeft + (daysLeft == 1 ? " day" : " days") + "."
                + (waiting > 0 ? " Waiting for you: " + cardsText(waiting, "card") + (weeks > 0 ? " from " + cardsText(weeks, "week") : "")
                + "." : "") + "[%]");
        Runnable destroy = () -> EconomyBuildings.openDestroyConfirmDialog(stage, objectId, () -> refreshTempleDialog(stage, objectId));
        if (waiting > 0) {
            int[] column = {0};
            EconomyBuildings.addHalfButton(dialog, column, "Collect Offering", true, () -> {
                stage.hideDialog();
                HeldCards.collectTemple();
            });
            EconomyBuildings.addHalfButton(dialog, column, "Destroy Building", true, destroy);
            float half = Forge.isLandscapeMode() ? 140f : 118f; // EconomyBuildings.addHalfButton's width
            dialog.getButtonTable().add(Controls.newTextButton("Close", stage::hideDialog)).colspan(2).width(2 * half).row();
        } else {
            EconomyBuildings.addButtonRow(dialog, "Destroy Building", true, destroy);
            dialog.getButtonTable().add(Controls.newTextButton("Close", stage::hideDialog)).width(240f).row();
        }
        dialog.setKeepWithinStage(true);
    }

    private static Texture whitePixel() {
        if (white == null) {
            Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pixmap.setColor(Color.WHITE);
            pixmap.fill();
            white = new Texture(pixmap);
            pixmap.dispose();
        }
        return white;
    }

    private static String cardsText(int n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }

    /** 8, 2.5 or 1.33 - a whole count without its decimals. */
    static String fmt(float value) {
        float rounded = Math.round(value * 100f) / 100f;
        if (Math.abs(rounded - Math.round(rounded)) < 0.005f)
            return String.valueOf(Math.round(rounded));
        String text = String.format(java.util.Locale.ROOT, "%.2f", rounded);
        return text.endsWith("0") ? text.substring(0, text.length() - 1) : text;
    }

    private static String tally(float[] kills) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < LETTERS.length; c++)
            sb.append(c == 0 ? "" : " ").append(LETTERS[c]).append(' ').append(fmt(kills[c]));
        return sb.toString();
    }
}
