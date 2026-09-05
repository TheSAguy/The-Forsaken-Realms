package forge.adventure.util;

import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.player.AdventurePlayer;
import forge.card.ColorSet;

import java.util.HashMap;
import java.util.Map;

/**
 * Player reputation with the 5 AI colors (MOD_SCOPE.md #1), first slice: scoring only, no
 * consequences yet. Core invariant, per explicit user design: the 5 values ALWAYS sum to zero -
 * every reputation event is a zero-sum redistribution across the wheel, never a plain gain/loss.
 * <p>
 * Rules (user-specified):
 * <ul>
 * <li>Winning a fight against a mono-color enemy: that color -2, each of its 2 allies -1, each of
 * its 2 enemies +2 (sums to 0). Losing has no effect. Colorless enemies have no effect.</li>
 * <li>Multicolor enemy: HALF that pattern, applied once per color of the enemy (user's choice
 * among the offered options). Halving -1 doesn't round cleanly, so reputation is STORED IN
 * HALF-POINTS internally (every user-facing amount doubled) - all cases stay exact integers and
 * the net-zero invariant holds precisely; only the display divides by 2 (see displayValue()).</li>
 * <li>Boss fights count 3x (EnemyData.boss).</li>
 * <li>Arena and Inn-tournament duels are excluded - the caller (DuelScene) checks that, since
 * that's where the isArena/eventData flags live.</li>
 * <li>Starting deck: +10 to each of the deck's identity colors, +5 to each of their allies, -10
 * to each of their enemies (also zero-sum per color; a colorless starter grants nothing).</li>
 * </ul>
 * Ally/enemy wheel is the standard MTG color pie adjacency, same table MOD_SCOPE.md's own header
 * documents (and future Territory Control cross-color targeting will use - keep them in sync).
 */
public class ColorReputation {
    // Same canonical order TerritoryControl.COLORS uses (that array is territory-specific;
    // duplicating the 5 names here keeps this class free of a territory-control dependency,
    // since reputation is meant to work even with territoryControlEnabled off).
    public static final String[] COLORS = {"white", "blue", "black", "red", "green"};

    private static final Map<String, String[]> ALLIES = new HashMap<>();
    private static final Map<String, String[]> ENEMIES = new HashMap<>();
    static {
        ALLIES.put("white", new String[]{"green", "blue"});
        ALLIES.put("blue", new String[]{"white", "black"});
        ALLIES.put("black", new String[]{"blue", "red"});
        ALLIES.put("red", new String[]{"black", "green"});
        ALLIES.put("green", new String[]{"red", "white"});

        ENEMIES.put("white", new String[]{"black", "red"});
        ENEMIES.put("blue", new String[]{"red", "green"});
        ENEMIES.put("black", new String[]{"green", "white"});
        ENEMIES.put("red", new String[]{"white", "blue"});
        ENEMIES.put("green", new String[]{"blue", "black"});
    }

    // All amounts in INTERNAL HALF-POINTS (user-facing value x2) - see class comment.
    private static final int FIGHT_TARGET = -4;   // displayed -2
    private static final int FIGHT_ALLY = -2;     // displayed -1
    private static final int FIGHT_ENEMY = 4;     // displayed +2
    private static final int BOSS_MULTIPLIER = 3;
    // Territory Control (MOD_SCOPE.md #7) mage kills: 2x the ordinary win pattern, user request -
    // mutually exclusive with BOSS_MULTIPLIER in practice (attack mages aren't tagged boss), but
    // written as an independent case rather than assuming that stays true forever.
    private static final int MAGE_KILL_MULTIPLIER = 2;
    private static final int START_TARGET = 20;   // displayed +10
    private static final int START_ALLY = 10;     // displayed +5
    private static final int START_ENEMY = -20;   // displayed -10

    private ColorReputation() {}

    public static boolean isEnabled() {
        ConfigData configData = Config.instance().getConfigData();
        return configData != null && configData.colorReputationEnabled;
    }

    // ---- Consequences (second slice, user's tier table). Thresholds are DISPLAY values.
    // Tier labels settled after two rounds of user correction: Unhappy is the MODERATE negative
    // tier, War the SEVERE one (final answer, 2026-08-07 - the effects were always bound to the
    // scale rows; only these display strings moved around). ----
    public enum Status {
        PARTNER("Partner"),   // rep >= 80: 30% cheaper, town 75% less likely to be mage-targeted (weight x0.25), free Inn overheal
        HAPPY("Happy"),       // 30..79:    15% cheaper, 50% less likely (x0.50)
        NEUTRAL("Neutral"),   // -29..29:   no effect
        UNHAPPY("Unhappy"),   // -79..-30:  25% pricier, 15% more likely (x1.15)
        WAR("War");           // <= -80:    towns barred (capitals charge a toll), 40% pricier, 50% more likely (x1.50), Inn heal barred

