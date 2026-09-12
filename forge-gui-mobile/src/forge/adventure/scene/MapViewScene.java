package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.SnapshotArray;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TypingLabel;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.AdventureQuestData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.world.WorldSave;

import java.util.List;
import java.util.Set;

/**
 * Displays the rewards of a fight or a treasure
 */
public class MapViewScene extends UIScene {
    private static MapViewScene object;
    private final ScrollPane scroll;
    private final Image img;
    private Texture miniMapTexture; // this plane's own minimap texture - see refreshMap()
    private final Image miniMapPlayer;
    private final Group table;
    private final List<TypingLabel> labels;
    private int index = -1;
    private float avatarX = 0, avatarY = 0;
    private Set<Vector2> positions;
    private final List<TypingLabel> details;
    /** WORLD position each entry of {@code details} is anchored to, index-aligned with it - so a zoom
     *  step can lay the labels out again from scratch instead of nudging them (round 162). */
    private final List<float[]> detailAnchors = Lists.newArrayList();
    private final float maxZoom = 1.2f;
    // Round 152 (user request: "Is it possible to have the over-world map zoom out further?").
    // 0.25 -> 0.12 is about seven more 0.9x steps, roughly halving the smallest scale again.
    private final float minZoom = 0.12f;
    private Set<PointOfInterest> bookmark;
    private int lastOverlayMode = 0; // 0=none, 1=details, 2=events, 3=reputation
    // Territory Control (MOD_SCOPE.md #7): one colored dot per in-flight capture mage, same
    // palette as the corner minimap's own dots (GameHUD.getMageMarkerColor()) - requested
    // directly: "I see the mages on the small mini-map... but I don't see them on the
    // mini-map when I look at the Zoom view." Rebuilt on every enter() from live WorldStage
    // state - this whole scene is a static snapshot (the player marker is positioned once on
    // enter the same way), so no per-frame tracking is needed here.
    private final List<Image> mageMarkers = Lists.newArrayList();
    // Round 162 (user: "the attack lines do not refresh / remove when you click through the
    // different views"; review S5/S6). The Attacks view's lines used to be dropped into
    // mageMarkers at UNZOOMED map coordinates, and no other view knew they were there: they
    // survived every later view, and after a zoom step sat off the map. They now live in their
    // own lists, every view clears them, and layoutAttacks() places them through the same
    // scale-and-offset the labels use, re-laying them on every zoom step. Endpoints are kept as
    // WORLD coordinates so a re-layout is a pure function of the current zoom.
    private final List<float[]> attackEnds = Lists.newArrayList();
    private final List<Color> attackColors = Lists.newArrayList();
    private final List<Image> attackLines = Lists.newArrayList();
    /** The homeward guard line: the guard dot's LIME, dimmed. */
    private static final Color GUARD_HOMEWARD = new Color(0.55f, 0.8f, 0.45f, 1f);

    public static MapViewScene instance() {
        if (object == null)
            object = new MapViewScene();
        return object;
    }

    private MapViewScene() {
        super(Forge.isLandscapeMode() ? "ui/map.json" : "ui/map_portrait.json");
        ui.onButtonPress("done", this::done);
        ui.onButtonPress("quest", this::scroll);
        //TODO:Add Translations for buttons
        ui.onButtonPress("details", this::details);
        ui.onButtonPress("events", this::events);
        ui.onButtonPress("reputation", this::reputation);
        ui.onButtonPress("names", this::names);
        ui.onButtonPress("attacks", this::attacks); // round 156
        ui.onButtonPress("zoomIn", this::zoomIn);
        ui.onButtonPress("zoomOut", this::zoomOut);
        scroll = new ScrollPane(null,Controls.getSkin()) {
            @Override
            public void addScrollListener() {
                return;
            }
        };
        scroll.setName("map");
        scroll.setActor(Controls.newTextraLabel(""));
        scroll.setWidth(ui.findActor("map").getWidth());
        scroll.setHeight(ui.findActor("map").getHeight());
        ui.addActor(scroll);
        scroll.setZIndex(1);
        labels = Lists.newArrayList();
        positions = Sets.newHashSet();
        bookmark = Sets.newHashSet();
        table = new Group();
        scroll.setActor(table);
        img = new Image();
        miniMapPlayer = new Image();
        img.setPosition(0, 0);
        table.addActor(img);
        table.addActor(miniMapPlayer);
        miniMapPlayer.setZIndex(2);
        details = Lists.newArrayList();
        ui.addListener(new InputListener() {
            public boolean scrolled(InputEvent event, float x, float y, float scrollAmountX, float scrollAmountY) {
                event.cancel();
                scroll.setScrollbarsVisible(true);
                if (scrollAmountY > 0) {
                    zoomOut();
                    return true;
                } else if (scrollAmountY < 0) {
                    zoomIn();
                    return true;
                }
                return false;
            }
        });
        stage.setScrollFocus(ui);

    }

