package forge.adventure.util;

import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TypingLabel;
import forge.adventure.data.ConfigData;
import forge.adventure.data.TuningData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.TileMapScene;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.MapStage;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

import java.util.ArrayList;
import java.util.List;

/**
 * Round 760 - town specialties (the user: "each time you fix a town, you get the option to select the town specialty.
 * (This will not be for your cap)"; then, on the measured design in notes/research-towns-1026.md Part C: "Change research
 * to 5%, to a max of 50 ... For outpost, add 5% defense against capture. Build with these changes"; and "New game or NG+
 * features").
 * <ul>
 * <li><b>Who</b>: every town the player holds - restored, captured, a Ring City that opened its gates - except the
 * Capitol and Orazca (the Capitol's seat). Only in a save with the flag (AdventurePlayer.hasTownSpecialties, set at New
 * Game and New Game+ on a plane with townSpecialtiesEnabled); an older save never sees a choice, a badge or an effect.</li>
 * <li><b>Choosing</b>: right after the restore fee is paid (QuestActor's restore listener), or on the first entry of a
 * town taken any other way (MapStage.loadMap -> onMapLoaded). "Later" leaves the free pick at the town's Job Board.</li>
 * <li><b>Changing</b>: at the Job Board, for specialtyChangeFeeShare of the current restore fee's gold, then locked
 * specialtyChangeLockDays days. The first pick is free and not locked.</li>
 * <li><b>Effects</b> (numbers in settings.json, TuningData.specialty*): Mining - EconomyBuildings' mine payout; Research -
 * ResearchMeter (v2 only); Outpost - World.getTownVisionRadiusTiles and the capture roll; Smithy -
 * ArmoryRarity.classifyVenue; Garrison - the capture roll and TownPillage's start; Market - ShopActor's price and
 * RewardScene's restock.</li>
 * <li><b>Badges</b>: MapViewScene draws badge(...) at each specialized town on the World Map.</li>
 * </ul>
 * State: PointOfInterestChanges (specialty, specialtyChangedDay, marketFreeRestockWeek, specialtyAsked), so a town that
 * leaves the player's hands loses it with the rest of its state. [TFR-Specialty] logs every pick, change and effect.
 */
public final class TownSpecialties {
    private TownSpecialties() {}

    public enum Specialty {
        MINING("Mining"), RESEARCH("Research"), OUTPOST("Outpost"), SMITHY("Smithy"), GARRISON("Garrison"), MARKET("Market");

        public final String label;

        Specialty(String label) {
            this.label = label;
        }

        /** One line for the choice list, read from the tunables so the text cannot drift from the effect. */
        public String effect() {
            TuningData t = tuning();
            switch (this) {
                case MINING: return "its mines pay +" + pct(t.specialtyMineFactor - 1f);
                case RESEARCH: return "research " + pct(t.specialtyResearchPerTown) + " faster per Research town (max "
                        + pct(t.specialtyResearchCap) + ")";
                case OUTPOST: return "lit as with an Outlook, +" + pct(t.specialtyOutpostDefense) + " defense";
                case SMITHY: return "its Armory rolls at the Capitol's odds";
                case GARRISON: return "+" + pct(t.specialtyGarrisonDefense) + " defense, no raids start here";
                case MARKET: return "card shops " + pct(1f - t.specialtyMarketPriceFactor) + " off, a free restock a week";
                default: return "";
            }
        }

        /** "a Mining", "an Outpost". */
        public String withArticle() {
            return ("AEIOU".indexOf(label.charAt(0)) >= 0 ? "an " : "a ") + label;
        }

        static Specialty parse(String name) {
            if (name == null)
                return null;
            for (Specialty s : values())
                if (s.name().equalsIgnoreCase(name) || s.label.equalsIgnoreCase(name))
                    return s;
            return null;
        }
    }

    private static TuningData tuning() {
        return Config.instance().getTuningData();
    }

    private static String pct(float f) {
        return Math.round(f * 100) + "%";
    }

    // ---- the gate ---------------------------------------------------------------------------------------------------

    /** The plane has town specialties (TFR's config.json) - the condition for a new game to start with them. */
    public static boolean planeEnabled() {
        ConfigData config = Config.instance().getConfigData();
        return config != null && config.townSpecialtiesEnabled;
    }

    /** The current save has town specialties: the plane's switch and the save's own flag. */
    public static boolean enabled() {
        WorldSave save = WorldSave.getCurrentSave();
        return save != null && save.getPlayer() != null && save.getPlayer().hasTownSpecialties() && planeEnabled();
    }