        public final String label;
        Status(String label) { this.label = label; }
    }

    /** Gold demanded to enter a barred color's CAPITAL at War (towns stay barred outright).
     *  Raised 100 -> 500 per user tuning, 2026-08-07. */
    public static final int CAPITAL_ENTRY_TOLL = 500;

    public static Status getStatus(String color) {
        int rep = displayValue(AdventurePlayer.current().getColorReputationHalfPoints(color));
        if (rep >= 80) return Status.PARTNER;
        if (rep >= 30) return Status.HAPPY;
        if (rep >= -29) return Status.NEUTRAL;
        if (rep >= -79) return Status.UNHAPPY;
        return Status.WAR;
    }

    /** Card-shop price multiplier in a color's town (user scope decision: card shops only -
     *  Inn/spellsmith/trader pricing deliberately untouched for now). */
    public static float getShopPriceMultiplier(String color) {
        if (!isEnabled() || color == null)
            return 1f;
        switch (getStatus(color)) {
            case PARTNER: return 0.70f;
            case HAPPY: return 0.85f;
            case UNHAPPY: return 1.25f;
            case WAR: return 1.40f; // reachable inside a capital after paying the toll; raised 1.25 -> 1.40 per user tuning
            default: return 1f;
        }
    }

    /**
     * Weight multiplier for a PLAYER-OWNED town when this color's castle picks a mage target
     * among its nearest candidates (TerritoryControl.dispatch()) - the user's chosen meaning of
     * "less/more likely to be attacked". 1.0 for non-player towns or when the feature is off.
     */
    public static float getPlayerTownAttackWeight(String color) {
        if (!isEnabled() || color == null)
            return 1f;
        switch (getStatus(color)) {
            case PARTNER: return 0.25f; // round 112 user tuning (was 0.75 / 0.95 / 1.05 / 1.25)
            case HAPPY: return 0.50f;
            case UNHAPPY: return 1.15f;
            case WAR: return 1.50f;
            default: return 1f;
        }
    }

    /**
     * Multiplier on the base proximity chance of a nearby foreign color's monsters intruding into
     * a roaming-spawn roll (WorldStage.handleMonsterSpawn(), TerritoryControl.findNearbyForeignColor()
     * - user request 2026-08-10: "if you are at war with a color they might spawn"). 1.0 at
     * Neutral; a Partner-tier color never intrudes at all (0), War-tier borders are the most
     * dangerous to wander near.
     */
    public static float getSpawnIntrusionMultiplier(String color) {
        if (!isEnabled() || color == null)
            return 1f;
        switch (getStatus(color)) {
            case PARTNER: return 0f;
            case HAPPY: return 0.5f;
            case UNHAPPY: return 1.5f;
            case WAR: return 2.5f;
            default: return 1f;
        }
    }

    /** True when the player is barred from this color's ordinary towns (War tier). */
    public static boolean isEntryBarred(String color) {
        return isEnabled() && color != null && getStatus(color) == Status.WAR;
    }

    /** True when this color's Spellsmith will deal with the player at all (2026-08-14 user spec:
     *  Happy or Partner only) - deliberately its own, stricter method rather than a reuse of
     *  isEntryBarred() (War-only): Neutral and Unhappy standing still let the player walk into
     *  the town itself, just not use this specific building. Disabled feature/no color -> always
     *  accessible (matches every other reputation gate's "off means nothing is gated" default). */
    public static boolean isSpellsmithAccessible(String color) {
        if (!isEnabled() || color == null)
            return true;
        Status status = getStatus(color);
        return status == Status.HAPPY || status == Status.PARTNER;
    }

    /** True when this color's War-tier standing bars the player from healing at its Inns
     *  entirely (Partner-tier Inns are also non-purchasable, but for the opposite reason - see
     *  AdventurePlayer.grantPartnerOverheal(); callers distinguish the two by status, not this). */
    public static boolean isHealBarred(String color) {
        return isEnabled() && color != null && getStatus(color) == Status.WAR;
    }

    /** True when this color's Unhappy-or-worse standing blocks the FREE full-life heal a town/
     *  capital entry would otherwise grant (user report 2026-08-11: "still getting life restored
     *  when visiting a town... unhappy/at war with"). Deliberately its own method, not a reuse of
     *  isHealBarred() - that one is WAR-only by design and gates the Inn's PAID potion, which
     *  stays purchasable at Unhappy; this gates the free auto-heal on entry, which the user wants
     *  blocked at Unhappy too, not just War. */
    public static boolean isFreeHealBlocked(String color) {
        if (!isEnabled() || color == null)
            return false;
        Status status = getStatus(color);
        return status == Status.UNHAPPY || status == Status.WAR;
    }