    public void test() {
        img.setPosition((scroll.getScrollPercentX()*2334 +233)*0.1f + 0.9f*img.getX(),(2544-scroll.getScrollPercentY()*2544 +128)*0.1f + 0.9f*img.getY());
        img.setScale(img.getScaleX()*0.9f);
        miniMapPlayer.setPosition((scroll.getScrollPercentX()*2334 +233)*0.1f + 0.9f*miniMapPlayer.getX(),(2544-scroll.getScrollPercentY()*2544 +128)*0.1f + 0.9f*miniMapPlayer.getY());
        miniMapPlayer.setScale(miniMapPlayer.getScaleX()*0.9f);
        for(Actor actor : table.getChildren()) {
            if (actor instanceof TypingLabel) {
                actor.setPosition((scroll.getScrollPercentX() * 2334 + 233) * 0.1f + 0.9f * actor.getX(), (2544 - scroll.getScrollPercentY() * 2544 + 128) * 0.1f + 0.9f * actor.getY());
            }
        }
    }

    public boolean done() {
        GameHUD.getInstance().getTouchpad().setVisible(false);
        SnapshotArray<Actor> allActors = table.getChildren();
        for (int i = 0; i < allActors.size; i++) {
            if (allActors.get(i) instanceof TypingLabel) {
                allActors.get(i).remove();
                i--;
            }
        }
        labels.clear();
        positions.clear();
        details.clear();
        detailAnchors.clear();
        // The TypingLabel sweep above doesn't catch the mage marker Images - remove them
        // explicitly (they're rebuilt from live state on every enter() anyway).
        for (Image marker : mageMarkers)
            marker.remove();
        mageMarkers.clear();
        clearAttacks();
        miniMapPlayer.setScale(1);
        img.setScale(1);
        img.setPosition(0,0);
        index = -1;
        Forge.switchToLast();
        return true;
    }

    public void addBookmark(PointOfInterest point) {
        if (point == null)
            return;
        bookmark.add(point);
    }

    public void removeBookmark(PointOfInterest point) {
        if (point == null)
            return;
        bookmark.remove(point);
    }

    public boolean scroll() {
        if (!labels.isEmpty()) {
            index++;
            if (index >= labels.size()) {
                index = -1;
                scroll.scrollTo(avatarX, avatarY, miniMapPlayer.getWidth(), miniMapPlayer.getHeight(), true, true);
                return true;
            }
            TypingLabel label = labels.get(index);
            scroll.scrollTo(label.getX(), label.getY(), miniMapPlayer.getWidth(), miniMapPlayer.getHeight(), true, true);
        }
        return true;
    }


    private void setOverlayButtonStates(int mode) {
        String[] buttons = {"details", "events", "reputation", "names", "attacks"};
        // Each mode shows only the *next* button in the cycle
        // mode 0 (none/names): show "details"
        // mode 1 (details):    show "events"
        // mode 2 (events):     show "reputation"
        // mode 3 (reputation): show "names"
        int activeIndex = mode; // the button to show (wraps: 0->details, 1->events, 2->reputation, 3->names)
        for (int i = 0; i < buttons.length; i++) {
            TextraButton btn = ui.findActor(buttons[i]);
            if (btn != null) {
                btn.setVisible(i == activeIndex);
                btn.setDisabled(i != activeIndex);
            }
        }
    }