    /** Can this town carry a specialty: a specialties save, a town the player holds, not the Capitol, not Orazca. */
    public static boolean eligible(PointOfInterest poi, PointOfInterestChanges changes) {
        return poi != null && poi.getData() != null && enabled() && TownRestoration.isTownRestored(changes)
                && !TownRestoration.CAPITOL_POI_NAME.equals(poi.getData().name) && !TownRestoration.isOrazca(poi.getData());
    }

    /** This town's specialty, or null - none chosen, not held, or not a specialties save. */
    public static Specialty of(PointOfInterestChanges changes) {
        if (changes == null || !enabled() || !TownRestoration.isTownRestored(changes))
            return null;
        return Specialty.parse(changes.getSpecialty());
    }

    public static Specialty of(PointOfInterest poi) {
        return poi == null ? null : of(WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID()));
    }

    // ---- the effects ------------------------------------------------------------------------------------------------

    /** Mining: what this town's mines pay, times this. */
    public static float mineFactor(PointOfInterestChanges changes) {
        return of(changes) == Specialty.MINING ? Math.max(0f, tuning().specialtyMineFactor) : 1f;
    }

    /** Outpost: this town is lit as if it had an Outlook (World.getTownVisionRadiusTiles). */
    public static boolean isOutpost(PointOfInterestChanges changes) {
        return of(changes) == Specialty.OUTPOST;
    }

    /** Smithy: this town's Armory rolls on the Capitol's rarity table (ArmoryRarity.classifyVenue). */
    public static boolean isSmithy(PointOfInterestChanges changes) {
        return of(changes) == Specialty.SMITHY;
    }

    /** Garrison: no pillage starts here (TownPillage). */
    public static boolean blocksRaids(PointOfInterestChanges changes) {
        return of(changes) == Specialty.GARRISON;
    }

    /** Outpost / Garrison: taken off an attacking mage's capture chance (TerritoryControl, before the floor). */
    public static float captureDefense(PointOfInterest town) {
        Specialty s = of(town);
        if (s == Specialty.GARRISON)
            return Math.max(0f, tuning().specialtyGarrisonDefense);
        if (s == Specialty.OUTPOST)
            return Math.max(0f, tuning().specialtyOutpostDefense);
        return 0f;
    }

    // Research towns are counted over every POI; the Lab's list asks for each set's row, so the count is kept a second.
    private static float researchBonusMemo = 0f;
    private static long researchBonusAt = -1L; // -1 = count again
    private static int researchTownsLogged = -1;

    private static boolean researchBonusFresh() {
        return researchBonusAt >= 0 && System.currentTimeMillis() - researchBonusAt < 1000L;
    }

    /** Research: the realm-wide bonus b - specialtyResearchPerTown per Research town, at most specialtyResearchCap. */
    public static float researchBonus() {
        if (researchBonusFresh())
            return researchBonusMemo;
        long now = System.currentTimeMillis();
        researchBonusAt = now;
        int towns = 0;
        if (enabled()) {
            for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest())
                if (of(poi) == Specialty.RESEARCH)
                    towns++;
        }
        TuningData t = tuning();
        researchBonusMemo = Math.max(0f, Math.min(t.specialtyResearchCap, towns * t.specialtyResearchPerTown));
        if (towns != researchTownsLogged) {
            researchTownsLogged = towns;
            System.out.println("[TFR-Specialty] Research: " + towns + " Research town(s) -> research +"
                    + pct(researchBonusMemo) + " faster (points x" + fmt(researchPointsFactor()) + ", days x"
                    + fmt(researchDaysFactor()) + ")");
        }
        return researchBonusMemo;
    }

    /** Research v2's points fed, times this (ResearchMeter.pointsFor). */
    public static float researchPointsFactor() {
        return 1f + researchBonusMemoOrNow();
    }

    /** Research v2's days, times this (ResearchMeter.lengthDays): / (1 + b), or (1 - b) with specialtyResearchTimeCut. */
    public static float researchDaysFactor() {
        float b = researchBonusMemoOrNow();
        return tuning().specialtyResearchTimeCut ? 1f - b : 1f / (1f + b);
    }

    private static float researchBonusMemoOrNow() {
        return researchBonusFresh() ? researchBonusMemo : researchBonus();
    }

    /** Forget the counted Research towns (a pick, a change, a load). */
    public static void invalidate() {
        researchBonusAt = -1L;
    }

    /** Market: a card shop's price in this town, times this (ShopActor.getPriceModifier). The Armory family is not a
     *  card shop. */
    public static float marketPriceFactor(PointOfInterestChanges changes, String shopName) {
        if (of(changes) != Specialty.MARKET || EconomyBuildings.isArmoryShopName(shopName))
            return 1f;
        return Math.max(0f, tuning().specialtyMarketPriceFactor);
    }

    /** Market: this town's free restock of the week is still there (RewardScene). */
    public static boolean marketRestockFree(PointOfInterestChanges changes) {
        if (of(changes) != Specialty.MARKET)
            return false;
        return changes.getMarketFreeRestockWeek() != World.weekOf(currentDay());
    }

    /** Market: the week's free restock was just used, in this shop. */
    public static void useMarketRestock(PointOfInterestChanges changes, String shopName) {
        int day = currentDay();
        changes.setMarketFreeRestockWeek(World.weekOf(day));
        System.out.println("[TFR-Specialty] Market: " + currentTownName() + "'s free restock of the week used on " + shopName
                + " (day " + day + ")");
    }

    // ---- choosing and changing --------------------------------------------------------------------------------------

    /** QuestActor's restore listener, the fee just paid: the choice, then {@code after} (out to the world map). Returns
     *  false, having done nothing, when this town takes no specialty - the caller then runs {@code after} itself. */
    public static boolean offerAfterRestore(MapStage stage, Runnable after) {
        PointOfInterest poi = TileMapScene.instance().rootPoint;
        PointOfInterestChanges changes = stage.getChanges();
        if (!eligible(poi, changes))
            return false;
        // A sacked town keeps its old changes entry; restoring it is a fresh start.
        changes.setSpecialty(null);
        changes.setSpecialtyChangedDay(-1);
        changes.setMarketFreeRestockWeek(-1);
        changes.setSpecialtyAsked(true);
        invalidate();
        showChoice(stage, poi, changes, false, "restored", after);
        return true;
    }

    /** MapStage.loadMap's end: a held town with no specialty that has never been asked (captured, a Ring City, a town
     *  whose restore choice did not show) asks once. */
    public static void onMapLoaded(MapStage stage) {
        try {
            if (!enabled() || TileMapScene.instance() == null)
                return;
            PointOfInterest poi = TileMapScene.instance().rootPoint;
            PointOfInterestChanges changes = stage.getChanges();
            if (!eligible(poi, changes) || changes.getSpecialty() != null || changes.isSpecialtyAsked())
                return;
            changes.setSpecialtyAsked(true);
            System.out.println("[TFR-Specialty] " + poi.getDisplayName() + " has no specialty - asking on entry");
            showChoice(stage, poi, changes, false, "first entry", null);
        } catch (Exception e) {
            e.printStackTrace(); // never break a map load
        }
    }

    /** Whether the town's Job Board should open its menu for the specialty row (QuestActor). */
    public static boolean offeredAtJobBoard() {
        if (TileMapScene.instance() == null || MapStage.getInstance() == null)
            return false;
        return eligible(TileMapScene.instance().rootPoint, MapStage.getInstance().getChanges());
    }

    /** TownRestoration.openJobBoardMenu: the specialty row - a free pick, the paid change, or the lock. */
    public static void addJobBoardRow(MapStage stage, Dialog dialog) {
        PointOfInterest poi = TileMapScene.instance().rootPoint;
        PointOfInterestChanges changes = stage.getChanges();
        if (!eligible(poi, changes))
            return;
        Specialty current = of(changes);
        if (current == null) {
            dialog.getButtonTable().add(Controls.newTextButton("Choose a specialty (free)", () -> {
                stage.hideDialog();
                showChoice(stage, poi, changes, false, "job board", null);
            })).width(240f).row();
            return;
        }
        int locked = lockDaysLeft(changes);
        if (locked > 0) {
            TextraButton lockedButton = Controls.newTextButton("[%85]" + current.label + " - change in " + locked
                    + (locked == 1 ? " day" : " days"), () -> {});
            lockedButton.setDisabled(true);
            dialog.getButtonTable().add(lockedButton).width(240f).row();
            return;
        }
        int gold = changeFeeGold();
        TextraButton change = Controls.newTextButton("[%85]Change specialty: " + current.label + " ("
                + EconomyBuildings.costLabel(gold, 0, 0, 0) + ")", () -> {
            stage.hideDialog();
            showChoice(stage, poi, changes, true, "job board", null);
        });
        change.setDisabled(!EconomyBuildings.canAffordCost(gold, 0, 0, 0));
        dialog.getButtonTable().add(change).width(240f).row();
    }

    /** The base gold of a change: specialtyChangeFeeShare of the current restore fee's gold (costLabel / payCost scale it
     *  for the difficulty, as the restore fee itself is). */
    static int changeFeeGold() {
        return Math.max(0, Math.round(TownRestoration.currentRestoreCost()[0] * tuning().specialtyChangeFeeShare));
    }

    /** Days before this town's specialty may change again (0 = now). Only a paid change locks it. */
    public static int lockDaysLeft(PointOfInterestChanges changes) {
        int changed = changes.getSpecialtyChangedDay();
        if (changed < 0)
            return 0;
        return Math.max(0, changed + tuning().specialtyChangeLockDays - currentDay());
    }

    private static void showChoice(MapStage stage, PointOfInterest poi, PointOfInterestChanges changes, boolean paid,
                                   String how, Runnable after) {
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        Specialty current = of(changes);
        String title = paid ? "Change " + poi.getDisplayName() + "'s specialty from " + current.label + " for "
                + EconomyBuildings.costLabel(changeFeeGold(), 0, 0, 0) + "? It is then fixed for "
                + tuning().specialtyChangeLockDays + " days."
                : "Choose " + poi.getDisplayName() + "'s specialty.";
        TypingLabel label = Controls.newTypingLabel(title);
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();
        for (Specialty s : Specialty.values()) {
            if (s == current)
                continue;
            dialog.getButtonTable().add(Controls.newTextButton("[%80]" + s.label + ": " + s.effect(), () -> {
                stage.hideDialog();
                pick(poi, changes, s, paid, how);
                if (after != null)
                    after.run();
            })).width(260f).row();
        }
        String later = paid ? "Keep " + current.label : "Later (free at the Job Board)";
        dialog.getButtonTable().add(Controls.newTextButton("[%80]" + later, () -> {
            stage.hideDialog();
            if (!paid)
                System.out.println("[TFR-Specialty] " + poi.getDisplayName() + ": choice put off (" + how + ")");
            if (after != null)
                after.run();
        })).width(260f).row();
        dialog.setKeepWithinStage(true);
        stage.showDialog();
    }

    /** Sets the specialty, pays and locks a change, and refreshes what it touches (vision, the Research count). */
    static void pick(PointOfInterest poi, PointOfInterestChanges changes, Specialty s, boolean paid, String how) {
        Specialty before = of(changes);
        int day = currentDay();
        String paidNote = "";
        if (paid) {
            int gold = changeFeeGold();
            paidNote = ", paid " + EconomyBuildings.costLabel(gold, 0, 0, 0).replace("[+Gold]", "gold")
                    + ", locked until day " + (day + tuning().specialtyChangeLockDays);
            EconomyBuildings.payCost(gold, 0, 0, 0);
            changes.setSpecialtyChangedDay(day);
        }
        changes.setSpecialty(s.name());
        changes.setSpecialtyAsked(true);
        invalidate();
        System.out.println("[TFR-Specialty] " + (before == null ? "pick" : "change") + ": " + poi.getDisplayName()
                + (before == null ? "" : " " + before.label + " ->") + " " + s.label + " on day " + day + " (" + how + paidNote
                + ") - " + s.effect());
        World world = WorldSave.getCurrentSave().getWorld();
        if (before == Specialty.OUTPOST || s == Specialty.OUTPOST) {
            world.rebuildPlayerTownVision();
            TownRestoration.applyTownVisionReveal(world, poi, changes);
            System.out.println("[TFR-Specialty] Outpost: " + poi.getDisplayName() + " lights "
                    + world.getTownVisionRadiusTiles(poi, changes) + " tile(s) round it");
        }
        if (before == Specialty.RESEARCH || s == Specialty.RESEARCH)
            researchBonus();
        GameHUD.getInstance().addNotification(poi.getDisplayName() + " is now " + s.withArticle() + " town: " + s.effect() + ".");
    }

    // ---- the World Map badges ---------------------------------------------------------------------------------------

    /** The badge art: the Art thread's atlas once it is in place (regions named as Specialty.label), else a stand-in item
     *  icon. Swapping a stand-in is one line in STAND_INS - or nothing at all once the atlas lands. */
    public static final String BADGE_ATLAS = "sprites/town_specialties.atlas";
    private static final String[] STAND_INS = {
            "GoldveinPick",          // MINING
            "TomeoftheTrove",        // RESEARCH
            "Torch",                 // OUTPOST
            "SwordoftheUr-Dragon",   // SMITHY
            "ShieldoftheRighteous",  // GARRISON
            "GoldCoin"};             // MARKET
    private static Boolean badgeAtlasPresent;

    public static Sprite badge(Specialty s) {
        if (badgeAtlasPresent == null) {
            com.badlogic.gdx.files.FileHandle atlas = Config.instance().getFile(BADGE_ATLAS); // null when not there
            badgeAtlasPresent = atlas != null && atlas.exists();
            System.out.println("[TFR-Specialty] badges: " + (badgeAtlasPresent ? BADGE_ATLAS : "stand-in item icons (no " + BADGE_ATLAS + ")"));
        }
        Sprite sprite = null;
        if (badgeAtlasPresent)
            sprite = Config.instance().getAtlasSprite(BADGE_ATLAS, s.label);
        if (sprite == null)
            sprite = Config.instance().getItemSprite(STAND_INS[s.ordinal()]);
        return sprite;
    }

    /** Every specialized town the player holds, with its specialty - MapViewScene's badges. */
    public static List<Object[]> badgedTowns() {
        List<Object[]> out = new ArrayList<>();
        if (!enabled())
            return out;
        for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest()) {
            if (!poi.getActive())
                continue;
            Specialty s = of(poi);
            if (s != null && eligible(poi, WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID())))
                out.add(new Object[]{poi, s});
        }
        return out;
    }

    // ---- the console (cheats) ---------------------------------------------------------------------------------------

    /** "specialty" - the current town's state and the realm's counts; "specialty set Mining" - set it here, free, no
     *  lock; "specialty clear" - none, and asked again on the next entry; "specialty unlock" - lift the change lock. */
    public static String console(String[] args) {
        if (!planeEnabled())
            return "This plane has no town specialties.";
        if (!enabled())
            return "This save has no town specialties (it was started before round 760).";
        MapStage stage = MapStage.getInstance();
        PointOfInterest poi = stage != null && stage.isInMap() && TileMapScene.instance() != null
                ? TileMapScene.instance().rootPoint : null;
        PointOfInterestChanges changes = poi == null ? null : stage.getChanges();
        if (args.length >= 1 && !"info".equalsIgnoreCase(args[0])) {
            if (!eligible(poi, changes))
                return "Stand in a town you hold (not the Capitol or Orazca).";
            switch (args[0].toLowerCase()) {
                case "set": {
                    Specialty s = args.length >= 2 ? Specialty.parse(args[1]) : null;
                    if (s == null)
                        return "specialty set Mining|Research|Outpost|Smithy|Garrison|Market";
                    pick(poi, changes, s, false, "console");
                    return poi.getDisplayName() + " is now " + s.withArticle() + " town.";
                }
                case "clear":
                    changes.setSpecialty(null);
                    changes.setSpecialtyAsked(false);
                    changes.setSpecialtyChangedDay(-1);
                    invalidate();
                    System.out.println("[TFR-Specialty] console: " + poi.getDisplayName() + "'s specialty cleared");
                    return poi.getDisplayName() + " has no specialty; it asks again on the next entry.";
                case "unlock":
                    changes.setSpecialtyChangedDay(-1);
                    changes.setMarketFreeRestockWeek(-1);
                    return poi.getDisplayName() + ": change lock and the week's Market restock reset.";
                default:
                    return "specialty [info | set <name> | clear | unlock]";
            }
        }
        StringBuilder sb = new StringBuilder();
        if (poi != null) {
            Specialty s = of(changes);
            sb.append(poi.getDisplayName()).append(": ").append(!eligible(poi, changes) ? "takes no specialty"
                    : s == null ? "none" : s.label + (lockDaysLeft(changes) > 0 ? ", locked " + lockDaysLeft(changes)
                    + " day(s)" : "") + (captureDefense(poi) > 0f ? ", capture defense +" + pct(captureDefense(poi)) : "")
                    + (s == Specialty.MARKET ? ", free restock " + (marketRestockFree(changes)
                    ? "ready" : "used this week") : "")).append(". ");
        }
        int[] counts = new int[Specialty.values().length];
        for (Object[] row : badgedTowns())
            counts[((Specialty) row[1]).ordinal()]++;
        sb.append("Realm:");
        for (Specialty s : Specialty.values())
            sb.append(' ').append(s.label).append(' ').append(counts[s.ordinal()]);
        invalidate();
        sb.append("; research +").append(pct(researchBonus())).append(" faster.");
        return sb.toString();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static int currentDay() {
        World world = Current.world();
        return world == null ? 0 : world.getCurrentDay();
    }

    static String currentTownName() {
        PointOfInterest poi = TileMapScene.instance() == null ? null : TileMapScene.instance().rootPoint;
        return poi == null ? "this town" : poi.getDisplayName();
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
