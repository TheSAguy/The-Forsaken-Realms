package forge.adventure.util;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.github.tommyettinger.textra.TextraButton;
import forge.adventure.data.AdventureQuestData;

import java.util.function.Supplier;

/**
 * Round 1000 (the tooltip audit's round C, spots 23-28 and 47-52): hover tips on the Ascendance dialogs, the quest log,
 * the journal, the character sheet, the world standings, the inventory and the Research Lab. Each screen hands its
 * widgets over in one call; every text is a tips.json key with its numbers filled where the game reads them. A tip in a
 * map dialog (the Ascendance status and level-up) uses {@link TfrTips#MAP_DIALOG}, not the HUD's null scene, which
 * stands down while a dialog holds the input. [TFR-Tips].
 */
public final class TfrScreenTips {
    private TfrScreenTips() {
    }

    // ------------------------------------------------------------------ Ascendance (map dialogs)

    /** AscendanceUI.openStatus: the Power row, the main items row and the companions row. */
    public static void ascendanceStatus(Actor power, Actor mainItems, Actor companions) {
        if (!TfrTips.enabled())
            return;
        Object scene = TfrTips.MAP_DIALOG;
        TfrTips.region(scene, power, "asc.power");
        TfrTips.region(scene, mainItems, "asc.mainItems");
        TfrTips.region(scene, companions, "asc.companions");
    }

    /** AscendanceUI.openChoice: the level-up's Re-roll. */
    public static void ascendanceReroll(Actor reroll) {
        if (!TfrTips.enabled())
            return;
        TfrTips.region(TfrTips.MAP_DIALOG, reroll, "asc.reroll", () -> TfrTips.text("asc.reroll",
                "cost", Ascendance.rerollCost(), "per", Ascendance.rerollsPerLevel() > 1
                        ? TfrTips.text("asc.rerollMany", "n", Ascendance.rerollsPerLevel()) : TfrTips.text("asc.rerollOnce")));
    }

    // ------------------------------------------------------------------ the quest log

    /** QuestLogScene's constructor: Abandon and Track. */
    public static void questButtons(Object scene, Actor abandon, Actor track) {
        if (!TfrTips.enabled())
            return;
        TfrTips.region(scene, abandon, "quest.abandon");
        TfrTips.region(scene, track, "quest.track");
    }

    /** QuestLogScene: a quest's name (list or detail); a tip only while it shows a time limit. */
    public static void questName(Object scene, Actor label, AdventureQuestData quest) {
        if (!TfrTips.enabled() || quest == null)
            return;
        TfrTips.region(scene, label, "quest.daysLeft", () -> QuestExpiry.questLogSuffix(quest).isEmpty() ? null
                : TfrTips.text("quest.daysLeft"));
    }

    /** QuestLogScene: a roaming legend's row. */
    public static void legend(Object scene, Actor label) {
        if (!TfrTips.enabled())
            return;
        TfrTips.region(scene, label, "quest.legend");
    }

    // ------------------------------------------------------------------ the journal

    /** JournalScene: an entry's streak pip (cards: how many it starts with). */
    public static void journalStreak(Object scene, Actor pip, int cards) {
        if (!TfrTips.enabled() || pip == null)
            return;
        TfrTips.region(scene, pip, "journal.streak", () -> TfrTips.text("journal.streak", "cards", cards));
    }

    /** JournalScene: the Bronze Coin an enemy holds. */
    public static void journalCoin(Object scene, Actor coin) {
        if (!TfrTips.enabled() || coin == null)
            return;
        TfrTips.region(scene, coin, "journal.coin");
    }

    /** JournalScene: a land filter pip. */
    public static void journalLand(Object scene, Actor pip, String land) {
        if (!TfrTips.enabled() || pip == null)
            return;
        TfrTips.region(scene, pip, "journal.land", () -> TfrTips.text("journal.land", "land", land));
    }

    // ------------------------------------------------------------------ the character sheet

    /** PlayerStatisticScene: the Notoriety line and the achievements / records toggle. */
    public static void character(Object scene, Actor notoriety, TextraButton toggle) {
        if (!TfrTips.enabled())
            return;
        TfrTips.region(scene, notoriety, "character.notoriety", () -> {
            String shown = notoriety instanceof com.github.tommyettinger.textra.TextraLabel
                    ? ((com.github.tommyettinger.textra.TextraLabel) notoriety).storedText : "x";
            return shown == null || shown.isEmpty() ? null : TfrTips.text("character.notoriety"); // blank: no Notoriety here
        });
        if (toggle != null)
            TfrTips.region(scene, toggle, "character.toggle", () -> TfrTips.text(
                    String.valueOf(toggle.getText()).contains("VS") ? "character.toRecords" : "character.toAchievements"));
    }

    // ------------------------------------------------------------------ the world standings

    /** WorldStandingsScene: a color's Status cell - what that standing does, with the live factors. */
    public static void standingStatus(Object scene, Actor cell, String colorKey) {
        if (!TfrTips.enabled() || cell == null || colorKey == null)
            return;
        TfrTips.region(scene, cell, "standings.status", () -> {
            ColorReputation.Status s = ColorReputation.getStatus(colorKey);
            float price = ColorReputation.getShopPriceMultiplier(colorKey), attack = ColorReputation.getPlayerTownAttackWeight(colorKey);
            String priceText = price < 1f ? TfrTips.text("standings.cheaper", "pct", pct(1f - price))
                    : price > 1f ? TfrTips.text("standings.pricier", "pct", pct(price - 1f)) : TfrTips.text("standings.samePrice");
            String extra = s == ColorReputation.Status.PARTNER ? TfrTips.text("standings.partner")
                    : s == ColorReputation.Status.WAR ? TfrTips.text("standings.war") : null;
            String attackText = attack == 1f ? TfrTips.text("standings.attackUsual")
                    : TfrTips.text("standings.attack", "factor", TfrTips.num(attack));
            return TfrTips.text("standings.status", "status", s.label, "prices", priceText, "attack", attackText)
                    + (extra == null ? "" : "\n" + extra);
        });
    }

    private static String pct(float f) {
        return TfrTips.num(Math.round(f * 100f));
    }

    // ------------------------------------------------------------------ the inventory and the Research Lab

    /** InventoryScene's constructor: the treasure maps and gear sets buttons. */
    public static void inventory(Object scene, Actor maps, Actor sets) {
        if (!TfrTips.enabled())
            return;
        TfrTips.region(scene, maps, "inventory.maps");
        TfrTips.region(scene, sets, "inventory.sets");
    }

    /** ResearchScene: the found-card window's Sell, Sacrifice and Sell all over N; the Hide untouched box. */
    public static void research(Object scene, Actor sell, Actor sacrifice, Actor overFour) {
        if (!TfrTips.enabled())
            return;
        TfrTips.region(scene, sell, "research.sell");
        TfrTips.region(scene, sacrifice, "research.sacrifice");
        TfrTips.region(scene, overFour, "research.overFour", () -> TfrTips.text("research.overFour", "keep", ResearchMeter.KEEP_COPIES));
    }

    /** ResearchScene's constructor: the hide box whose words change by save ("Hide untouched" in a v2 game). */
    public static void researchHide(Object scene, Actor box, Supplier<String> shown) {
        if (!TfrTips.enabled())
            return;
        TfrTips.region(scene, box, "research.hideUntouched", () -> "Hide untouched".equals(shown.get())
                ? TfrTips.text("research.hideUntouched") : null);
    }
}