    public void details() {
        lastOverlayMode = 1;
        setOverlayButtonStates(1);
        // Self-cleanup (2026-08-16 review finding) - the other 3 overlay builders (events()/
        // reputation()/names()) all clear their own previously-added labels before rebuilding;
        // this one never did, so a double enter()/details() call (without an intervening leave())
        // would silently stack a second full set of labels on top of the first. Matches the
        // sibling pattern exactly.
        clearDetails();
        clearAttacks();
        List<PointOfInterest> allPois = Current.world().getAllPointOfInterest();
        // GLOBAL label collision avoidance (2026-08-15, replaces the earlier per-POI-only offset
        // map - user screenshot near the Capitol showed labels from three DIFFERENT nearby POIs
        // garbled into each other, which per-POI stacking is architecturally incapable of
        // preventing): every placed label records its rectangle; a new label that would intersect
        // any already-placed one shifts DOWN one label height at a time until clear. Seeded with
        // the quest/bookmark TypingLabels enter() already placed directly on the table, so
        // detail labels dodge those too. Same-POI stacking (name + event + under-attack) falls
        // out of the same rule with no special casing.
        List<Rectangle> placedLabelRects = Lists.newArrayList();
        for (Actor existing : table.getChildren()) {
            if (existing instanceof TypingLabel)
                placedLabelRects.add(new Rectangle(existing.getX(), existing.getY(), existing.getWidth(), existing.getHeight()));
        }
        // Round 156 (user: "On the mini-map, guard info screen, remove the Set information. It
        // should not be on that screen."). The event/set-block labels used to be drawn here, from
        // a 2026-08-17 request made before this overlay also carried Under Attack and garrison
        // labels. With all three competing for the same POI positions the set names were the ones
        // crowding out the information the player opens this view for, so they are gone.

        // Towns under attack (2026-08-14 user request: "Details or Events could show towns under
        // attack" - the minimap buttons audit found neither actually did). One label per in-
        // flight Territory Control capture mage with a live target, drawn at the TARGET town's
        // position (the mage's own current position already has its own dot via the marker loop
        // in enter()) - colored the same as that mage's minimap dot for a consistent read. Safe
        // on every plane, not just this one: getTerritoryMages() simply returns empty where
        // Territory Control isn't active.
        // Round 183 (code review S10): ONE "Under Attack!" per town, with the attacker count - a label per mage
        // stacked at the same town used up the shift budget, so the garrison label placed after them was the
        // one dropped, on exactly the town that most needed it.
        java.util.Map<String, Integer> attackersPerTown = new java.util.HashMap<>();
        for (EnemySprite mage : WorldStage.getInstance().getTerritoryMages())
            if (mage.territoryTarget != null)
                attackersPerTown.merge(mage.territoryTarget.getID(), 1, Integer::sum);
        java.util.Set<String> labelledTowns = new java.util.HashSet<>();
        for (EnemySprite mage : WorldStage.getInstance().getTerritoryMages()) {
            PointOfInterest targetPoi = mage.territoryTarget;
            if (targetPoi == null || !labelledTowns.add(targetPoi.getID()))
                continue;
            // Fog-of-war gate (2026-08-15 adversarial review finding) - same check the mage-dot
            // marker loop in enter() already applies to the mage's OWN position; without it this
            // label leaked an unexplored town's existence/location/under-attack status through
            // solid fog, since it draws at the TARGET's position rather than the mage's.
            int targetTileX = (int) (targetPoi.getPosition().x / WorldSave.getCurrentSave().getWorld().getTileSize());
            int targetTileY = (int) (targetPoi.getPosition().y / WorldSave.getCurrentSave().getWorld().getTileSize());
            if (!WorldSave.getCurrentSave().getWorld().isCurrentlyVisible(targetTileX, targetTileY))
                continue;
            int attackers = attackersPerTown.getOrDefault(targetPoi.getID(), 1);
            TypingLabel label = Controls.newTypingLabel("[%?BLACKEN] Under Attack!" + (attackers > 1 ? " x" + attackers : ""));
            label.setColor(GameHUD.getMageMarkerColor(mage.territoryColor));
            placeDetailLabel(label, targetPoi.getPosition().x, targetPoi.getPosition().y, placedLabelRects);
        }

        // Garrison strength (round 147, user request). Added to THIS overlay rather than a new one
        // because Details is already where "Under Attack!" lives, and the two answer the same
        // question together: which of my towns is threatened, and what is standing in that town.
        // Same fog-of-war gate as above - a garrison label would otherwise leak an unexplored
        // town's existence.
        for (PointOfInterest poi : allPois) {
            forge.adventure.pointofintrest.PointOfInterestChanges changes =
                    WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
            if (changes == null || !forge.adventure.util.TownRestoration.isTownRestored(changes))
                continue;
            int tileX = (int) (poi.getPosition().x / WorldSave.getCurrentSave().getWorld().getTileSize());
            int tileY = (int) (poi.getPosition().y / WorldSave.getCurrentSave().getWorld().getTileSize());
            if (!WorldSave.getCurrentSave().getWorld().isCurrentlyVisible(tileX, tileY))
                continue;
            StringBuilder garrison = new StringBuilder();
            for (int i = 0; i < changes.getGuardCount(); i++) {
                if (garrison.length() > 0)
                    garrison.append(", ");
                garrison.append(forge.adventure.util.EconomyBuildings.guardTierDisplayName(changes.getGuardTier(i)));
            }
            // A roaming guard on its way here (or already standing at the gate) counts as part of
            // this town's defence for the purposes of "what is protecting this place".
            for (forge.adventure.data.RoamingGuardData roamer : forge.adventure.util.RoamingGuards.roster()) {
                if (roamer.returningHome || !poi.getID().equals(roamer.missionPoiId))
                    continue;
                if (garrison.length() > 0)
                    garrison.append(", ");
                garrison.append("Roaming ").append(forge.adventure.util.RoamingGuards.displayName(roamer.tier));
            }
            if (garrison.length() == 0)
                continue;
            TypingLabel label = Controls.newTypingLabel("[%?BLACKEN] Guards: " + garrison);
            placeDetailLabel(label, poi.getPosition().x, poi.getPosition().y, placedLabelRects);
        }
    }

    /** Places one Details-overlay label centered at the given WORLD position (converted through
     *  the current img scale/offset, same math the old inline placement used), shifting it DOWN
     *  one label height at a time while its rectangle would intersect any already-placed label's,
     *  then records the final rectangle. libGDX is y-up, so "down" = subtract - the same
     *  direction the old per-POI eventLabelYOffset already shifted. */
    /** How far a label may be nudged to dodge its neighbours before it is dropped instead - see
     *  the note in placeDetailLabel(). Four label-heights is far enough to unstack a small cluster
     *  and short enough that the label is still plainly attached to its own POI. */
    private static final int MAX_LABEL_SHIFTS = 4;