    // Color Defeat penalty (MOD_SCOPE.md #61, user request 2026-08-14): -50 flat to the defeated
    // color, DELIBERATELY NOT zero-sum - this class's whole net-zero invariant (see the class doc
    // comment) exists to model duel events as a redistribution across the wheel, and a color being
    // wiped off the map by the player isn't a duel. Applied once, directly, with no compensating
    // shift to the other 4 (which are otherwise untouched and keep working normally - the wheel
    // just permanently loses its balance by 50 half-points x2 from this point on, same as real
    // geopolitics doesn't rebalance itself when a power collapses). Called from
    // TerritoryControl.defeatColor(), guarded there on isEnabled() already.
    private static final int DEFEAT_PENALTY_HALF_POINTS = -100; // displayed -50

    public static void applyColorDefeatPenalty(String color) {
        AdventurePlayer.current().addColorReputationHalfPoints(color, DEFEAT_PENALTY_HALF_POINTS);
    }

    /**
     * Debug/console support (`give rep <color> <amount>`): shifts one color by a display-value
     * amount while PRESERVING the net-zero invariant - the negation is spread evenly across the
     * other 4 colors (remainder half-points distributed one at a time so the sum stays exactly
     * zero). A raw single-color add would silently break the invariant the standings are used
     * to eyeball, which would muddy exactly the testing this command exists for.
     */
    public static void debugShiftReputation(String targetColor, int displayAmount) {
        AdventurePlayer player = AdventurePlayer.current();
        int halfPoints = displayAmount * 2;
        player.addColorReputationHalfPoints(targetColor, halfPoints);
        int perOther = -halfPoints / 4;
        int remainder = -halfPoints - perOther * 4;
        for (String color : COLORS) {
            if (color.equals(targetColor))
                continue;
            int delta = perOther;
            if (remainder != 0) {
                int step = remainder > 0 ? 1 : -1;
                delta += step;
                remainder -= step;
            }
            player.addColorReputationHalfPoints(color, delta);
        }
    }

    // "Plains Town ..."/"Plains Capital" -> white, etc. Deliberately its own copy of the noun
    // mapping (TerritoryControl has an equivalent private one) so reputation effects work with
    // territoryControlEnabled off - stock world-gen names color towns the same way regardless.
    // Returns null for anything that isn't a color's town/capital (Waste Towns, Spawn, dungeons).
    // PLAYER-OWNED exemption is the CALLER's job (needs the POI id for the changes lookup):
    // per explicit user decision, "the player's towns should not match any color" - a restored/
    // captured town ignores color reputation entirely no matter what it's named.
    private static final Map<String, String> TOWN_NOUN_TO_COLOR = new HashMap<>();
    static {
        TOWN_NOUN_TO_COLOR.put("Plains", "white");
        TOWN_NOUN_TO_COLOR.put("Island", "blue");
        TOWN_NOUN_TO_COLOR.put("Swamp", "black");
        TOWN_NOUN_TO_COLOR.put("Mountain", "red");
        TOWN_NOUN_TO_COLOR.put("Forest", "green");
    }

    public static String colorOfTown(forge.adventure.data.PointOfInterestData data) {
        if (data == null || data.name == null || data.type == null)
            return null;
        if (!"town".equals(data.type) && !"capital".equals(data.type))
            return null;
        for (Map.Entry<String, String> entry : TOWN_NOUN_TO_COLOR.entrySet()) {
            if (data.name.startsWith(entry.getKey()))
                return entry.getValue();
        }
        return null;
    }

    /** Internal half-points -> user-facing value. Rounds the rare leftover half (only reachable
     *  via a multicolor boss's x3 on odd half-point amounts); the stored value stays exact. */
    public static int displayValue(int halfPoints) {
        return Math.round(halfPoints / 2f);
    }

