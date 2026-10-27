package forge.adventure.util;

import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TypingLabel;
import forge.adventure.data.ConfigData;
import forge.adventure.data.TuningData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.TileMapScene;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.MapStage;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Round 774 - realm milestones (the user: "every 10 towns you pick 1 of 3 realm-wide perks. It builds on tonight's town
 * specialties", then, on notes/realm-milestones-774.md: "Approve, new games only").
 * <ul>
 * <li><b>Who</b>: a save with the flag (AdventurePlayer.getRealmMilestones() not null - set at New Game and New Game+ on
 * a plane with realmMilestonesEnabled) that also has town specialties. An older save never sees a boon.</li>
 * <li><b>When</b>: every milestoneEveryTowns of the PEAK - the most towns held at once, counted as the restore fee and the
 * life bonus count them (TownRestoration.countPlayerTowns). TownRestoration.updateTownLifeBonus, which runs on every
 * change of hands and on load, calls onTownCountChanged. A lost town takes no boon away; winning it back earns none.</li>
 * <li><b>Choosing</b>: 3 boons, rolled once per milestone and saved (a reload, "Later" or closing never re-rolls). Slot 1
 * is the mastery of the specialty the player holds most towns of; the rest are drawn from what is left. Shown right after
 * the restore's specialty choice, else on entering a held town; "Later" leaves it at any held town's Job Board.</li>
 * <li><b>Effects</b> (numbers in settings.json, TuningData.milestone*): the masteries through TownSpecialties' effect
 * methods; Town Watch in TownSpecialties.captureDefense; Lamplighters and Watchtowers' light in
 * World.getTownVisionRadiusTiles; Tithe on payday (EconomyBuildings.processPaydays); Royal Grant in ResearchMeter.start.</li>
 * </ul>
 * Permanent: no respec. [TFR-Milestone] logs the peak, every offer, pick and effect.
 */
public final class RealmMilestones {
    private RealmMilestones() {}

    public enum Boon {
        DEEP_VEINS("deepVeins", "Deep Veins", TownSpecialties.Specialty.MINING, 1),
        GRAND_LIBRARY("grandLibrary", "Grand Library", TownSpecialties.Specialty.RESEARCH, 1),
        WATCHTOWERS("watchtowers", "Watchtowers", TownSpecialties.Specialty.OUTPOST, 1),
        MASTER_SMITHS("masterSmiths", "Master Smiths", TownSpecialties.Specialty.SMITHY, 1),
        IRON_GARRISONS("ironGarrisons", "Iron Garrisons", TownSpecialties.Specialty.GARRISON, 1),
        MERCHANT_GUILD("merchantGuild", "Merchant Guild", TownSpecialties.Specialty.MARKET, 1),
        TITHE("tithe", "Tithe", null, 2),
        TOWN_WATCH("townWatch", "Town Watch", null, 2),
        LAMPLIGHTERS("lamplighters", "Lamplighters", null, 1),
        ROYAL_GRANT("royalGrant", "Royal Grant", null, 2);

        public final String id;
        public final String label;
        /** The specialty a mastery upgrades, or null for a realm boon. */
        public final TownSpecialties.Specialty mastery;
        public final int maxPicks;

        Boon(String id, String label, TownSpecialties.Specialty mastery, int maxPicks) {
            this.id = id;
            this.label = label;
            this.mastery = mastery;
            this.maxPicks = maxPicks;
        }

        /** One line for the choice list, read from the tunables (as a further pick reads, for Tithe and Town Watch). */
        public String effect() {
            TuningData t = tuning();
            int next = picks(this) + 1;
            switch (this) {
                case DEEP_VEINS: return "Mining towns' mines pay +" + pct(t.milestoneDeepVeinsMineFactor - 1f);
                case GRAND_LIBRARY: return "Research towns +" + pct(t.milestoneLibraryPerTown) + " each, max +"
                        + pct(t.milestoneLibraryCap);
                case WATCHTOWERS: return "Outposts lit x" + (2 + t.milestoneWatchtowerLight) + ", +"
                        + pct(t.milestoneWatchtowerDefense) + " defense";
                case MASTER_SMITHS: return "Smithy Armories " + pct(1f - t.milestoneSmithsArmoryFactor) + " cheaper";
                case IRON_GARRISONS: return "Garrison towns +" + pct(t.milestoneIronGarrisonDefense) + " defense";
                case MERCHANT_GUILD: return "Market card shops " + pct(1f - t.milestoneMerchantPriceFactor) + " off";
                case TITHE: return "each town you hold pays " + (t.milestoneTitheGold * next) + "[+Gold] a week";
                case TOWN_WATCH: return "all your towns +" + pct(t.milestoneTownWatchDefense * next) + " defense";
                case LAMPLIGHTERS: return "all your towns light +" + t.milestoneLamplighterTiles
                        + (t.milestoneLamplighterTiles == 1 ? " tile" : " tiles");
                case ROYAL_GRANT: return "your next research is free, at once";
                default: return "";
            }
        }

        static Boon parse(String name) {
            if (name == null)
                return null;
            for (Boon b : values())
                if (b.id.equalsIgnoreCase(name) || b.name().equalsIgnoreCase(name) || b.label.equalsIgnoreCase(name)
                        || b.label.replace(" ", "").equalsIgnoreCase(name))
                    return b;
            return null;
        }
    }

    /**
     * The save's state, kept on AdventurePlayer and saved as one string ("realmMilestones"), so no save-bound class
     * changes: peak towns held, milestones reached so far, the boons taken (a boon twice for its second pick), the
     * milestones waiting (town counts, oldest first), the offer rolled for the oldest one, the last milestone shown,
     * Royal Grants used, and the last day Tithe paid.
     */
    public static final class State {
        int peak;
        int reached;
        int asked;
        int grantsUsed;
        int titheLastPaid = -1;
        final List<Boon> boons = new ArrayList<>();
        final List<Integer> waiting = new ArrayList<>();
        final List<Boon> offer = new ArrayList<>();

        public String encode() {
            return "peak=" + peak + ";reached=" + reached + ";asked=" + asked + ";grants=" + grantsUsed + ";tithe="
                    + titheLastPaid + ";boons=" + join(boons) + ";waiting=" + joinInts(waiting) + ";offer=" + join(offer);
        }

        public static State decode(String s) {
            State st = new State();
            if (s == null)
                return st;
            for (String part : s.split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0)
                    continue;
                String key = part.substring(0, eq), value = part.substring(eq + 1);
                try {
                    switch (key) {
                        case "peak": st.peak = Integer.parseInt(value); break;
                        case "reached": st.reached = Integer.parseInt(value); break;
                        case "asked": st.asked = Integer.parseInt(value); break;
                        case "grants": st.grantsUsed = Integer.parseInt(value); break;
                        case "tithe": st.titheLastPaid = Integer.parseInt(value); break;
                        case "boons": parseBoons(value, st.boons); break;
                        case "offer": parseBoons(value, st.offer); break;
                        case "waiting":
                            for (String w : value.split(","))
                                if (!w.isEmpty())
                                    st.waiting.add(Integer.parseInt(w));
                            break;
                        default: break;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            return st;
        }

        public String summary() {
            return "peak " + peak + ", boons " + boonNames(boons) + ", waiting " + waiting.size()
                    + (waiting.isEmpty() ? "" : " " + waiting) + (offer.isEmpty() ? "" : ", offer " + join(offer));
        }

        private static void parseBoons(String value, List<Boon> into) {
            for (String id : value.split(",")) {
                Boon b = Boon.parse(id);
                if (b != null)
                    into.add(b);
            }
        }

        private static String join(List<Boon> list) {
            StringBuilder sb = new StringBuilder();
            for (Boon b : list)
                sb.append(sb.length() == 0 ? "" : ",").append(b.id);
            return sb.toString();
        }

        private static String joinInts(List<Integer> list) {
            StringBuilder sb = new StringBuilder();
            for (Integer i : list)
                sb.append(sb.length() == 0 ? "" : ",").append(i);
            return sb.toString();
        }
    }

    private static TuningData tuning() {
        return Config.instance().getTuningData();
    }

    private static String pct(float f) {
        float p = f * 100f;
        return Math.abs(p - Math.round(p)) < 0.05f ? Math.round(p) + "%"
                : String.format(java.util.Locale.ROOT, "%.1f%%", p);
    }

    // ---- the gate ---------------------------------------------------------------------------------------------------

    /** The plane has realm milestones (TFR's config.json) - the condition for a new game to start with them. */
    public static boolean planeEnabled() {
        ConfigData config = Config.instance().getConfigData();
        return config != null && config.realmMilestonesEnabled;
    }

    private static State state() {
        WorldSave save = WorldSave.getCurrentSave();
        if (save == null || save.getPlayer() == null || !planeEnabled())
            return null;
        return save.getPlayer().getRealmMilestones();
    }

    /** The current save has realm milestones: its own flag, the plane's switch and town specialties. */
    public static boolean enabled() {
        return state() != null && TownSpecialties.enabled();
    }

    private static int every() {
        return Math.max(1, tuning().milestoneEveryTowns);
    }

    // ---- the effects (each 0 / false / 1 when the boon is not taken) -----------------------------------------------

    public static int picks(Boon b) {
        State s = enabled() ? state() : null;
        if (s == null)
            return 0;
        int n = 0;
        for (Boon taken : s.boons)
            if (taken == b)
                n++;
        return n;
    }

    public static boolean has(Boon b) {
        return picks(b) > 0;
    }

    /** Town Watch: taken off the capture roll in every held town (TownSpecialties.captureDefense). */
    public static float townWatchDefense() {
        return Math.max(0f, tuning().milestoneTownWatchDefense) * picks(Boon.TOWN_WATCH);
    }

    /** Lamplighters: tiles added to every held town's light (World.getTownVisionRadiusTiles). */
    public static int lamplighterTiles() {
        return has(Boon.LAMPLIGHTERS) ? Math.max(0, tuning().milestoneLamplighterTiles) : 0;
    }

    /** Watchtowers: added to an Outpost's light multiplier (x2 -> x3, x3 with an Outlook -> x4). */
    public static int watchtowerLight() {
        return has(Boon.WATCHTOWERS) ? Math.max(0, tuning().milestoneWatchtowerLight) : 0;
    }

    /** Tithe: the gold each held town pays on payday. */
    public static int titheGoldPerTown() {
        return Math.max(0, tuning().milestoneTitheGold) * picks(Boon.TITHE);
    }

    /** Tithe: what the next payday brings at today's towns (BalanceSheet's projection). */
    public static int titheProjected() {
        int per = titheGoldPerTown();
        return per == 0 ? 0 : per * TownRestoration.countPlayerTowns();
    }

    /** EconomyBuildings.processPaydays: Tithe for every payday since it last paid (a long fast-forward pays each). */
    public static void processPaydays(int newDayCount) {
        State s = state();
        if (s == null || !enabled() || titheGoldPerTown() == 0)
            return;
        int last = s.titheLastPaid < 0 ? newDayCount : s.titheLastPaid;
        while (true) {
            int payday = World.nextWeekBoundary(last);
            if (payday > newDayCount)
                break;
            int towns = TownRestoration.countPlayerTowns();
            int gold = titheGoldPerTown() * towns;
            if (gold > 0) {
                AdventurePlayer.current().giveGold(gold);
                System.out.println("[TFR-Milestone] Tithe: " + towns + " town(s) pay " + gold + " gold (payday day "
                        + payday + ")");
                GameHUD.getInstance().addNotification("Tithe: your " + towns + " towns pay " + gold + "[+Gold].");
            }
            last = payday;
        }
        s.titheLastPaid = last;
    }

    /** Royal Grant: a grant is waiting for the next research (ResearchMeter.startCost / start). */
    public static boolean royalGrantReady() {
        State s = state();
        return s != null && enabled() && picks(Boon.ROYAL_GRANT) > s.grantsUsed;
    }

    /** Royal Grant: ResearchMeter.start just used one on this set. */
    public static void useRoyalGrant(String code) {
        State s = state();
        if (s == null)
            return;
        s.grantsUsed++;
        System.out.println("[TFR-Milestone] Royal Grant used on " + code + " (" + s.grantsUsed + " of "
                + picks(Boon.ROYAL_GRANT) + " used)");
    }

    // ---- the peak and the milestones ---------------------------------------------------------------------------------

    /** TownRestoration.updateTownLifeBonus - after every change of hands, and on load. Raises the peak and queues each
     *  milestone it passes. */
    public static void onTownCountChanged(boolean notify) {
        State s = state();
        if (s == null || !enabled())
            return;
        int held = TownRestoration.countPlayerTowns();
        if (held <= s.peak)
            return;
        int before = s.peak;
        s.peak = held;
        int added = 0;
        while ((s.reached + 1) * every() <= s.peak) {
            s.reached++;
            s.waiting.add(s.reached * every());
            added++;
        }
        if (added == 0)
            return;
        System.out.println("[TFR-Milestone] peak " + before + " -> " + s.peak + " (milestone " + s.reached + " reached, "
                + s.waiting.size() + " waiting)");
        if (notify)
            GameHUD.getInstance().addNotification("Your realm reaches " + s.waiting.get(s.waiting.size() - 1)
                    + " towns! Choose a realm boon in any of your towns.");
    }

    /** The oldest waiting milestone's offer - rolled once and kept. Empty when nothing waits or nothing is left. */
    static List<Boon> currentOffer(State s) {
        while (!s.waiting.isEmpty() && s.offer.isEmpty()) {
            rollOffer(s);
            if (s.offer.isEmpty()) {
                System.out.println("[TFR-Milestone] nothing left to offer for " + s.waiting.get(0) + " towns - skipped");
                s.waiting.remove(0);
            }
        }
        return s.offer;
    }

    private static boolean offerable(Boon b, int[] specialtyTowns) {
        if (picks(b) >= b.maxPicks)
            return false;
        if (b.mastery != null)
            return specialtyTowns[b.mastery.ordinal()] >= Math.max(1, tuning().milestoneMasteryMinTowns);
        if (b == Boon.ROYAL_GRANT)
            return ResearchMeter.enabled();
        return true;
    }

    private static void rollOffer(State s) {
        int[] towns = new int[TownSpecialties.Specialty.values().length];
        for (Object[] row : TownSpecialties.badgedTowns())
            towns[((TownSpecialties.Specialty) row[1]).ordinal()]++;
        List<Boon> pool = new ArrayList<>();
        Boon lead = null;
        for (Boon b : Boon.values()) {
            if (!offerable(b, towns))
                continue;
            pool.add(b);
            if (b.mastery != null && (lead == null || towns[b.mastery.ordinal()] > towns[lead.mastery.ordinal()]))
                lead = b;
        }
        s.offer.clear();
        if (lead != null) {
            s.offer.add(lead);
            pool.remove(lead);
        }
        Random random = new Random();
        while (s.offer.size() < 3 && !pool.isEmpty())
            s.offer.add(pool.remove(random.nextInt(pool.size())));
        if (!s.offer.isEmpty())
            System.out.println("[TFR-Milestone] offer for " + s.waiting.get(0) + " towns: " + State.join(s.offer)
                    + (lead != null ? " (lead: " + lead.mastery.label + ", " + towns[lead.mastery.ordinal()] + " towns)" : ""));
    }

    // ---- choosing ------------------------------------------------------------------------------------------------

    /** TownSpecialties, after the restore's specialty choice (or in its place): the waiting boon, then {@code after}.
     *  Returns false, having done nothing, when no boon waits - the caller then runs {@code after} itself. */
    public static boolean offerWaiting(MapStage stage, Runnable after, String how) {
        State s = state();
        if (s == null || !enabled() || currentOffer(s).isEmpty())
            return false;
        showChoice(stage, s, how, after);
        return true;
    }

    /** MapStage.loadMap's end (through TownSpecialties.onMapLoaded): a held town asks once per milestone. */
    public static void offerOnEntry(MapStage stage) {
        try {
            State s = state();
            if (s == null || !enabled() || !heldTownHere(stage))
                return;
            if (currentOffer(s).isEmpty() || s.asked >= s.waiting.get(0))
                return;
            showChoice(stage, s, "entry", null);
        } catch (Exception e) {
            e.printStackTrace(); // never break a map load
        }
    }

    private static boolean heldTownHere(MapStage stage) {
        return stage != null && TileMapScene.instance() != null && TileMapScene.instance().rootPoint != null
                && TownRestoration.isTownRestored(stage.getChanges());
    }

    /** QuestActor (through TownSpecialties.offeredAtJobBoard): a boon waits, so this town's board opens its menu. */
    public static boolean waitingAtJobBoard() {
        State s = state();
        return s != null && enabled() && heldTownHere(MapStage.getInstance()) && !currentOffer(s).isEmpty();
    }

    /** TownRestoration.openJobBoardMenu (through TownSpecialties.addJobBoardRow): the waiting boon, or where the realm
     *  stands. */
    public static void addJobBoardRow(MapStage stage, Dialog dialog) {
        State s = state();
        if (s == null || !enabled() || !heldTownHere(stage))
            return;
        if (!currentOffer(s).isEmpty()) {
            dialog.getButtonTable().add(Controls.newTextButton("Realm boon (" + s.waiting.get(0) + " towns): choose", () -> {
                stage.hideDialog();
                showChoice(stage, s, "job board", null);
            })).width(240f).row();
            return;
        }
        int held = TownRestoration.countPlayerTowns();
        TextraButton info = Controls.newTextButton("[%85]Realm: " + held + " towns" + (s.peak > held ? " (most " + s.peak
                + ")" : "") + " - next boon at " + (s.reached + 1) * every(), () -> {});
        info.setDisabled(true);
        dialog.getButtonTable().add(info).width(240f).row();
    }

    private static void showChoice(MapStage stage, State s, String how, Runnable after) {
        int milestone = s.waiting.get(0);
        s.asked = Math.max(s.asked, milestone);
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        TypingLabel label = Controls.newTypingLabel("Your realm reaches " + milestone + " towns. Choose a realm boon.");
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();
        for (Boon b : new ArrayList<>(s.offer)) {
            dialog.getButtonTable().add(Controls.newTextButton("[%80]" + b.label + ": " + b.effect(), () -> {
                stage.hideDialog();
                pick(s, b, how);
                if (after != null)
                    after.run();
            })).width(260f).row();
        }
        dialog.getButtonTable().add(Controls.newTextButton("[%80]Later (at any of your towns' Job Board)", () -> {
            stage.hideDialog();
            System.out.println("[TFR-Milestone] " + milestone + " towns: choice put off (" + how + ")");
            if (after != null)
                after.run();
        })).width(260f).row();
        dialog.setKeepWithinStage(true);
        stage.showDialog();
        System.out.println("[TFR-Milestone] choice shown for " + milestone + " towns (" + how + "): " + State.join(s.offer));
    }

    /** Takes the boon for the oldest waiting milestone and applies what it changes at once. */
    static void pick(State s, Boon b, String how) {
        int milestone = s.waiting.isEmpty() ? 0 : s.waiting.remove(0);
        String effect = b.effect(); // read before the pick, so a second Tithe says its new total
        s.boons.add(b);
        s.offer.clear();
        int day = currentDay();
        if (b == Boon.TITHE && s.titheLastPaid < 0)
            s.titheLastPaid = day; // the first Tithe is the next payday
        TownSpecialties.invalidate();
        System.out.println("[TFR-Milestone] pick: " + b.label + " (" + b.id + ", pick " + picks(b) + ") at " + milestone
                + " towns on day " + day + " (" + how + ") - " + effect.replace("[+Gold]", " gold") + "; boons now "
                + boonNames(s.boons));
        if (b == Boon.LAMPLIGHTERS || b == Boon.WATCHTOWERS)
            relight(b);
        GameHUD.getInstance().addNotification("Realm boon: " + b.label + " - " + effect + ".");
    }

    /** Lamplighters / Watchtowers: the held towns' light is rebuilt and the new ring revealed. */
    private static void relight(Boon b) {
        World world = WorldSave.getCurrentSave().getWorld();
        world.rebuildPlayerTownVision();
        int towns = 0;
        String example = "";
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
            if (!TownRestoration.isTownRestored(changes))
                continue;
            if (b == Boon.WATCHTOWERS && !TownSpecialties.isOutpost(changes))
                continue;
            TownRestoration.applyTownVisionReveal(world, poi, changes);
            if (towns++ == 0)
                example = " - " + poi.getDisplayName() + " now lights " + world.getTownVisionRadiusTiles(poi, changes) + " tile(s)";
        }
        System.out.println("[TFR-Milestone] " + b.label + ": " + towns + " town(s) relit" + example);
    }

    // ---- shown elsewhere ---------------------------------------------------------------------------------------------

    /** WorldStandingsScene: the taken boons and the next milestone, or "" for a save without them. */
    public static String standingsLine() {
        State s = state();
        if (s == null || !enabled())
            return "";
        String taken = s.boons.isEmpty() ? "none yet" : boonNames(s.boons);
        String waiting = s.waiting.isEmpty() ? "" : " - " + s.waiting.size() + " waiting at your Job Boards";
        return "Realm boons: " + taken + ". Most towns held: " + s.peak + "; next boon at " + (s.reached + 1) * every()
                + waiting + ".";
    }

    /** The boons by name, a second pick as "Tithe x2". */
    static String boonNames(List<Boon> boons) {
        if (boons.isEmpty())
            return "none";
        StringBuilder sb = new StringBuilder();
        for (Boon b : Boon.values()) {
            int n = 0;
            for (Boon taken : boons)
                if (taken == b)
                    n++;
            if (n > 0)
                sb.append(sb.length() == 0 ? "" : ", ").append(b.label).append(n > 1 ? " x" + n : "");
        }
        return sb.toString();
    }

    /** AdventurePlayer.load: one line on what the save holds. */
    public static void logLoaded(State s) {
        System.out.println("[TFR-Milestone] realm milestones: " + (s == null ? "off (an older save)" : "on - " + s.summary())
                + " (loaded)");
    }

    // ---- the console (cheats) ---------------------------------------------------------------------------------------

    /** "milestone" - the state; "milestone peak N" - set the peak (queues every milestone it passes); "milestone offer" -
     *  open the waiting choice here; "milestone pick <id>" - take a boon for the oldest waiting milestone, or as a free
     *  extra when none waits; "milestone clear" - no boons, peak 0, nothing waiting. */
    public static String console(String[] args) {
        if (!planeEnabled())
            return "This plane has no realm milestones.";
        State s = state();
        if (s == null)
            return "This save has no realm milestones (it was started before round 774).";
        if (!TownSpecialties.enabled())
            return "This save has no town specialties, so no realm milestones.";
        String cmd = args.length == 0 ? "info" : args[0].toLowerCase();
        switch (cmd) {
            case "peak": {
                int n;
                try { n = Integer.parseInt(args.length >= 2 ? args[1] : ""); }
                catch (NumberFormatException e) { return "milestone peak <towns>"; }
                int before = s.peak;
                s.peak = Math.max(0, n);
                while ((s.reached + 1) * every() <= s.peak) {
                    s.reached++;
                    s.waiting.add(s.reached * every());
                }
                System.out.println("[TFR-Milestone] console: peak " + before + " -> " + s.peak + " (" + s.reached
                        + " reached, " + s.waiting.size() + " waiting)");
                return "Peak " + s.peak + ": " + s.waiting.size() + " boon(s) waiting.";
            }
            case "offer": {
                MapStage stage = MapStage.getInstance();
                if (!heldTownHere(stage))
                    return "Stand in a town you hold.";
                if (currentOffer(s).isEmpty())
                    return "No boon waits (milestone peak <towns> queues one).";
                showChoice(stage, s, "console", null);
                return "Choice open for " + s.waiting.get(0) + " towns.";
            }
            case "pick": {
                Boon b = args.length >= 2 ? Boon.parse(args[1]) : null;
                if (b == null) {
                    StringBuilder ids = new StringBuilder();
                    for (Boon x : Boon.values())
                        ids.append(ids.length() == 0 ? "" : "|").append(x.id);
                    return "milestone pick " + ids;
                }
                if (picks(b) >= b.maxPicks)
                    return b.label + " is at its " + b.maxPicks + " pick(s).";
                pick(s, b, "console");
                return "Took " + b.label + ". Boons: " + boonNames(s.boons) + ".";
            }
            case "clear":
                s.peak = 0;
                s.reached = 0;
                s.asked = 0;
                s.grantsUsed = 0;
                s.titheLastPaid = -1;
                s.boons.clear();
                s.waiting.clear();
                s.offer.clear();
                TownSpecialties.invalidate();
                System.out.println("[TFR-Milestone] console: cleared");
                return "Realm milestones cleared (peak 0; the next town change counts again).";
            default:
                return "Realm milestones: " + s.summary() + "; towns held " + TownRestoration.countPlayerTowns()
                        + "; Royal Grant " + (royalGrantReady() ? "ready" : "none") + ". [info | peak N | offer | pick <id> | clear]";
        }
    }

    private static int currentDay() {
        World world = Current.world();
        return world == null ? 0 : world.getCurrentDay();
    }
}