    private static boolean overlapsAny(Rectangle rect, List<Rectangle> placed) {
        for (Rectangle other : placed)
            if (rect.overlaps(other))
                return true;
        return false;
    }

    private void placeDetailLabel(TypingLabel label, float worldX, float worldY, List<Rectangle> placedLabelRects) {
        table.addActor(label);
        details.add(label);
        detailAnchors.add(new float[]{worldX, worldY});
        // Root cause of labels rendering fused/overlapping (user report, 4th time raised)
        // 2026-08-17: TypingLabel/TextraLabel (textratypist 0.8.2) never call setSize()/pack()
        // internally, so immediately after addActor() getWidth()/getHeight() both read 0 - every
        // collision rectangle below is 0x0, Rectangle.overlaps() can never return true, and the
        // "shift down until clear" loop never fires no matter how many labels share a position.
        // pack() sizes the label from its own preferred size without touching position.
        label.pack();
        float x = img.getScaleX() * (getMapX(worldX) - label.getWidth() / 2) + img.getX();
        float y = img.getScaleY() * (getMapY(worldY) - label.getHeight() / 2) + img.getY();
        Rectangle rect = new Rectangle(x, y, label.getWidth(), label.getHeight());
        // ROUND 158 BUG FIX (user playtest: "there are Roaming Guard labels on black towns... Not
        // sure why"). The data was right - the LABELS had walked. This loop shifts a label down one
        // height at a time until it clears every other label, with no limit, so on a crowded map a
        // garrison label slid far enough from its own town to come to rest over somebody else's,
        // which reads as a flat lie about who holds that town. Bounded to four steps now; a label
        // that still cannot find room is dropped rather than parked somewhere untrue.
        // Round 183 (code review S10): every position up to MAX_LABEL_SHIFTS is TESTED - the old loop stopped after
        // the 4th shift without checking it, so the effective cap was 3.
        int shifts = 0;
        boolean blocked = overlapsAny(rect, placedLabelRects);
        while (blocked && shifts < MAX_LABEL_SHIFTS) {
            rect.y -= label.getHeight();
            shifts++;
            blocked = overlapsAny(rect, placedLabelRects);
        }
        if (blocked) { // still colliding after the cap - better absent than misplaced
            table.removeActor(label);
            details.remove(label);
            detailAnchors.remove(detailAnchors.size() - 1);
            return;
        }
        placedLabelRects.add(rect);
        label.setPosition(rect.x, rect.y);
        label.skipToTheEnd();
    }

    public void events() {
        lastOverlayMode = 2;
        setOverlayButtonStates(2);
        clearDetails();
        clearAttacks();
        // Routed through placeDetailLabel()'s collision avoidance (2026-08-17) - previously
        // placed directly with no overlap protection at all, unlike details()/names().
        List<Rectangle> placedLabelRects = Lists.newArrayList();
        for (Actor existing : table.getChildren()) {
            if (existing instanceof TypingLabel)
                placedLabelRects.add(new Rectangle(existing.getX(), existing.getY(), existing.getWidth(), existing.getHeight()));
        }
        List<PointOfInterest> allPois = Current.world().getAllPointOfInterest();
        for (PointOfInterest poi : allPois) {
            int rep = WorldSave.getCurrentSave().getPointOfInterestChanges(poi.getID()).getMapReputation();
            if (rep != 0) {
                TypingLabel label = Controls.newTypingLabel("[%?BLACKEN] " + rep);
                placeDetailLabel(label, poi.getPosition().x, poi.getPosition().y, placedLabelRects);
            }
        }
    }

    public void reputation() {
        lastOverlayMode = 3;
        setOverlayButtonStates(3);
        clearDetails();
        clearAttacks();
        // Routed through placeDetailLabel()'s collision avoidance (2026-08-17) - same reasoning
        // as events() above.
        List<Rectangle> placedLabelRects = Lists.newArrayList();
        for (Actor existing : table.getChildren()) {
            if (existing instanceof TypingLabel)
                placedLabelRects.add(new Rectangle(existing.getX(), existing.getY(), existing.getWidth(), existing.getHeight()));
        }
        List<PointOfInterest> allPois = Current.world().getAllPointOfInterest();
        for (PointOfInterest poi : allPois) {
            if (WorldSave.getCurrentSave().getPointOfInterestChanges(poi.getID()).isVisited()) {
                if ("cave".equalsIgnoreCase(poi.getData().type) || "dungeon".equalsIgnoreCase(poi.getData().type) || "castle".equalsIgnoreCase(poi.getData().type)) {
                    TypingLabel label = Controls.newTypingLabel("[%?BLACKEN] " + poi.getDisplayName());
                    placeDetailLabel(label, poi.getPosition().x, poi.getPosition().y, placedLabelRects);
                }
            }
        }
    }

