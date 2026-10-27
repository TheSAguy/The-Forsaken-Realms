package forge.adventure.util;

import forge.adventure.data.TuningData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.stage.GameHUD;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Round 751 - research v2 (the user: "Each card should ONLY count towards that set ... The first unique card should count
 * more ... all selling happens at the Research lab ... You also have one sell price"; then "let's have the shard reduction
 * by 50% of what you currently have"; then "Make research v2 ... new-game only; existing saves keep the old systems").
 * <p>
 * Every set has a meter. A card sold anywhere, or sacrificed at the Lab, adds points to the meter of the set its printing
 * belongs to (PrintingIndex): the first copy of a name ever fed counts in full by rarity, a later copy a share of that,
 * and a sacrifice counts double. The meter's size is a share of the set's card count. Its fill cuts the research's shard
 * cost (by researchShardCutAtFull at a full meter, never to nothing) and its days (to none at a full meter), both along
 * the square root of the fill so the first cards count most. A full meter finishes on pressing Research, once the shards
 * are paid. The numbers live in settings.json (TuningData.research*); the state lives on AdventurePlayer (saved).
 * <p>
 * Only a game started on round 751 or later runs it (AdventurePlayer.isResearchV2, set by create()); a save from before
 * keeps the classic research - the 10% gate, 7 days, shop-only selling at the town's price. Grep forge.log for
 * [TFR-Research].
 * <p>
 * Round 764 (the user: "I'm not sure I like/understand the "meter" concept ... it will just keep lowering the time
 * needed", then option A, "even research in progress will lessen in time if you donate cards after research has
 * started. The only thing you won't get back would be the shards you spent"): the player never sees the meter. The Lab
 * shows days, shards and how many new commons take the next day off (nextDayLine, worked out through the same
 * lengthDays/pointsFor math); a set at 0 days is Ready and takes no more points (pointsFor clamps at a full meter, so
 * its cards only pay gold); a sacrifice stops at Ready; and feeding a running research to 0 days finishes it at once
 * (flush). The numbers are unchanged; "meter" stays only as the internal name.
 */
public final class ResearchMeter {
    private ResearchMeter() {}

    private static TuningData tuning() {
        return Config.instance().getTuningData();
    }

    /** The plane runs set unlocks at all (TFR's config.json) - the condition for a new game to start on v2. */
    public static boolean planeEnabled() {
        forge.adventure.data.ConfigData config = Config.instance().getConfigData();
        return config != null && config.editionProgressionEnabled;
    }

    /** This save runs research v2: the plane's switch and the save's own flag. */
    public static boolean enabled(AdventurePlayer player) {
        return player != null && player.isResearchV2() && planeEnabled();
    }

    /** The current save runs research v2 (the editor's Sell outside a shop, the one sell price). */
    public static boolean enabled() {
        forge.adventure.world.WorldSave save = forge.adventure.world.WorldSave.getCurrentSave();
        return save != null && enabled(save.getPlayer());
    }

    // ---- the meter ------------------------------------------------------------------------------------------------

    /** Points a full meter holds: researchMeterShare of the set's card count (PrintingIndex), at least researchMeterMin. */
    public static int meterSize(String code) {
        TuningData t = tuning();
        return Math.max(Math.max(1, t.researchMeterMin), Math.round(PrintingIndex.total(code) * t.researchMeterShare));
    }

    public static float points(AdventurePlayer player, String code) {
        Float p = player.getResearchPoints().get(code);
        return p == null ? 0f : p;
    }

    /** 0..1. */
    public static float fill(AdventurePlayer player, String code) {
        return Math.min(1f, points(player, code) / meterSize(code));
    }

    /** sqrt(fill): how far the meter has cut the cost and the days. */
    private static double reach(AdventurePlayer player, String code) {
        return Math.sqrt(fill(player, code));
    }

    /** The unfed shard price: researchShardCost, difficulty-scaled, with the Researcher perk (round 551). */
    public static int baseCost() {
        return Math.round(EconomyBuildings.scaledCost(AdventurePlayer.researchShardCost()) * Ascendance.researchCostFactor());
    }

    /** What starting this set costs now: the base less researchShardCutAtFull x sqrt(fill) of it. */
    public static int startCost(AdventurePlayer player, String code) {
        double cut = Math.max(0, Math.min(1, tuning().researchShardCutAtFull));
        return (int) Math.round(baseCost() * (1 - cut * reach(player, code)));
    }

    /** researchDaysV2, or 28. */
    public static int unfedDays() {
        int d = tuning().researchDaysV2;
        return d > 0 ? d : 28;
    }

    /** The research's whole length at today's fill, before rounding: researchDaysV2 x (1 - sqrt(fill)), with Researcher
     *  and (round 760) the Research towns' speed (TownSpecialties.researchDaysFactor). */
    public static double lengthDays(AdventurePlayer player, String code) {
        return lengthDaysAt(code, points(player, code));
    }

    /** Round 764: lengthDays at this many points - the Lab's look-ahead (nextDayOff, the sacrifice plan, sellNote). */
    private static double lengthDaysAt(String code, float pts) {
        double reach = Math.sqrt(Math.min(1f, Math.max(0f, pts) / meterSize(code)));
        return unfedDays() * (1 - reach) * Ascendance.researchDaysFactor() * TownSpecialties.researchDaysFactor();
    }

    /** Round 764: the days the Lab shows at this many points - left on a running research, else a start now's. */
    private static int daysAt(AdventurePlayer player, String code, float pts, int currentDay) {
        Integer start = player.getResearchInProgress().get(code);
        double left = lengthDaysAt(code, pts) - (start == null ? 0 : currentDay - start);
        return Math.max(0, (int) Math.ceil(left - 1e-6));
    }

    /** Round 764: the set's days now - left on a running research, else a start now's. 0 = Ready. */
    public static int days(AdventurePlayer player, String code, int currentDay) {
        return daysAt(player, code, points(player, code), currentDay);
    }

    /** Days a research would take if started now. */
    public static int daysIfStarted(AdventurePlayer player, String code) {
        return Math.max(0, (int) Math.ceil(lengthDays(player, code) - 1e-6));
    }

    /** Days left on a running research: its live length (feeding after the start shortens it) less the days gone. */
    public static int daysLeft(AdventurePlayer player, String code, int startDay, int currentDay) {
        return Math.max(0, (int) Math.ceil(lengthDays(player, code) - (currentDay - startDay) - 1e-6));
    }

    // ---- feeding --------------------------------------------------------------------------------------------------

    /** First-copy points by rarity: researchPointsByRarity = Common, Uncommon, Rare, Mythic. */
    public static float firstCopyPoints(PaperCard card) {
        int[] p = tuning().researchPointsByRarity;
        int i;
        CardRarity r = card.getRarity();
        if (r == CardRarity.MythicRare)
            i = 3;
        else if (r == CardRarity.Rare || r == CardRarity.Special)
            i = 2;
        else if (r == CardRarity.Uncommon)
            i = 1;
        else
            i = 0;
        return p != null && p.length > i ? p[i] : new int[]{1, 2, 3, 5}[i];
    }

    /** Raw points times the Research towns' factor (round 760), doubled for a sacrifice. */
    private static float scaled(float pts, boolean sacrifice) {
        pts *= TownSpecialties.researchPointsFactor(); // round 760: the Research towns' speed (1 without specialties)
        return sacrifice ? pts * tuning().researchSacrificeFactor : pts;
    }

    /** Round 764: what one new common adds when sold - the Lab's unit for "N more new commons take a day off". */
    public static float newCommonPoints() {
        int[] p = tuning().researchPointsByRarity;
        return scaled(p != null && p.length > 0 ? p[0] : 1, false);
    }

    /** Round 764: points a set still takes before its meter is full (0 = Ready: more of its cards only pay gold). */
    private static float room(AdventurePlayer player, String code) {
        return Math.max(0f, meterSize(code) - points(player, code));
    }

    /** Can this printing feed a meter: a v2 save, a card with sale value, counted by its own set, the set not open. */
    public static boolean feedable(AdventurePlayer player, PaperCard card) {
        return enabled(player) && card != null && !card.hasNoSellValue() && PrintingIndex.counts(card)
                && !player.hasUnlockedEdition(card.getEdition());
    }

    /** Points this many copies would add now (first copy in full when the name is new to the set, the rest a share). */
    public static float pointsFor(AdventurePlayer player, PaperCard card, int amount, boolean sacrifice) {
        if (amount <= 0 || !feedable(player, card))
            return 0f;
        float first = firstCopyPoints(card);
        float copy = first * tuning().researchCopyShare;
        Set<String> fed = player.getResearchFedNames().get(card.getEdition());
        boolean isNew = fed == null || !fed.contains(card.getName());
        float pts = isNew ? first + (amount - 1) * copy : amount * copy;
        // Round 764: clamped at a full meter - a Ready set stores nothing more, so its cards only pay gold.
        return Math.min(scaled(pts, sacrifice), room(player, card.getEdition()));
    }

    /** The editor's Sell line, round 764 in days: " (DOM research -1 day)", " (feeds DOM research)", " (DOM research:
     *  Ready)" or " (finishes DOM research)" when one copy sold now would feed its set, else "". */
    public static String sellNote(AdventurePlayer player, PaperCard card) {
        float pts = pointsFor(player, card, 1, false);
        if (pts <= 0f)
            return "";
        String code = card.getEdition();
        int day = currentDay();
        float now = points(player, code);
        int before = daysAt(player, code, now, day), after = daysAt(player, code, now + pts, day);
        if (after == 0)
            return player.isResearching(code) ? " (finishes " + code + " research)" : " (" + code + " research: Ready)";
        return after < before ? " (" + code + " research -" + daysText(before - after) + ")" : " (feeds " + code + " research)";
    }

    /** Round 764: "1 day", "3 days". */
    public static String daysText(int days) {
        return days + (days == 1 ? " day" : " days");
    }

    /** Round 764: {new commons sold, days they take off} for the next day off now; {0, 0} when Ready. */
    public static int[] nextDayOff(AdventurePlayer player, String code, int currentDay) {
        float pts = points(player, code);
        int now = daysAt(player, code, pts, currentDay);
        float c = newCommonPoints();
        if (now <= 0 || c <= 0f)
            return new int[]{0, 0};
        int max = (int) Math.ceil(room(player, code) / c) + 1; // a full meter is 0 days, so this always finds one
        for (int n = 1; n <= max; n++) {
            int d = daysAt(player, code, pts + n * c, currentDay);
            if (d < now)
                return new int[]{n, now - d};
        }
        return new int[]{0, 0};
    }

    /** Round 764: "One more new common takes a day off", "3 more new commons take a day off", "One more new common takes
     *  2 days off"; "" when Ready. */
    public static String nextDayLine(AdventurePlayer player, String code, int currentDay) {
        int[] r = nextDayOff(player, code, currentDay);
        if (r[0] <= 0)
            return "";
        return (r[0] == 1 ? "One more new common takes " : r[0] + " more new commons take ")
                + (r[1] == 1 ? "a day" : r[1] + " days") + " off";
    }

    private static final class Batch {
        int newCards, copies;
        float points;
        int daysBefore; // round 764: the days the Lab showed before this feed (left, when running)
    }

    private static final Map<String, Batch> batch = new LinkedHashMap<>();
    private static int batchDepth = 0;

    /** Groups the sales of one bulk sale into one log line per set. */
    public static void beginBatch() {
        batchDepth++;
    }

    public static void endBatch(AdventurePlayer player, String how) {
        if (batchDepth > 0)
            batchDepth--;
        if (batchDepth == 0)
            flush(player, how);
    }

    /** A sale (shop, auto-sell, the editor) - AdventurePlayer.performSale, after the copies left the collection. */
    public static void onSold(AdventurePlayer player, PaperCard card, int amount) {
        if (!enabled(player))
            return;
        try {
            feed(player, card, amount, false);
            if (batchDepth == 0)
                flush(player, "sold");
        } catch (Exception e) {
            e.printStackTrace(); // never break a sale
        }
    }

    private static void feed(AdventurePlayer player, PaperCard card, int amount, boolean sacrifice) {
        float pts = pointsFor(player, card, amount, sacrifice);
        if (pts <= 0f)
            return;
        String code = card.getEdition();
        Batch b = batch.get(code);
        if (b == null) {
            b = new Batch();
            b.daysBefore = days(player, code, currentDay());
            batch.put(code, b);
        }
        Set<String> fed = player.getResearchFedNames().computeIfAbsent(code, k -> new HashSet<>());
        if (fed.add(card.getName())) {
            b.newCards++;
            b.copies += amount - 1;
        } else {
            b.copies += amount;
        }
        b.points += pts;
        player.getResearchPoints().merge(code, pts, Float::sum);
    }

    private static void flush(AdventurePlayer player, String how) {
        if (batch.isEmpty())
            return;
        int day = currentDay();
        java.util.List<String> finished = new java.util.ArrayList<>();
        for (Map.Entry<String, Batch> e : batch.entrySet()) {
            String code = e.getKey();
            Batch b = e.getValue();
            float after = fill(player, code);
            boolean running = player.isResearching(code);
            int daysNow = days(player, code, day);
            System.out.println("[TFR-Research] fed " + code + " (" + how + "): " + b.newCards + " new card(s), "
                    + b.copies + " copy(ies), +" + fmt(b.points) + " pts -> " + fmt(points(player, code)) + "/"
                    + meterSize(code) + " (" + Math.round(after * 100) + "%); start now: " + startCost(player, code)
                    + " shards (base " + baseCost() + "), " + daysIfStarted(player, code) + " day(s)"
                    + (running ? "; running, " + player.getResearchDaysLeft(code, day) + " day(s) left" : ""));
            if (running) {
                // Round 764: feeding a running research shortens it; at 0 days left it finishes now, not on the next tick.
                if (daysNow < b.daysBefore)
                    System.out.println("[TFR-Research] running " + code + " shortened by feeding: " + b.daysBefore
                            + " -> " + daysNow + " day(s) left (start shards not refunded)");
                if (daysNow == 0)
                    finished.add(code);
            } else if (b.daysBefore > 0 && daysNow == 0) {
                GameHUD.getInstance().addNotification("[+Shards] " + editionName(code) + " is ready to research -"
                        + " at your Capitol's Research Lab for " + startCost(player, code) + "[+Shards], with no wait.");
            }
        }
        batch.clear();
        for (String code : finished) {
            System.out.println("[TFR-Research] " + code + " finished by feeding on day " + day);
            player.checkResearchCompletion(day);
            if (player.hasUnlockedEdition(code))
                GameHUD.getInstance().addNotification("[+Shards] " + editionName(code) + " is researched - its cards"
                        + " now stock your shops.");
        }
    }

    /** The cards of this set the player could sacrifice: not in a deck, not a favorite, with sale value. keepOne leaves
     *  one copy of each name in the collection. */
    public static Map<PaperCard, Integer> spareCards(AdventurePlayer player, String code, boolean keepOne) {
        Map<PaperCard, Integer> out = new LinkedHashMap<>();
        if (!enabled(player) || player.hasUnlockedEdition(code))
            return out;
        Set<String> kept = new HashSet<>();
        for (Map.Entry<PaperCard, Integer> e : player.getCards()) {
            PaperCard pc = e.getKey();
            if (!code.equals(pc.getEdition()) || !feedable(player, pc) || player.favoriteCards.contains(pc))
                continue;
            int inDecks = player.getCopiesUsedInDecks(pc);
            int spare = e.getValue() - inDecks;
            // One copy of each name stays: a deck's copy already does, otherwise the name's first printing keeps one.
            if (keepOne && kept.add(pc.getName()) && inDecks == 0)
                spare = Math.min(spare, e.getValue() - 1);
            if (spare > 0)
                out.put(pc, spare);
        }
        return out;
    }

    /** Round 764: what a sacrifice would do (or did) - the cards it takes and the Lab's days before and after. */
    public static final class Plan {
        public int cards;
        public int daysBefore, daysAfter;
    }

    /** Round 764: the Lab's Sacrifice, looked at before it runs - copy by copy, stopping once the set reaches 0 days,
     *  so a sacrifice never takes cards past Ready. */
    public static Plan planSacrifice(AdventurePlayer player, String code, boolean keepOne, int currentDay) {
        return runSacrifice(player, code, keepOne, currentDay, false);
    }

    /** The Lab's Sacrifice: these copies leave the collection for double points, no gold, until the set reaches 0 days
     *  (round 764). A running research brought to 0 days finishes at once (flush). */
    public static Plan sacrifice(AdventurePlayer player, String code, boolean keepOne, int currentDay) {
        Plan plan = runSacrifice(player, code, keepOne, currentDay, true);
        flush(player, "sacrificed " + plan.cards + " card(s)" + (keepOne ? ", one of each kept" : ""));
        return plan;
    }

    private static Plan runSacrifice(AdventurePlayer player, String code, boolean keepOne, int currentDay, boolean apply) {
        Plan plan = new Plan();
        float pts = points(player, code);
        plan.daysBefore = daysAt(player, code, pts, currentDay);
        Set<String> fedNow = player.getResearchFedNames().get(code);
        Set<String> fedSim = fedNow == null ? new HashSet<>() : new HashSet<>(fedNow);
        float copyShare = tuning().researchCopyShare;
        outer:
        for (Map.Entry<PaperCard, Integer> e : spareCards(player, code, keepOne).entrySet()) {
            PaperCard pc = e.getKey();
            for (int i = 0; i < e.getValue(); i++) {
                if (daysAt(player, code, apply ? points(player, code) : pts, currentDay) == 0)
                    break outer;
                if (apply) {
                    if (player.removeCardsForResearch(pc, 1) <= 0)
                        break;
                    feed(player, pc, 1, true);
                } else {
                    float first = firstCopyPoints(pc);
                    float raw = fedSim.add(pc.getName()) ? first : first * copyShare;
                    pts += Math.min(scaled(raw, true), Math.max(0f, meterSize(code) - pts));
                }
                plan.cards++;
            }
        }
        plan.daysAfter = daysAt(player, code, apply ? points(player, code) : pts, currentDay);
        return plan;
    }

    // ---- research start and finish -------------------------------------------------------------------------------

    /** The Lab's Research button: pays, starts, and finishes at once when the meter left no days. */
    public static void start(AdventurePlayer player, String code, int currentDay) {
        int cost = startCost(player, code);
        int days = daysIfStarted(player, code);
        player.takeShards(cost);
        player.startResearch(code, currentDay);
        System.out.println("[TFR-Research] start " + code + " on day " + currentDay + ": meter " + fmt(points(player, code))
                + "/" + meterSize(code) + " (" + Math.round(fill(player, code) * 100) + "%), paid " + cost + " of "
                + baseCost() + " shards, " + days + " day(s)" + (days == 0 ? " - done at once" : " (done day "
                + (currentDay + days) + ")"));
        if (days == 0)
            player.checkResearchCompletion(currentDay);
    }

    /** checkResearchCompletion drops a finished set's meter. */
    public static void onCompleted(AdventurePlayer player, String code) {
        player.getResearchPoints().remove(code);
        player.getResearchFedNames().remove(code);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    public static String editionName(String code) {
        CardEdition ed = FModel.getMagicDb().getEditions().get(code);
        return ed != null ? ed.getName() + " (" + code + ")" : code;
    }

    public static String fmt(float v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static int currentDay() {
        forge.adventure.world.World world = Current.world();
        return world == null ? 0 : world.getCurrentDay();
    }
}
