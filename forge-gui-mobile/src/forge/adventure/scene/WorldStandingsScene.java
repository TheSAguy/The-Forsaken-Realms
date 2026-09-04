package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.util.ColorReputation;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.util.TerritoryControl;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.adventure.stage.GameHUD;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Territory Control (MOD_SCOPE.md #7): a dedicated full-screen "World Standings" page showing
 * live per-color town counts, opened from a HUD button rather than a permanently-visible panel
 * (the earlier TownCountActor HUD panel this replaced was taking up too much on-screen space for
 * data that only changes every few in-game days). Own JSON layout lives under the plane's own
 * `ui/` folder (`The Forsaken Realms/ui/world_standings.json`) rather than forking a shared one -
 * same "new file in the mod folder, not an edit to a common one" pattern as everything else this
 * feature has added.
 */
public class WorldStandingsScene extends UIScene {
    private static final String ICON_ATLAS = "maps/tileset/color_icons.atlas";
    private static final int ICON_SIZE = 16;

    private final Table standingsList;
    private final Table chartArea;
    /** Where PlayerStatisticScene's own Back button should return to - the town/world scene the
     *  player opened this page from, NOT this page. Same contract as QuestLogScene's identically
     *  named field, and the reason instance() now takes a parameter (2026-09-01). */
    private Scene lastGameScene;

    private WorldStandingsScene() {
        // Portrait variant added 2026-08-30 (Android tester screenshot: the standings page had
        // "a lot of buttons weird on the Phone"). This scene was the ONE mod UI still hardcoding
        // its landscape layout - every sibling (research/inn/inventory/map/new_game/info_text)
        // already picks a *_portrait.json here - so on a phone it rendered a 480x270 layout into
        // a 270x480 screen, pushing the button column off the right edge.
        super(Forge.isLandscapeMode() ? "ui/world_standings.json" : "ui/world_standings_portrait.json");
        standingsList = ui.findActor("standingsList");
        chartArea = ui.findActor("chartArea");
        ui.onButtonPress("return", WorldStandingsScene.this::back);
        // "Info Page" wiki buttons (2026-08-11, round 8, user request: "a wiki, each will explain
        // some aspect we added") - plain info dialogs via the same createGenericDialog() pattern
        // every other explanatory/confirm dialog in the mod already uses, single "Close" button.
        ui.onButtonPress("reputationInfo", this::showReputationInfo);
        ui.onButtonPress("expansionInfo", this::showExpansionInfo);
        // Replaces the old "Explanations" button (2026-08-14 user request) - a single long-form
        // overview for players who have never touched this mod before, via InfoTextScene (the
        // same scrollable-page pattern Guard Info uses) since this page runs far longer than the
        // wrapped-Dialog wiki popups above can hold.
        ui.onButtonPress("modDetailsInfo", this::showModDetails);
        // Player statistics (user request 2026-09-01): the same Status button the quest log
        // already carries, duplicated here so the two "how am I doing" pages link to each other
        // instead of both needing a trip back through the HUD. Identical name/binding as
        // common/ui/quests.json's, so the Q hotkey works the same on both pages, and it hands
        // PlayerStatisticScene the same lastGameScene the quest log does - so its Back returns to
        // the town/world you came from, not to this page.
        ui.onButtonPress("status", this::status);
    }

    private void status() {
        Forge.switchScene(PlayerStatisticScene.instance(lastGameScene), true);
    }

    // Reputation tier table (2026-08-11, round 8) - values cross-checked directly against
    // ColorReputation.java rather than recalled from memory (getShopPriceMultiplier(),
    // getPlayerTownAttackWeight(), isEntryBarred()/isHealBarred(), CAPITAL_ENTRY_TOLL) so this
    // wiki text can't drift from what the tiers actually do.
    /** Info dialog with a WRAPPED, width-capped body. createGenericDialog()'s own label is
     *  unwrapped, so these long wiki texts made the dialog grow wider than the 480px stage -
     *  pushing the OK button off-screen, which also made the dialog impossible to dismiss
     *  (real soft-lock, user-reported 2026-08-12: "could not exit... had to force shut down").
     *  Same wrap+width(250-400) pattern EconomyBuildings' building-info dialogs already use. */
    private void showInfoDialog(String title, String text) {
        com.badlogic.gdx.scenes.scene2d.ui.Dialog dialog = createGenericDialog(title, null,
                Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null);
        TypingLabel label = Controls.newTypingLabel(text);
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(Forge.isLandscapeMode() ? 400f : 240f).row(); // round 100: portrait fit
        showDialog(dialog);
    }

    private void showReputationInfo() {
        showInfoDialog("Reputation",
                "Partner (+80 or higher): 30% cheaper card shops, 25% less likely to be attacked, free Inn healing, noticeably weaker enemies roaming their land.\n\n"
                        + "Happy (+30 to +79): 15% cheaper card shops, 5% less likely to be attacked, somewhat weaker enemies.\n\n"
                        + "Neutral (-29 to +29): no effect.\n\n"
                        + "Unhappy (-30 to -79): 25% pricier card shops, 5% more likely to be attacked, somewhat tougher enemies.\n\n"
                        + "War (-80 or lower): barred from that color's towns (Capitals: pay "
                        + ColorReputation.CAPITAL_ENTRY_TOLL + " gold to enter, 40% pricier once inside), "
                        + "25% more likely to be attacked, no healing at their Inns, noticeably tougher enemies roaming their land.\n\n"
                        + "Your own territory is also always safer to fight in than anyone else's, on top of all of the above.");
    }

    // Expansion/defense explainer (2026-08-11, round 8) - mechanics cross-checked against
    // TerritoryControl.java (attackerWinChance() tiers, GUARD_FIGHT_ATTACKER_BONUS,
    // OUTLOOK_DEFENSE_BONUS, ATTACKER_SACKS_TOWN_CHANCE) and WorldStage.startForcedCapitolDuel()/
    // triggerCapitolDefeat() rather than recalled from memory.
    private void showExpansionInfo() {
        showInfoDialog("Expansion",
                "Each color periodically sends a mage from its Castle toward one of its nearest "
                        + "neutral or enemy towns. Reaching an undefended town gives it a real chance to "
                        + "capture it - stronger mages (Apprentice/Adept/Master/Archmage) have a much "
                        + "better chance.\n\n"
                        + "Defending a town:\n"
                        + "- Hire Guards (Armory, Level 2) to fight the attacker before it can capture.\n"
                        + "- Build an Outlook to cut the capture chance by 5%.\n"
                        + "- Even a successful capture has a 20% chance the town is only sacked (reverted "
                        + "to ruins) instead of kept.\n\n"
                        + "Your Capitol is different: any mage that reaches it (after any hired guards "
                        + "fall) triggers a forced best-of-3 duel to defend it in person. Losing that "
                        + "duel ends your game.");
    }

    // Mod Details overview (2026-08-14 user request): "how the mod differs from base Shandalar...
    // how you unlock sets, how you defend yourself... terrain bonuses/penalties... starting race
    // sets... difficulty changes/effects... explain to someone who's never played what's going on
    // here." Facts cross-checked against MOD_SCOPE.md's own feature entries (#4 Progressive Set
    // Unlocks, #4b Race-Based Starting Expansions, #17 Territory Effects, #29 bonus-mage scaling)
    // rather than recalled from memory, same standard as the wiki dialogs above.
    private void showModDetails() {
        InfoTextScene.show("Mod Details", Arrays.asList(
                "Welcome to The Forsaken Realms - a custom world for Forge's Adventure mode. "
                        + "The five colors of Magic each rule a Castle and a swath of the map. Your job: "
                        + "carve out your own territory, build it up, and either out-develop the AI colors "
                        + "or bring their Castles down one by one.",

                "Unlike a stock Shandalar world, land here is OWNED. Every color's Castle slowly "
                        + "expands its terrain outward, repainting biomes and converting towns to its own "
                        + "banner. Your own Capitol does the same. Reaching a rival town undefended gives a "
                        + "mage a real shot at capturing it - and capturing enough of a color's territory, "
                        + "or killing its Castle outright, permanently pushes that color back.",

                "Defending what's yours: hire Guards at a town's Armory (Level 2) to fight off "
                        + "attacking mages before they can capture. Build an Outlook to cut capture odds "
                        + "further. Your own Capitol is different - any mage that fights through its "
                        + "Guards forces a personal best-of-3 duel to defend it, and losing that duel ends "
                        + "the run. Kill a rival color's Castle and its terrain and towns revert to "
                        + "neutral, but its survivors don't take it quietly: they field an extra attacking "
                        + "mage, skew tougher, and its allies are forced to send their next mage straight "
                        + "at you.",

                "Reputation tracks your standing with each color individually, from War up to "
                        + "Partner - it moves with what you do to that color's people and territory, and it "
                        + "swings shop prices, attack odds, and healing access at that color's towns. It "
                        + "also changes how fast you move: your own land is always friendlier to travel, "
                        + "and a color's land gets easier or harsher to cross the better or worse your "
                        + "standing with them is. The same standing also shifts what tier of enemy you're "
                        + "likely to run into on their land - and separately, week by week, the world as a "
                        + "whole trends toward tougher roaming enemies as a run goes on, capped well short "
                        + "of an endless escalation. Your own territory is always the safest place to fight.",

                "Card sets aren't all available on day one. This world splits every real Magic "
                        + "expansion six ways - one slice per color, plus a neutral slice - and you only "
                        + "start with a handful, decided by the race you pick at character creation (each "
                        + "race is tied to 4 lore-themed sets). Fighting monsters drops cards from the "
                        + "color you beat them in, and once you've found enough cards from a set, the "
                        + "Research Lab in your Capitol lets you formally unlock it for your own shops and "
                        + "Inn tournaments. Rival Capitols only ever sell from their own slice, difficulty "
                        + "permitting - so there's always a reason to go looking.",

                "Each of the 16 races is tied to its own 4 lore-themed sets:\n"
                        + "Devil: RNA, TOR, SOI, VOW\nKor: ZEN, BFZ, ZNR, ROE\nHuman: DOM, DMU, M20, M21\n"
                        + "Elf: LRW, MOR, KHM, ELD\nMetathran: INV, PLS, APC, 8ED\nUndead: AKH, HOU, ISD, DKA\n"
                        + "Viashino: GRN, ALA, ARB, DGM\nPhyrexian: SOM, MBS, NPH, ONE\nDwarf: KLD, AER, KHM, BRO\n"
                        + "Werewolf: ISD, MID, EMN, DKA\nLeonin: MRD, DST, AKH, IKO\nRed Dragon: DTK, TDM, M19, IKO\n"
                        + "White Dragon: DTK, TDM, M20, AFR\nBlue Dragon: DTK, TDM, M21, MH1\n"
                        + "Green Dragon: DTK, TDM, IKO, KHM\nBlack Dragon: DTK, TDM, AFR, VOW",

                "Difficulty (Easy/Normal/Hard/Insane) isn't just tougher monsters. It decides how "
                        + "many of your race's 4 sets you actually start with - Easy gives you all 4, Normal "
                        + "a random 3, Hard a random 2, Insane just 1 - so two Hard-or-below runs as the same "
                        + "race can start with different sets. It also scales how many mages a color can "
                        + "field against you at once, shop prices, and how many towns you need to hold "
                        + "before earning each bonus attacking mage against you. Insane plays meaningfully "
                        + "differently from Easy, not just harder.",

                "Town buildings and Ante, retuned: Mines and the Lumber Mill now pay out once a "
                        + "week (the same day 7/14/21 schedule Guards and Bank interest already run "
                        + "on) instead of daily - a Gold Mine, Lumber Mill, Stone Mine, or Shard Mine "
                        + "built mid-week still makes its first payout on the next weekly boundary, "
                        + "not 7 days after construction. If you play with Ante on, two options are "
                        + "new here: Re-roll your ante for Shards right after it's chosen (cost climbs "
                        + "50% with each re-roll that same duel), and Buy Back a card you lost to ante "
                        + "for gold, right from the \"Card Lost\" screen.",

                "Town Reputation - separate from your standing with the 5 colors above - caps how "
                        + "fast any one of your towns can rebuild. Every point of reputation with a town "
                        + "unlocks 3 more of its building slots (a town has 9, a Capitol 25), and you "
                        + "earn it by restoring the town in the first place, upgrading it to your Capitol, "
                        + "and defending it by defeating attacking mages there. Losing a point never tears "
                        + "down what's already built - it only pauses new construction until your standing "
                        + "climbs back over the threshold - and losing the town to an AI color resets it to "
                        + "zero. Completing that town's own quests is the fastest way to build it back up.",

                "Everything else - the Armory's rotating stock, weekly-refreshing shops, side "
                        + "quests, dungeons, the Arena's AI opponents - runs on the same Forge Adventure "
                        + "engine you'd find in any other world. If you've played Shandalar before, the "
                        + "controls are all familiar; what's new here is that the map itself has stakes."
        ));
    }

    private static WorldStandingsScene object;

    public static WorldStandingsScene instance() {
        return instance(null);
    }

    /** @param lastGameScene the scene the player opened this page from, forwarded to
     *  PlayerStatisticScene by the Status button so its Back lands somewhere sensible. Null is
     *  accepted (PlayerStatisticScene falls back to GameScene) - the no-arg overload above keeps
     *  any caller that does not care compiling unchanged. */
    public static WorldStandingsScene instance(Scene lastGameScene) {
        object = new WorldStandingsScene();
        object.lastGameScene = lastGameScene;
        return object;
    }

    @Override
    public void dispose() {
    }

    @Override
    public void enter() {
        super.enter();
        refresh();
    }

    private void refresh() {
        standingsList.clear();
        if (WorldSave.getCurrentSave() == null || WorldSave.getCurrentSave().getWorld() == null)
            return;

        World world = WorldSave.getCurrentSave().getWorld();
        Map<String, Integer> counts = TerritoryControl.getTownCounts(world);
        // Self-seed (2026-08-15): guarantees the line chart below always has at least one data
        // point by the time it renders, rather than showing "not enough data" for the first few
        // minutes of a brand new game before the first real day-tick fires this same call from
        // WorldStage.onActing(). Idempotent per week - a no-op if this week's already recorded.
        Map<String, Integer> chartCounts = new java.util.HashMap<>(counts);
        chartCounts.remove("Colorless");
        world.recordStandingsHistoryIfNewWeek(chartCounts);

        // Header row (per user mockup): blank cell over the icon column, then column titles.
        // Rebuilt every refresh since clear() above wipes the whole table. Rows stay in
        // getSortedStandingsRows()'s town-count order - per user decision, headers are labels
        // only, not sort toggles, for now.
        // Column packing (user layout request 2026-08-08): Reputation/Status sit immediately
        // right of Town Count instead of drifting to the table's far edge - the expandX slack
        // lives on the LAST column, so everything else hugs left as one block. Both numeric
        // columns right-align so each color's count and reputation digits line up per row.
        boolean showReputation = ColorReputation.isEnabled();
        standingsList.add();
        TypingLabel countHeader = Controls.newTypingLabel("[%75]Town Count");
        countHeader.setColor(Color.BLACK);
        countHeader.skipToTheEnd();
        standingsList.add(countHeader).align(Align.left).padRight(16).padBottom(4);
        if (showReputation) {
            TypingLabel repHeader = Controls.newTypingLabel("[%75]Reputation");
            repHeader.setColor(Color.BLACK);
            repHeader.skipToTheEnd();
            standingsList.add(repHeader).align(Align.left).padRight(16).padBottom(4);
            TypingLabel statusHeader = Controls.newTypingLabel("[%75]Status");
            statusHeader.setColor(Color.BLACK);
            statusHeader.skipToTheEnd();
            standingsList.add(statusHeader).align(Align.left).expandX().padBottom(4);
        } else {
            standingsList.add().expandX();
        }
        standingsList.row();

        for (String row : TerritoryControl.getSortedStandingsRows(counts)) {
            Image icon = null;
            if ("Player".equals(row)) {
                // The little HUD portrait (GameHUD's own "avatar" actor uses the exact same
                // source, Current.player().avatar()) - a dot/marker texture like the minimap's
                // own miniMapPlayer isn't "his picture," this is the actual chosen player avatar.
                icon = new Image(new TextureRegionDrawable(Current.player().avatar()));
            } else {
                TextureRegion region = Config.instance().getAtlasSprite(ICON_ATLAS, row);
                if (region != null)
                    icon = new Image(new TextureRegionDrawable(region));
            }
            if (icon != null) {
                standingsList.add(icon).size(ICON_SIZE).padRight(6).padBottom(6);
            } else {
                standingsList.add();
            }
            // Color Defeat discoverability (2026-08-15 review finding): a defeated color's town
            // count is always 0, indistinguishable at a glance from a color that simply hasn't
            // expanded yet - the only other player-facing signal was one overwritable HUD toast at
            // the moment of defeat. Tag the row instead of just showing the bare number. Checked
            // independent of showReputation/ColorReputation.isEnabled() - Color Defeat is a
            // Territory Control concept, not gated by the Reputation feature toggle.
            Integer defeatDay = world.isColorDefeated(row.toLowerCase()) ? world.getColorDefeatDay(row.toLowerCase()) : null;
            // Mixed-color content within one label needs BOTH halves explicitly bracketed (same
            // tint gotcha as the Status column below: actor tint MULTIPLIES inline glyph colors,
            // so a WHITE tint only "preserves" a segment that's ALREADY inline-tagged - a plain,
            // untagged segment would render white too, not black, if left unbracketed).
            String countText = defeatDay != null
                    ? "[BLACK]" + counts.get(row) + "[] [RED](Defeated Day " + defeatDay + ")[]"
                    : String.valueOf(counts.get(row));
            TypingLabel countLabel = Controls.newTypingLabel(countText);
            countLabel.setColor(defeatDay != null ? Color.WHITE : Color.BLACK);
            countLabel.skipToTheEnd();
            standingsList.add(countLabel).align(Align.right).padRight(16).padBottom(6);

            // Reputation column (MOD_SCOPE.md #1): only the 5 AI colors have a value - the
            // Player and Colorless rows leave the cell blank (neutral has no reputation by
            // design, and "reputation with yourself" is meaningless). Same font size as the
            // count column (no [%85] shrink) and right-aligned, per user layout request - each
            // row's count and reputation digits line up.
            if (showReputation) {
                String colorKey = row.toLowerCase();
                boolean isAiColor = false;
                for (String c : ColorReputation.COLORS)
                    if (c.equals(colorKey)) { isAiColor = true; break; }
                if (isAiColor) {
                    int rep = ColorReputation.displayValue(Current.player().getColorReputationHalfPoints(colorKey));
                    String number = rep > 0 ? "+" + rep : String.valueOf(rep);
                    // Colored by reputation TIER, not just sign (user request 2026-08-11, moved
                    // from the number onto the STATUS WORD 2026-08-14 per user follow-up - the
                    // number now stays plain black like every other numeric column in this scene,
                    // and the tier color rides the word instead): Red for War, Orange for
                    // Unhappy, Green for Partner, light blue (Cyan) for Happy - Neutral stays
                    // plain, matching the previous "0" case.
                    String colorTag;
                    switch (ColorReputation.getStatus(colorKey)) {
                        case PARTNER: colorTag = "[GREEN]"; break;
                        case HAPPY: colorTag = "[CYAN]"; break;
                        case UNHAPPY: colorTag = "[ORANGE]"; break;
                        case WAR: colorTag = "[RED]"; break;
                        default: colorTag = ""; break;
                    }
                    TypingLabel repLabel = Controls.newTypingLabel(number);
                    repLabel.setColor(Color.BLACK);
                    repLabel.skipToTheEnd();
                    standingsList.add(repLabel).align(Align.right).padRight(16).padBottom(6);
                    TypingLabel statusLabel = Controls.newTypingLabel(colorTag + ColorReputation.getStatus(colorKey).label);
                    // Actor tint MULTIPLIES the glyph colors (see GameHUD.addNotification's
                    // comment on the same rule) - a BLACK tint erases any inline [COLOR] tag.
                    // WHITE tint preserves the markup; Neutral rows carry no tag and stay plain.
                    statusLabel.setColor(colorTag.isEmpty() ? Color.BLACK : Color.WHITE);
                    statusLabel.skipToTheEnd();
                    standingsList.add(statusLabel).align(Align.left).padBottom(6);
                } else {
                    standingsList.add();
                    standingsList.add();
                }
            }
            standingsList.row();
        }
        refreshChart(world);
    }

    // The 6 series this chart plots - 5 AI colors + Player, Colorless deliberately excluded per
    // user spec ("Leave out neutral - 5 AI and Player only"). Order matches the user's own mockup
    // legend left-to-right.
    private static final String[] CHART_ROWS = {"Green", "White", "Blue", "Black", "Red", "Player"};
    private static final float CHART_LEGEND_HEIGHT = 24f;
    private static final float CHART_X_AXIS_HEIGHT = 10f;
    private static final float CHART_Y_AXIS_WIDTH = 14f;
    private static final float CHART_LINE_THICKNESS = 1.3f;
    private static final float CHART_POINT_SIZE = 4f;

    // Line chart, town count by week, rolling window (2026-08-15 user request, mockup supplied) -
    // replaces the earlier per-refresh snapshot bar chart now that World persists real weekly
    // history (World.recordStandingsHistoryIfNewWeek()/getStandingsHistoryWeeks()/
    // getStandingsHistoryCounts()). Built entirely from positioned Image/TypingLabel actors added
    // directly to chartArea (a Table used purely as a blank freely-positioned canvas here, same
    // "Table is-a Group" technique GameHUD/MapViewScene already rely on for manual layout) rather
    // than table cells, since a line chart needs arbitrary x/y placement no row/column flow can
    // express. Uniform small square point markers for every series (not the distinct per-series
    // shapes in the user's own mockup reference image) - color alone already distinguishes all 6
    // lines, and building 6 unique marker shapes would need new art or hand-drawn polygons for
    // comparatively little added legibility.
    private void refreshChart(World world) {
        chartArea.clear();
        List<Integer> weeks = world.getStandingsHistoryWeeks();
        if (weeks.isEmpty()) {
            TypingLabel placeholder = Controls.newTypingLabel("[%70]Not enough data yet - check back in a few in-game days.");
            placeholder.setWrap(true);
            placeholder.setColor(Color.BLACK);
            placeholder.skipToTheEnd();
            placeholder.setSize(chartArea.getWidth(), chartArea.getHeight());
            placeholder.setPosition(0, 0);
            chartArea.addActor(placeholder);
            return;
        }
        Map<String, List<Integer>> history = world.getStandingsHistoryCounts();

        float w = chartArea.getWidth();
        float h = chartArea.getHeight();
        // Legend reserved at the TOP (matches the user's own mockup and the legend-placement loop
        // below, which is itself top-anchored: ly = h - (row+1)*12), x-axis labels at the BOTTOM,
        // y-axis labels along the LEFT, plot in between. Fixed 2026-08-15 (user report: "a number
        // showing up above the green square") - plotY/plotH were previously computed as if the
        // legend's space was reserved at the BOTTOM while the legend was actually drawn at the
        // TOP, so the Y-axis max-value label (drawn just above the plot's own top edge, which
        // wrongly extended all the way to h) landed almost exactly on top of the legend's first
        // row instead of sitting below it.
        float plotX = CHART_Y_AXIS_WIDTH;
        float plotW = w - CHART_Y_AXIS_WIDTH;
        float plotBottom = CHART_X_AXIS_HEIGHT;
        float plotTop = h - CHART_LEGEND_HEIGHT;
        float plotH = plotTop - plotBottom;

        int maxValue = 1;
        for (String row : CHART_ROWS) {
            List<Integer> series = history.get(row);
            if (series != null)
                for (int v : series)
                    maxValue = Math.max(maxValue, v);
        }
        int yMax = maxValue + Math.max(1, maxValue / 5); // headroom so the tallest point isn't glued to the top edge

        int n = weeks.size();
        float stepX = n > 1 ? plotW / (n - 1) : 0f;

        TypingLabel yTop = Controls.newTypingLabel("[%65]" + yMax);
        yTop.setColor(Color.BLACK);
        yTop.skipToTheEnd();
        yTop.setSize(CHART_Y_AXIS_WIDTH, 10f);
        yTop.setPosition(0, plotTop - 8f);
        chartArea.addActor(yTop);

        TypingLabel yBottom = Controls.newTypingLabel("[%65]0");
        yBottom.setColor(Color.BLACK);
        yBottom.skipToTheEnd();
        yBottom.setSize(CHART_Y_AXIS_WIDTH, 10f);
        yBottom.setPosition(0, plotBottom - 2f);
        chartArea.addActor(yBottom);

        TypingLabel xFirst = Controls.newTypingLabel("[%65]Wk" + weeks.get(0));
        xFirst.setColor(Color.BLACK);
        xFirst.skipToTheEnd();
        xFirst.setSize(30f, CHART_X_AXIS_HEIGHT);
        xFirst.setPosition(plotX, 0f);
        chartArea.addActor(xFirst);

        if (n > 1) {
            TypingLabel xLast = Controls.newTypingLabel("[%65]Wk" + weeks.get(n - 1));
            xLast.setColor(Color.BLACK);
            xLast.skipToTheEnd();
            xLast.setSize(30f, CHART_X_AXIS_HEIGHT);
            xLast.setAlignment(Align.right);
            xLast.setPosition(plotX + plotW - 30f, 0f);
            chartArea.addActor(xLast);
        }

        for (String row : CHART_ROWS) {
            List<Integer> series = history.get(row);
            if (series == null || series.isEmpty())
                continue;
            Color color = chartColor(row);
            float prevX = -1, prevY = -1;
            int points = Math.min(series.size(), n);
            for (int i = 0; i < points; i++) {
                float x = plotX + stepX * i;
                float y = plotBottom + plotH * (series.get(i) / (float) yMax);
                if (prevX >= 0)
                    addChartLine(prevX, prevY, x, y, color);
                addChartPoint(x, y, color);
                prevX = x;
                prevY = y;
            }
        }

        for (int i = 0; i < CHART_ROWS.length; i++) {
            int col = i % 3;
            int legendRow = i / 3;
            float lx = col * (w / 3f);
            float ly = h - (legendRow + 1) * 12f;
            Image swatch = new Image(Controls.getSkin().getDrawable("white-pixel"));
            swatch.setColor(chartColor(CHART_ROWS[i]));
            swatch.setSize(6f, 6f);
            swatch.setPosition(lx, ly + 2f);
            chartArea.addActor(swatch);
            TypingLabel legendLabel = Controls.newTypingLabel("[%60]" + CHART_ROWS[i]);
            legendLabel.setColor(Color.BLACK);
            legendLabel.skipToTheEnd();
            legendLabel.setSize(w / 3f - 8f, 10f);
            legendLabel.setPosition(lx + 8f, ly);
            chartArea.addActor(legendLabel);
        }
    }

    private static Color chartColor(String row) {
        return "Player".equals(row) ? Color.GOLD : GameHUD.getMageMarkerColor(row.toLowerCase());
    }

    private void addChartLine(float x1, float y1, float x2, float y2, Color color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
        Image line = new Image(Controls.getSkin().getDrawable("white-pixel"));
        line.setColor(color);
        line.setSize(length, CHART_LINE_THICKNESS);
        line.setOrigin(0f, CHART_LINE_THICKNESS / 2f);
        line.setPosition(x1, y1 - CHART_LINE_THICKNESS / 2f);
        line.setRotation(angle);
        chartArea.addActor(line);
    }

    private void addChartPoint(float x, float y, Color color) {
        Image point = new Image(Controls.getSkin().getDrawable("white-pixel"));
        point.setColor(color);
        point.setSize(CHART_POINT_SIZE, CHART_POINT_SIZE);
        point.setPosition(x - CHART_POINT_SIZE / 2f, y - CHART_POINT_SIZE / 2f);
        chartArea.addActor(point);
    }
}