    public void names() {
        // Round 158: names() used to close the cycle back to Details, so the "attacks" button added
        // in round 156 was never made visible and the overlay could not be opened at all (its own
        // log line never appeared once in the user's session). Names now hands off to Attacks, and
        // attacks() closes the cycle.
        // Round 164 (Android/plane pass): a plane whose map layout has no "attacks" button (the stock
        // common/ui/map.json) used to dead-end here - the next button in the cycle did not exist, so
        // the only way back to Details was to leave the map. Close the cycle at Names on such planes.
        boolean hasAttacks = ui.findActor("attacks") != null;
        lastOverlayMode = hasAttacks ? 4 : 0;
        setOverlayButtonStates(hasAttacks ? 4 : 0);
        clearDetails();
        clearAttacks();

        // Town/capital names (moved here from details() - user request 2026-08-17: "Town names
        // need to be moved to the Names view and details should show the set info"). Visited-only,
        // same reasoning details() always used this text under: you learn a town's name by going
        // there, and it keeps hundreds of wilderness POIs from smothering the map.
        List<Rectangle> placedLabelRects = Lists.newArrayList();
        for (Actor existing : table.getChildren()) {
            if (existing instanceof TypingLabel)
                placedLabelRects.add(new Rectangle(existing.getX(), existing.getY(), existing.getWidth(), existing.getHeight()));
        }
        List<PointOfInterest> allPois = Current.world().getAllPointOfInterest();
        for (PointOfInterest poi : allPois) {
            String poiType = poi.getData().type;
            if (("town".equalsIgnoreCase(poiType) || "capital".equalsIgnoreCase(poiType))
                    && WorldSave.getCurrentSave().getPointOfInterestChanges(poi.getID()).isVisited()) {
                TypingLabel nameLabel = Controls.newTypingLabel("[%?BLACKEN] " + poi.getDisplayName());
                placeDetailLabel(nameLabel, poi.getPosition().x, poi.getPosition().y, placedLabelRects);
            }
        }
    }

    /**
     * Round 156 (user request: "Would it be possible to create little attack lines from the
     * attacking mage to the town he is heading? - Maybe as its own view, since it might clutter
     * the current view"). Its own overlay, exactly for that reason: the Details view already
     * carries Under Attack labels and garrison strength, and a line per mage on top of that is
     * unreadable.
     * <p>
     * scene2d has no line primitive, so each line is the minimap's own dot texture stretched to
     * the distance, one pixel tall, and rotated to the bearing - the standard trick, and it costs
     * no new art. Lines are re-laid from world coordinates on every zoom step (layoutAttacks())
     * and cleared by every other view - see attackEnds. Round 162 added the guard lines.
     */
    public void attacks() {
        lastOverlayMode = 0;
        setOverlayButtonStates(0);
        clearDetails();
        clearAttacks();
        // The mage and guard dots from enter() stay put: a line reads as travelling FROM its dot,
        // so the old per-line "head" dot is redundant - and building it used to wipe every dot
        // first, which is why the guard dots vanished the moment this view opened.
        int day = WorldSave.getCurrentSave().getWorld().getCurrentDay();
        int mages = 0, guards = 0;
        for (EnemySprite mage : WorldStage.getInstance().getTerritoryMages()) {
            PointOfInterest target = mage.territoryTarget;
            if (target == null)
                continue;
            // Same fog gate the dots use - a line would otherwise trace a mage the player cannot
            // see, straight to a town they have not found.
            int mageTileX = (int) (mage.getX() / WorldSave.getCurrentSave().getWorld().getTileSize());
            int mageTileY = (int) (mage.getY() / WorldSave.getCurrentSave().getWorld().getTileSize());
            if (!WorldSave.getCurrentSave().getWorld().isCurrentlyVisible(mageTileX, mageTileY))
                continue;
            attackEnds.add(new float[]{mage.getX(), mage.getY(), target.getPosition().x, target.getPosition().y});
            attackColors.add(GameHUD.getMageMarkerColor(mage.territoryColor));
            mages++;
        }
        // Round 162 (user: "draw lines on the mini-map line view for the guards on their way to a
        // town they are defending and back to the capitol if they are going back"). The guard dot's
        // own LIME for the outbound leg and a dimmed green for the way home, so a guard walking
        // away from a town never reads as one racing to defend it. No fog gate, as with the dots:
        // these are the player's own guards.
        for (forge.adventure.data.RoamingGuardData guard : forge.adventure.util.RoamingGuards.roster()) {
            PointOfInterest destination = forge.adventure.util.RoamingGuardRuntime.destination(guard, day);
            if (destination == null)
                continue;
            attackEnds.add(new float[]{guard.x, guard.y, destination.getPosition().x, destination.getPosition().y});
            attackColors.add(guard.returningHome ? GUARD_HOMEWARD : Color.LIME);
            guards++;
        }
        layoutAttacks();
        System.out.println("[TFR-MapView] attack overlay: " + mages + " mage(s) heading for a town, " + guards
                + " guard(s) on the road, " + attackLines.size() + " line(s) drawn at zoom " + img.getScaleX());
    }

    private void clearDetails() {
        for (TypingLabel detail : details)
            table.removeActor(detail);
        details.clear();
        detailAnchors.clear();
    }