    /**
     * Called by DuelScene when the player WINS an ordinary duel (caller excludes Arena/Inn-event
     * fights and losses). Colorless/no-identity enemies are a no-op. isTerritoryMage is true when
     * the defeated enemy was a Territory Control attack mage (EnemySprite.territoryColor != null)
     * - killing one is worth double the normal win pattern (user request), on top of stopping the
     * attack it was carrying out.
     */
    public static void onPlayerWonDuel(EnemyData enemyData, boolean isTerritoryMage) {
        if (!isEnabled() || enemyData == null)
            return;
        java.util.List<String> enemyColors = colorsFromLetters(enemyData.colors);
        if (enemyColors.isEmpty())
            return;
        boolean mono = enemyColors.size() == 1;
        int multiplier = enemyData.boss ? BOSS_MULTIPLIER : (isTerritoryMage ? MAGE_KILL_MULTIPLIER : 1);
        for (String color : enemyColors) {
            // Multicolor applies the HALF pattern per color; internal values are stored doubled,
            // so "half" is a clean integer division by 2 of already-even constants.
            int target = (mono ? FIGHT_TARGET : FIGHT_TARGET / 2) * multiplier;
            int ally = (mono ? FIGHT_ALLY : FIGHT_ALLY / 2) * multiplier;
            int enemy = (mono ? FIGHT_ENEMY : FIGHT_ENEMY / 2) * multiplier;
            applyPattern(color, target, ally, enemy);
        }
    }

    /** Called once from AdventurePlayer.create() with the chosen starter deck's color identity. */
    public static void applyStartingDeckBonus(ColorSet identity) {
        if (!isEnabled() || identity == null)
            return;
        for (String color : colorsFromColorSet(identity))
            applyPattern(color, START_TARGET, START_ALLY, START_ENEMY);
    }

    // One zero-sum wheel application: target gets targetDelta, its 2 allies allyDelta each, its 2
    // enemies enemyDelta each. Callers pass amounts satisfying target + 2*ally + 2*enemy == 0.
    /** Town assault (MOD_SCOPE #87, user spec 2026-09-03): lose {@code displayPoints} with the
     *  attacked color, half with its allies, and its enemies gain the full amount - the same spread
     *  the starting-deck bonus uses, with the sign flipped. Half-points internally (x2). */
    public static void applyTownAssaultPenalty(String targetColor, int displayPoints, String why) {
        if (!isEnabled() || targetColor == null || displayPoints <= 0)
            return;
        applyPattern(targetColor, -displayPoints * 2, -displayPoints, displayPoints * 2);
        System.out.println("[TFR-Reputation] " + why + ": " + targetColor + " -" + displayPoints
                + ", its allies -" + (displayPoints / 2.0f) + ", its enemies +" + displayPoints);
    }

    private static void applyPattern(String targetColor, int targetDelta, int allyDelta, int enemyDelta) {
        AdventurePlayer player = AdventurePlayer.current();
        player.addColorReputationHalfPoints(targetColor, targetDelta);
        for (String ally : ALLIES.get(targetColor))
            player.addColorReputationHalfPoints(ally, allyDelta);
        for (String enemy : ENEMIES.get(targetColor))
            player.addColorReputationHalfPoints(enemy, enemyDelta);
    }

    /** Progressive Set Unlocks (MOD_SCOPE.md #4) - the single canonical color to use for a roaming
     *  monster's color-shard loot lookup. Returns the FIRST listed color for a multicolor enemy
     *  (its "dominant" color in enemies.json's own letter order) rather than requiring an exact
     *  mono-color match - checked directly against enemies.json before picking this: 917 of 1469
     *  enemies (62%) are multicolor, so falling back to the neutral shard for all of them would
     *  have defeated the user's actual design intent ("explore each color to get different
     *  edition cards" - most fights would've handed out neutral-shard loot regardless of which
     *  color's territory the fight happened in). Only genuinely colorless enemies (33 of 1469, no
     *  WUBRG letters at all) fall back to null/neutral now. */
    public static String singleColorOfEnemy(String enemyColors) {
        java.util.List<String> colors = colorsFromLetters(enemyColors);
        return colors.isEmpty() ? null : colors.get(0);
    }

    // EnemyData.colors is MTG letters ("W","U","B","R","G", possibly combined like "GW"); order
    // and case are not guaranteed, duplicates guarded against just in case.
    private static java.util.List<String> colorsFromLetters(String letters) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (letters == null)
            return result;
        for (char c : letters.toUpperCase().toCharArray()) {
            String color;
            switch (c) {
                case 'W': color = "white"; break;
                case 'U': color = "blue"; break;
                case 'B': color = "black"; break;
                case 'R': color = "red"; break;
                case 'G': color = "green"; break;
                default: continue;
            }
            if (!result.contains(color))
                result.add(color);
        }
        return result;
    }

    private static java.util.List<String> colorsFromColorSet(ColorSet identity) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (identity.hasWhite()) result.add("white");
        if (identity.hasBlue()) result.add("blue");
        if (identity.hasBlack()) result.add("black");
        if (identity.hasRed()) result.add("red");
        if (identity.hasGreen()) result.add("green");
        return result;
    }
}
