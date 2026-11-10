package forge.adventure.util;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * TFR round 993: the "Show tooltips: Auto / On / Off" rule - Auto on for Easy and Normal, off for Hard and Insane (a
 * custom difficulty carries its preset's name), on with no game; On and Off overrule it both ways - and the tip text
 * helpers.
 */
public class TfrTipsTest {
    @Test
    public void autoFollowsTheDifficulty() {
        AssertJUnit.assertTrue(TfrTips.resolve("Auto", "Easy"));
        AssertJUnit.assertTrue(TfrTips.resolve("Auto", "Normal"));
        AssertJUnit.assertFalse(TfrTips.resolve("Auto", "Hard"));
        AssertJUnit.assertFalse(TfrTips.resolve("Auto", "Insane"));
        AssertJUnit.assertTrue("no game running", TfrTips.resolve("Auto", null));
        AssertJUnit.assertFalse("null mode reads as Auto", TfrTips.resolve(null, "Hard"));
        AssertJUnit.assertTrue("an unknown mode reads as Auto", TfrTips.resolve("maybe", "Easy"));
    }

    @Test
    public void onAndOffOverruleAuto() {
        AssertJUnit.assertTrue(TfrTips.resolve("On", "Insane"));
        AssertJUnit.assertTrue(TfrTips.resolve("on", "Hard"));
        AssertJUnit.assertFalse(TfrTips.resolve("Off", "Easy"));
        AssertJUnit.assertFalse(TfrTips.resolve("OFF", null));
    }

    @Test
    public void labelsAndCycle() {
        AssertJUnit.assertEquals("Auto (on)", TfrTips.label("Auto", "Normal"));
        AssertJUnit.assertEquals("Auto (off)", TfrTips.label("Auto", "Hard"));
        AssertJUnit.assertEquals("On", TfrTips.label("on", "Hard"));
        AssertJUnit.assertEquals("On", TfrTips.next("Auto"));
        AssertJUnit.assertEquals("Off", TfrTips.next("On"));
        AssertJUnit.assertEquals("Auto", TfrTips.next("Off"));
        AssertJUnit.assertEquals("Off: off", TfrTips.describe("Off", "Easy", false));
        AssertJUnit.assertEquals("Auto (Hard): off", TfrTips.describe("Auto", "Hard", false));
    }

    @Test
    public void textHelpers() {
        AssertJUnit.assertEquals("3", TfrTips.num(3.0));
        AssertJUnit.assertEquals("0.5", TfrTips.num(0.5));
        AssertJUnit.assertEquals("a [WHITE][+Gold]" + TfrTips.INK + " 5", TfrTips.ink("a [+Gold] 5"));
    }

    /**
     * Round 993 (follow-up): a wide HUD tip keeps its box. The wrap-at-170 block packed the label to a near-zero width
     * and the frame was built around that (a gold sliver, the text spilling across the map); the box now always takes
     * the packed label's own size.
     */
    @Test
    public void aWideTipKeepsItsBox() {
        float[] box = TfrTips.boxSize(470f, 36f); // hud.clock's three lines at [%60] on the world-map HUD
        AssertJUnit.assertTrue("wider than its text", box[0] >= 470f);
        AssertJUnit.assertTrue("taller than its text", box[1] >= 36f);
        AssertJUnit.assertEquals(478f, box[0], 0.001f);
        AssertJUnit.assertEquals(40f, box[1], 0.001f);
    }

    /** With no wrapping, every tip breaks its own lines ("\n"): no line runs past 125 characters, markup aside. */
    @Test
    public void tipsBreakTheirOwnLines() {
        java.io.File f = new java.io.File("../forge-gui/res/adventure/The Forsaken Realms/" + TfrTips.FILE);
        AssertJUnit.assertTrue(f.getAbsolutePath() + " not found", f.isFile());
        com.badlogic.gdx.utils.JsonValue d = new com.badlogic.gdx.utils.JsonReader().parse(new com.badlogic.gdx.files.FileHandle(f));
        int count = 0;
        for (com.badlogic.gdx.utils.JsonValue v = d.child; v != null; v = v.next, count++)
            for (String line : v.asString().split("\n"))
                AssertJUnit.assertTrue(v.name + ": a " + line.length() + "-character line - break it with \\n",
                        line.replaceAll("\\[[^\\]]*]", "").length() <= 125);
        AssertJUnit.assertTrue("tips read", count > 20);
    }

    static com.badlogic.gdx.utils.JsonValue tips() {
        java.io.File f = new java.io.File("../forge-gui/res/adventure/The Forsaken Realms/" + TfrTips.FILE);
        return new com.badlogic.gdx.utils.JsonReader().parse(new com.badlogic.gdx.files.FileHandle(f));
    }

    /** Every key a round's code asks for is in tips.json (a missing one shows no tip at all). */
    static void assertKeys(String... keys) {
        com.badlogic.gdx.utils.JsonValue d = tips();
        for (String k : keys)
            AssertJUnit.assertTrue("tips.json has no \"" + k + "\"", d.has(k));
    }

    /** Round 999 (round B): the towns', shops', Spellsmith's, Shard Trader's and Inn's keys (TfrPlaceTips). */
    @Test
    public void round999KeysExist() {
        assertKeys("shop.restock", "shop.armoryReroll", "shop.blueprint", "shop.shopType", "shop.upgradeArmory",
                "shop.manageGuards", "shop.storage", "shop.destroy", "shop.price", "shop.haggler", "shardTrader.buy",
                "shardTrader.sell", "smith.edition", "smith.color", "smith.rarity", "smith.cost", "smith.pull",
                "smith.pullPaid", "smith.shards", "inn.tempHp", "inn.tempHpBarred", "inn.tempHpBlessed", "inn.tempHpClosed",
                "inn.reroll", "inn.sell", "inn.entryFee", "inn.regular", "town.restore", "town.capitol",
                "town.capitolMages", "town.capitolTowns", "town.capitolOrazca", "town.specialtyChoose",
                "town.specialtyLocked", "town.specialtyChange", "town.realm");
    }

    /** Round 1000 (round C): Ascendance, the quest log, the journal, the character sheet, standings, inventory, research. */
    @Test
    public void round1000KeysExist() {
        assertKeys("asc.power", "asc.mainItems", "asc.companions", "asc.reroll", "asc.rerollOnce", "asc.rerollMany",
                "quest.abandon", "quest.track", "quest.daysLeft", "quest.legend", "journal.streak", "journal.coin",
                "journal.land", "character.notoriety", "character.toRecords", "character.toAchievements",
                "standings.status", "standings.attack", "standings.attackUsual", "standings.cheaper", "standings.pricier",
                "standings.samePrice", "standings.partner",
                "standings.war", "inventory.maps", "inventory.sets", "research.sell", "research.sacrifice",
                "research.overFour", "research.hideUntouched");
    }

    /** Round 1001 (round D): the world-map tokens' and the custom difficulty screen's keys. */
    @Test
    public void round1001KeysExist() {
        assertKeys("map.guard", "map.guardCapital", "map.ruin", "map.obelisk", "map.xmark", "map.pile", "map.mystery",
                "map.chest", "custom.basic", "custom.expert", "custom.changedOnly", "custom.reset");
    }

    /** Round 1001: a knob's own tooltip (no "\n" of its own) is broken at spaces, no line past the limit, nothing lost. */
    @Test
    public void plainTextWraps() {
        String t = "Enemy life: every enemy's starting life is multiplied by this. [GOLD]Higher[] is harder, and a boss counts too.";
        String w = TfrTips.wrap(t, 40);
        for (String line : w.split("\n"))
            AssertJUnit.assertTrue(line + " is " + line.length(), line.replaceAll("\\[[^\\]]*]", "").length() <= 40);
        AssertJUnit.assertEquals(t, w.replace('\n', ' '));
        AssertJUnit.assertEquals("short", TfrTips.wrap("short", 40));
    }

    /** Every {asc.FIELD} a tip names is a real AscendanceData field (a typo would show "?"). */
    @Test
    public void ascendanceFillsExist() throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{asc\\.(\\w+)}").matcher(tips().toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json));
        int n = 0;
        while (m.find()) {
            forge.adventure.data.AscendanceData.class.getField(m.group(1));
            n++;
        }
        AssertJUnit.assertTrue("asc fills found", n > 0);
    }

    /**
     * The resource-icon rule (the user's standing rule): an amount is "[+Gold] 50", never "50 gold" - and no tip writes
     * a bare amount after a resource word either ("gold 50"). Numbers come from {fills}, so a digit before a resource
     * word can only be a literal someone typed.
     */
    @Test
    public void amountsUseTheIcons() {
        java.util.regex.Pattern bare = java.util.regex.Pattern.compile(
                "(?i)(\\d+|\\{[\\w.]+})\\s*(gold|shards?|wood|stone|reputation)\\b");
        for (com.badlogic.gdx.utils.JsonValue v = tips().child; v != null; v = v.next) {
            String t = v.asString().replaceAll("\\[\\+\\w+]", "");
            AssertJUnit.assertFalse(v.name + ": an amount without its icon: " + v.asString(), bare.matcher(t).find());
        }
    }

    /** The Spellsmith's tips read the multipliers its price uses (round 999 named them; the price math uses the same). */
    @Test
    public void spellsmithMultipliers() {
        AssertJUnit.assertEquals("4", TfrTips.num(forge.adventure.scene.SpellSmithScene.EDITION_MULT));
        AssertJUnit.assertEquals("2.5", TfrTips.num(forge.adventure.scene.SpellSmithScene.COLOR_MULT));
        AssertJUnit.assertEquals("5", TfrTips.num(Math.min(2 * forge.adventure.scene.SpellSmithScene.COLOR_MULT,
                forge.adventure.scene.SpellSmithScene.COLOR_MAX)));
        AssertJUnit.assertEquals("5.5", TfrTips.num(forge.adventure.scene.SpellSmithScene.RARITY_MULT[3]));
        AssertJUnit.assertEquals("20", TfrTips.num(Math.round(forge.adventure.scene.SpellSmithScene.SHARD_SHARE * 100f)));
    }
}