    /**
     * Round 162 (user screenshot: "Under Attack" and Guards text "floating / not on a specific
     * town"). Lays every overlay label out again from its WORLD anchor at the current zoom, in
     * build order, with the same bounded shift-down rule placeDetailLabel() applies when the view
     * is built. The old zoom step transformed each label and then ran resolveLabelOverlaps(), which
     * can only ever move a label DOWN and never back - so every zoom-out pushed the crowded labels
     * further from their towns and no zoom-in brought them home. A pure function of anchor and zoom
     * cannot drift. A label that still finds no room at this zoom is hidden here, not parked
     * somewhere untrue, and comes back when the map is zoomed in.
     */
    private void layoutDetails() {
        List<Rectangle> placed = Lists.newArrayList();
        for (Actor existing : table.getChildren()) {
            if (existing instanceof TypingLabel && existing.isVisible() && !details.contains(existing))
                placed.add(new Rectangle(existing.getX(), existing.getY(), existing.getWidth(), existing.getHeight()));
        }
        for (int i = 0; i < details.size() && i < detailAnchors.size(); i++) {
            TypingLabel label = details.get(i);
            float[] anchor = detailAnchors.get(i);
            float x = img.getScaleX() * getMapX(anchor[0]) + img.getX() - label.getWidth() / 2;
            float y = img.getScaleY() * getMapY(anchor[1]) + img.getY() - label.getHeight() / 2;
            Rectangle rect = new Rectangle(x, y, label.getWidth(), label.getHeight());
            boolean moved = true;
            int shifts = 0;
            while (moved && shifts < MAX_LABEL_SHIFTS) {
                moved = false;
                for (Rectangle other : placed) {
                    if (rect.overlaps(other)) {
                        rect.y -= label.getHeight();
                        moved = true;
                        shifts++;
                        break;
                    }
                }
            }
            label.setVisible(!moved);
            if (moved)
                continue;
            placed.add(rect);
            label.setPosition(rect.x, rect.y);
        }
    }

    private void clearAttacks() {
        for (Image line : attackLines)
            line.remove();
        attackLines.clear();
        attackEnds.clear();
        attackColors.clear();
    }

    /** (Re)draws the attack lines at the minimap's CURRENT scale and offset - the transform
     *  placeDetailLabel() uses - so a line drawn while zoomed lands on the map, and zoomIn/zoomOut
     *  call this instead of nudging the old lines along. */
    private void layoutAttacks() {
        for (Image line : attackLines)
            line.remove();
        attackLines.clear();
        for (int i = 0; i < attackEnds.size(); i++) {
            float[] p = attackEnds.get(i);
            float x1 = img.getScaleX() * getMapX(p[0]) + img.getX(), y1 = img.getScaleY() * getMapY(p[1]) + img.getY();
            float x2 = img.getScaleX() * getMapX(p[2]) + img.getX(), y2 = img.getScaleY() * getMapY(p[3]) + img.getY();
            float dx = x2 - x1, dy = y2 - y1;
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length < 1f)
                continue;
            Image line = new Image(Forge.getAssets().getTexture(Config.instance().getFile("ui/minimap_player.png")));
            line.setColor(attackColors.get(i));
            line.setSize(length, 1f);
            line.setOrigin(0f, 0.5f);
            line.setRotation((float) Math.toDegrees(Math.atan2(dy, dx)));
            line.setPosition(x1, y1);
            table.addActor(line);
            attackLines.add(line);
        }
    }

    public void zoomOut() {
        if (img.getScaleX()*0.9f > minZoom) {
            img.setPosition((scroll.getScrollX() + scroll.getWidth()/2) * 0.1f + 0.9f * img.getX(), (scroll.getMaxY() - scroll.getScrollY() + scroll.getHeight()/2) * 0.1f + 0.9f * img.getY());
            img.setScale(img.getScaleX() * 0.9f);
            miniMapPlayer.setPosition((scroll.getScrollX() + scroll.getWidth()/2) * 0.1f + 0.9f * miniMapPlayer.getX(), (scroll.getMaxY() - scroll.getScrollY() + scroll.getHeight()/2) * 0.1f + 0.9f * miniMapPlayer.getY());
            miniMapPlayer.setScale(miniMapPlayer.getScaleX() * 0.9f);
            for (Actor actor : table.getChildren()) {
                // Mage markers ride the same transform as the player marker/labels, or they'd
                // visibly detach from the map the first time the view is zoomed.
                if (actor instanceof TypingLabel || mageMarkers.contains(actor)) {
                    actor.setPosition((scroll.getScrollX() + scroll.getWidth()/2) * 0.1f + 0.9f * actor.getX(), (scroll.getMaxY() - scroll.getScrollY() + scroll.getHeight()/2) * 0.1f + 0.9f * actor.getY());
                }
            }
            resolveLabelOverlaps();
            layoutDetails();
            layoutAttacks();
        }
    }
    public void zoomIn() {
        if (img.getScaleX()*1.1f < maxZoom) {
            img.setPosition(-(scroll.getScrollX() + scroll.getWidth()/2) * 0.1f + 1.1f * img.getX(), -(scroll.getMaxY() - scroll.getScrollY() + scroll.getHeight()/2) * 0.1f + 1.1f * img.getY());
            img.setScale(img.getScaleX() * 1.1f);
            miniMapPlayer.setPosition(-(scroll.getScrollX() + scroll.getWidth()/2) * 0.1f + 1.1f * miniMapPlayer.getX(), -(scroll.getMaxY() - scroll.getScrollY() + scroll.getHeight()/2) * 0.1f + 1.1f * miniMapPlayer.getY());
            miniMapPlayer.setScale(miniMapPlayer.getScaleX() * 1.1f);
            for (Actor actor : table.getChildren()) {
                // Same reasoning as zoomOut()'s marker handling above.
                if (actor instanceof TypingLabel || mageMarkers.contains(actor)) {
                    actor.setPosition(-(scroll.getScrollX() + scroll.getWidth()/2) * 0.1f + 1.1f * actor.getX(), -(scroll.getMaxY() - scroll.getScrollY() + scroll.getHeight()/2) * 0.1f + 1.1f * actor.getY());
                }
            }
            resolveLabelOverlaps();
            layoutDetails();
            layoutAttacks();
        }
    }

    /** Re-establishes the label collision-avoidance placeDetailLabel() enforces at BUILD time,
     *  after a zoom step's uniform scale+translate transform has moved every label (2026-08-16
     *  user report: garbled overlapping map labels, reproducible by zooming out). Root cause:
     *  the zoom transform shrinks the pixel GAP between two labels' anchors by the same factor
     *  it moves them, but each label's own on-screen SIZE never changes - so a pair that
     *  placeDetailLabel() positioned edge-to-edge (its minimum possible clearance, zero margin)
     *  collapses into an overlap the moment the view zooms out. Re-runs the identical shift-down-
     *  until-clear algorithm placeDetailLabel() uses, but against the labels' ALREADY-transformed
     *  positions instead of a fresh candidate - so it fixes up whatever the zoom step just broke
     *  rather than rebuilding from world coordinates (which would restart every label's typing
     *  animation and is unnecessary just to re-separate them). Operates on every TypingLabel
     *  currently on the table, so it covers all 3 overlay modes that can show labels
     *  (details/events/reputation), not just the one placeDetailLabel() originally targeted.
     *  Round 162: the overlay labels themselves are now re-laid from their world anchors by
     *  layoutDetails() - this pass can only ever push a label DOWN, and it ran on every zoom step,
     *  so the labels walked away from their towns - and this covers only the quest/bookmark labels. */
    private void resolveLabelOverlaps() {
        List<Rectangle> placedLabelRects = Lists.newArrayList();
        for (Actor actor : table.getChildren()) {
            if (!(actor instanceof TypingLabel) || details.contains(actor))
                continue; // round 162: overlay labels are re-laid from their anchors by layoutDetails()
            Rectangle rect = new Rectangle(actor.getX(), actor.getY(), actor.getWidth(), actor.getHeight());
            boolean moved = true;
            while (moved) {
                moved = false;
                for (Rectangle placed : placedLabelRects) {
                    if (rect.overlaps(placed)) {
                        rect.y -= actor.getHeight();
                        moved = true;
                        break;
                    }
                }
            }
            actor.setPosition(rect.x, rect.y);
            placedLabelRects.add(rect);
        }
    }

    // Extracted so the fog-of-war debug toggle (GameHUD) can force an immediate refresh here too,
    // instead of only updating on the next time this scene is entered.
    // Engine merge 09.11: upstream's enter() moved to Assets.getNewMiniMapTexture(), which keeps ONE texture and
    // skips the upload when the Pixmap is the same object (hashCode). This plane repaints fog of war and territory
    // into that same Pixmap in place, so the cached texture would freeze the world map at its first image - the map
    // keeps its own texture and rebuilds it on every refresh instead.
    public void refreshMap() {
        if (miniMapTexture != null)
            miniMapTexture.dispose();
        miniMapTexture = new Texture(WorldSave.getCurrentSave().getWorld().getBiomeImage());
        img.setSize(WorldSave.getCurrentSave().getWorld().getBiomeImage().getWidth(), WorldSave.getCurrentSave().getWorld().getBiomeImage().getHeight());
        img.getParent().setSize(WorldSave.getCurrentSave().getWorld().getBiomeImage().getWidth(), WorldSave.getCurrentSave().getWorld().getBiomeImage().getHeight());
        img.setDrawable(new TextureRegionDrawable(miniMapTexture));
    }

    @Override
    public void enter() {
        refreshMap();
        miniMapPlayer.setDrawable(new TextureRegionDrawable(Current.player().avatar()));
        miniMapPlayer.setSize(Current.player().avatar().getRegionWidth(), Current.player().avatar().getRegionHeight());
        avatarX = getMapX(WorldStage.getInstance().getPlayerSprite().getX()) - miniMapPlayer.getWidth() / 2;
        avatarY = getMapY(WorldStage.getInstance().getPlayerSprite().getY()) - miniMapPlayer.getHeight() / 2;
        miniMapPlayer.setPosition(avatarX, avatarY);
        miniMapPlayer.layout();
        scroll.scrollTo(avatarX, avatarY, miniMapPlayer.getWidth(), miniMapPlayer.getHeight(), true, true);
        for (AdventureQuestData adq : Current.player().getQuests()) {
            PointOfInterest poi = adq.getTargetPOI();
            if (poi != null) {
                if (positions.contains(poi.getPosition()))
                    continue; //don't map duplicate position to prevent stacking
                TypingLabel label = Controls.newTypingLabel("[+GPS][%?BLACKEN] " + adq.name);
                labels.add(label);
                table.addActor(label);
                label.setPosition(getMapX(poi.getPosition().x) - label.getWidth() / 2, getMapY(poi.getPosition().y) - label.getHeight() / 2);
                label.skipToTheEnd();
                positions.add(poi.getPosition());
            }
        }
        for (PointOfInterest poi : bookmark) {
            TypingLabel label = Controls.newTypingLabel("[%75][+Star] ");
            table.addActor(label);
            label.setPosition(getMapX(poi.getPosition().x) - label.getWidth() / 2, getMapY(poi.getPosition().y) - label.getHeight() / 2);
            label.skipToTheEnd();
        }

        // Clear-then-rebuild rather than diffing: re-entering without a done() in between (or
        // after a mage arrived/died) must never stack or strand stale dots.
        for (Image marker : mageMarkers)
            marker.remove();
        mageMarkers.clear();
        for (EnemySprite mage : WorldStage.getInstance().getTerritoryMages()) {
            // Same fog-of-war gate as the corner minimap's dots (GameHUD.updateMageMinimapMarkers):
            // only mages inside REVEALED territory - player vision or a player-owned town's own
            // area - get a dot; isCurrentlyVisible() returns true for everything when fog is off.
            int mageTileX = (int) (mage.getX() / WorldSave.getCurrentSave().getWorld().getTileSize());
            int mageTileY = (int) (mage.getY() / WorldSave.getCurrentSave().getWorld().getTileSize());
            if (!WorldSave.getCurrentSave().getWorld().isCurrentlyVisible(mageTileX, mageTileY))
                continue;
            Image marker = new Image(Forge.getAssets().getTexture(Config.instance().getFile("ui/minimap_player.png")));
            marker.setColor(GameHUD.getMageMarkerColor(mage.territoryColor));
            table.addActor(marker);
            marker.setPosition(getMapX(mage.getX()) - marker.getWidth() / 2, getMapY(mage.getY()) - marker.getHeight() / 2);
            mageMarkers.add(marker);
        }

        // Roaming guard dots (round 152, user request: "can we add a dot on the mini-map for our
        // Roaming Guards"). Deliberately in the same mageMarkers list as the attacker dots above:
        // that list is what zoomIn/zoomOut re-position and what enter() clears, so a separate one
        // would detach from the map on the first zoom. Green rather than a territory color, so a
        // friendly dot never reads as another incoming mage. No fog gate - these are the player's
        // own guards and their position is not a secret from them.
        for (forge.adventure.data.RoamingGuardData guard : forge.adventure.util.RoamingGuards.roster()) {
            if (!guard.deployed)
                continue;
            Image marker = new Image(Forge.getAssets().getTexture(Config.instance().getFile("ui/minimap_player.png")));
            marker.setColor(com.badlogic.gdx.graphics.Color.LIME);
            table.addActor(marker);
            marker.setPosition(getMapX(guard.x) - marker.getWidth() / 2, getMapY(guard.y) - marker.getHeight() / 2);
            mageMarkers.add(marker);
        }

        setOverlayButtonStates(0);
        TextraButton zoomInButton = ui.findActor("zoomIn");
        if (zoomInButton != null) {
            zoomInButton.setVisible(true);
            zoomInButton.setDisabled(false);
        }
        TextraButton zoomOutButton = ui.findActor("zoomOut");
        if (zoomOutButton != null) {
            zoomOutButton.setVisible(true);
            zoomOutButton.setDisabled(false);
        }
        TextraButton questButton = ui.findActor("quest");
        if (questButton != null) {
            questButton.setDisabled(labels.isEmpty());
            questButton.setVisible(!labels.isEmpty());
        }
        // Restore last overlay mode
        if (lastOverlayMode == 1) details();
        else if (lastOverlayMode == 2) events();
        else if (lastOverlayMode == 3) reputation();

        super.enter();
    }
    float getMapX(float posX) {
        return (posX / (float) WorldSave.getCurrentSave().getWorld().getTileSize() / (float) WorldSave.getCurrentSave().getWorld().getWidthInTiles()) * img.getWidth();
    }
    float getMapY(float posY) {
        return (posY / (float) WorldSave.getCurrentSave().getWorld().getTileSize() / (float) WorldSave.getCurrentSave().getWorld().getHeightInTiles()) * img.getHeight();
    }

    public void clearBookMarks() {
        if (bookmark != null)
            bookmark.clear();
    }
}
