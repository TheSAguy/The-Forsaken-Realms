package forge.adventure.agent;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.character.DialogActor;
import forge.adventure.character.EnemySprite;
import forge.adventure.character.EntryActor;
import forge.adventure.character.MapActor;
import forge.adventure.character.PortalActor;
import forge.adventure.character.QuestActor;
import forge.adventure.character.RewardSprite;
import forge.adventure.character.ShopActor;
import forge.adventure.data.AdventureQuestData;
import forge.adventure.data.HeroListData;
import forge.adventure.data.ItemData;
import forge.adventure.data.RoamingGuardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.scene.AgentSceneAccess;
import forge.adventure.scene.DuelScene;
import forge.adventure.scene.HudScene;
import forge.adventure.scene.RewardScene;
import forge.adventure.scene.Scene;
import forge.adventure.stage.AgentStageAccess;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.GameStage;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.AdventureQuestController;
import forge.adventure.util.ColorReputation;
import forge.adventure.util.Current;
import forge.adventure.util.RewardActor;
import forge.adventure.util.TownRestoration;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Round 161: builds the agent's observation. Everything comes from the live objects on the render
 * thread; fair-play by default (discovered points of interest, visible enemies), the whole world
 * only with cheats. Also owns the id -> actor table that {@code click} resolves against.
 */
final class AgentObserver {
    private final AgentBridge bridge;
    private final Map<String, Actor> actorsById = new LinkedHashMap<>();
    private static final String[] COLORS = {"white", "blue", "black", "red", "green"};

    AgentObserver(AgentBridge bridge) {
        this.bridge = bridge;
    }

    Actor actor(String id) {
        return actorsById.get(id);
    }

    /** All clickable actors from the last snapshot, for click-by-text. */
    Map<String, Actor> lastActors() {
        return actorsById;
    }

    boolean hasGame() {
        // Before a game is loaded the save holds an EMPTY World shell (its data is null), so the
        // object test alone is not enough - the start menu has a "world" that cannot be asked anything.
        try {
            return WorldSave.getCurrentSave() != null && WorldSave.getCurrentSave().getWorld() != null
                    && WorldSave.getCurrentSave().getPlayer() != null && Current.world() != null
                    && Current.world().getData() != null && Current.player() != null
                    && Current.player().getCurrentGameStage() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** The dialog the player is looking at, if any: the game stage's own, else any visible Dialog on the scene's stage or the HUD. */
    Dialog currentDialog(Scene scene) {
        if (scene instanceof HudScene && hasGame()) {
            GameStage stage = Current.player().getCurrentGameStage();
            if (stage != null && stage.getDialog() != null && stage.getDialog().getStage() != null && visible(stage.getDialog()))
                return stage.getDialog();
        }
        Stage st = stageFor(scene);
        Dialog d = st == null ? null : findDialog(st.getRoot());
        if (d == null && scene instanceof HudScene)
            d = findDialog(GameHUD.getInstance().getRoot());
        return d;
    }

    boolean isIdle() {
        if (bridge.walker().isBusy() || transitioning())
            return false;
        // A Forge-toolkit prompt (the win/lose view, an option pane) is waiting for the agent: that
        // is "idle" in the sense that matters - nothing moves until it is answered.
        if (!forgeButtons().isEmpty())
            return true;
        if (Forge.advFreezePlayerControls)
            return false;
        Scene scene = Forge.getCurrentScene();
        if (scene instanceof DuelScene)
            return false;
        if (scene instanceof HudScene && hasGame()) {
            GameStage stage = Current.player().getCurrentGameStage();
            if (stage != null && stage.isPaused())
                return false;
        }
        return true;
    }

    static boolean transitioning() {
        try {
            java.lang.reflect.Field f = Forge.class.getDeclaredField("transitionScreen");
            f.setAccessible(true);
            return f.get(null) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ the snapshot

    Map<String, Object> snapshot(boolean cheat) {
        actorsById.clear();
        Map<String, Object> s = new LinkedHashMap<>();
        Scene scene = Forge.getCurrentScene();
        s.put("ok", true);
        s.put("scene", scene == null ? null : scene.getClass().getSimpleName());
        s.put("transition", transitioning());
        s.put("frozen", Forge.advFreezePlayerControls);
        boolean game = hasGame();
        s.put("inGame", game);
        if (game) {
            AdventurePlayer p = Current.player();
            World world = Current.world();
            boolean inMap = MapStage.getInstance().isInMap();
            GameStage stage = p.getCurrentGameStage();
            s.put("paused", stage != null && stage.isPaused());
            s.put("player", player(p, world, inMap));
            if (scene instanceof HudScene) {
                if (inMap)
                    s.put("map", map(p, cheat));
                else
                    s.put("world", world(p, world, cheat));
            }
            s.put("quests", quests(p));
        }
        s.put("dialog", dialog(scene));
        s.put("ui", ui(scene));
        List<Object> forge = forgeUi();
        if (!forge.isEmpty())
            s.put("forgeUi", forge);
        if (scene instanceof RewardScene)
            s.put("shop", shop((RewardScene) scene));
        s.put("agent", agent());
        return s;
    }

    private Map<String, Object> agent() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("busy", bridge.walker().isBusy());
        a.put("action", bridge.walker().describe());
        a.put("autoBattle", bridge.autoBattle);
        a.put("cheats", bridge.cheatsAllowed());
        a.put("frame", bridge.frame);
        a.put("notifications", bridge.drainNotifications());
        a.put("log", bridge.recentLog());
        return a;
    }

    private Map<String, Object> player(AdventurePlayer p, World world, boolean inMap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("race", HeroListData.getRawRaceName(p.getHeroRace()));
        m.put("difficulty", p.getDifficulty() == null ? null : p.getDifficulty().name);
        m.put("day", world.getCurrentDay());
        m.put("dayProgress", world.getDayProgress());
        m.put("gold", p.getGold());
        m.put("shards", p.getShards());
        m.put("wood", p.getWood());
        m.put("stone", p.getStone());
        m.put("life", p.getLife());
        m.put("maxLife", p.getMaxLife());
        m.put("colorIdentity", p.getColorIdentityLong());
        GameStage stage = p.getCurrentGameStage();
        if (stage != null && stage.getPlayerSprite() != null) {
            Vector2 c = WalkController.playerCenter(stage);
            m.put("pos", c);
            if (!inMap) {
                int ts = world.getTileSize();
                m.put("tile", new int[]{(int) (c.x / ts), (int) (c.y / ts)});
            }
        }
        PointOfInterest poi = AdventureQuestController.instance().mostRecentPOI;
        m.put("location", inMap ? (poi == null ? "a map" : poi.getDisplayName()) : "world map");
        Deck sel = p.getSelectedDeck();
        m.put("selectedDeck", sel == null ? null : deck(sel, p.getSelectedDeckIndex()));
        List<Object> decks = new ArrayList<>();
        for (int i = 0; i < p.getDeckCount(); i++) {
            Deck d = p.getDeck(i);
            if (d != null && d.getMain().countAll() > 0)
                decks.add(deck(d, i));
        }
        m.put("decks", decks);
        m.put("collection", p.getCards().countAll());
        List<Object> items = new ArrayList<>();
        for (ItemData it : p.getItems()) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("name", it.name);
            im.put("slot", it.equipmentSlot);
            im.put("equipped", it.isEquipped && it.longID != null && p.getEquippedItems().contains(it.longID));
            if (it.commandOnUse != null && !it.commandOnUse.isEmpty()) {
                im.put("usable", it.usableOnWorldMap ? (it.usableInPoi ? "anywhere" : "world map") : (it.usableInPoi ? "in maps" : "no"));
                im.put("shardsToUse", it.shardsNeeded);
            }
            if (it.questItem) im.put("questItem", true);
            im.put("sell", (int) (it.cost * 0.25f));
            items.add(im);
        }
        m.put("items", items);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("wins", p.getStatistic().totalWins());
        stats.put("losses", p.getStatistic().totalLoss());
        m.put("stats", stats);
        Map<String, Object> rep = new LinkedHashMap<>();
        for (String c : COLORS) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("points", p.getColorReputationHalfPoints(c) / 2f);
            r.put("status", String.valueOf(ColorReputation.getStatus(c)));
            rep.put(c, r);
        }
        m.put("reputation", rep);
        List<Object> guards = new ArrayList<>();
        for (RoamingGuardData g : p.getRoamingGuards()) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("tier", g.tier);
            gm.put("deck", g.deckName);
            gm.put("deployed", g.deployed);
            gm.put("mission", g.missionPoiId);
            gm.put("downUntilDay", g.downUntilDay);
            guards.add(gm);
        }
        if (!guards.isEmpty()) m.put("roamingGuards", guards);
        return m;
    }

    private static Map<String, Object> deck(Deck d, int index) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("name", d.getName());
        m.put("cards", d.getMain().countAll());
        return m;
    }

    private List<Object> quests(AdventurePlayer p) {
        List<Object> out = new ArrayList<>();
        for (AdventureQuestData q : p.getQuests()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", q.getID());
            m.put("name", q.name);
            m.put("description", Jsons.plain(q.description));
            m.put("tracked", q.isTracked);
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ world map

    private Map<String, Object> world(AdventurePlayer p, World world, boolean cheat) {
        Map<String, Object> m = new LinkedHashMap<>();
        WorldStage ws = WorldStage.getInstance();
        int ts = world.getTileSize();
        Vector2 me = WalkController.playerCenter(ws);
        int mx = (int) (me.x / ts), my = (int) (me.y / ts);
        m.put("size", new int[]{world.getWidthInTiles(), world.getHeightInTiles()});
        m.put("visionRadius", world.getVisionRadius());
        m.put("fastTime", ws.isFastTimeEnabled());
        m.put("waitingForTime", ws.isWaitingForTime());
        List<Map<String, Object>> pois = new ArrayList<>();
        int hidden = 0;
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (!poi.getActive())
                continue;
            Vector2 c = poi.getCenter();
            int tx = (int) (c.x / ts), ty = (int) (c.y / ts);
            if (!cheat && !world.isExploredWorld(tx, ty)) {
                hidden++;
                continue;
            }
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", poi.getDisplayName());
            pm.put("type", poi.getData().type);
            String color = ColorReputation.colorOfTown(poi.getData());
            if (color != null) {
                pm.put("color", color);
                pm.put("playerHeld", TownRestoration.isTownRestored(WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID())));
            }
            pm.put("tile", new int[]{tx, ty});
            double dist = Math.hypot(tx - mx, ty - my);
            pm.put("distance", Math.round(dist));
            pm.put("bearing", bearing(tx - mx, ty - my));
            pois.add(pm);
        }
        pois.sort((a, b) -> Long.compare((Long) a.get("distance"), (Long) b.get("distance")));
        m.put("poiCount", pois.size());
        m.put("poiUndiscovered", hidden);
        m.put("pois", pois.size() > 80 ? pois.subList(0, 80) : pois);
        List<Object> enemies = new ArrayList<>();
        for (EnemySprite e : AgentStageAccess.worldEnemies()) {
            if (e.hidden || e.inactive)
                continue;
            int ex = (int) (e.getX() / ts), ey = (int) (e.getY() / ts);
            if (!cheat && !world.isCurrentlyVisible(ex, ey))
                continue;
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("name", e.getTieredDisplayName());
            em.put("rank", e.getData().tier);
            em.put("life", e.getData().life);
            em.put("tile", new int[]{ex, ey});
            em.put("distance", Math.round(Math.hypot(ex - mx, ey - my)));
            em.put("bearing", bearing(ex - mx, ey - my));
            if (e.territoryTarget != null)
                em.put("attacking", e.territoryTarget.getDisplayName());
            enemies.add(em);
        }
        m.put("enemies", enemies);
        return m;
    }

    private static String bearing(int dx, int dy) {
        if (dx == 0 && dy == 0) return "here";
        double a = Math.toDegrees(Math.atan2(dy, dx));
        String[] names = {"E", "NE", "N", "NW", "W", "SW", "S", "SE"};
        int i = (int) Math.round(((a + 360) % 360) / 45.0) % 8;
        return names[i];
    }

    // ------------------------------------------------------------------ inside a map

    private Map<String, Object> map(AdventurePlayer p, boolean cheat) {
        Map<String, Object> m = new LinkedHashMap<>();
        MapStage ms = MapStage.getInstance();
        PointOfInterest poi = AdventureQuestController.instance().mostRecentPOI;
        m.put("name", poi == null ? null : poi.getDisplayName());
        m.put("type", poi == null ? null : poi.getData().type);
        Vector2 me = WalkController.playerCenter(ms);
        List<Object> actors = new ArrayList<>();
        for (MapActor a : AgentStageAccess.mapActors()) {
            String kind, label = null;
            if (a instanceof EnemySprite) {
                EnemySprite e = (EnemySprite) a;
                if (e.hidden || e.inactive) continue;
                kind = "enemy";
                label = e.getTieredDisplayName() + " (" + e.getData().tier + ", " + e.getData().life + " life)";
            } else if (a instanceof ShopActor) {
                kind = "shop";
                ShopActor sh = (ShopActor) a;
                label = sh.getShopData() == null ? "shop" : sh.getShopData().name;
                if (sh.isDestroyed()) label += " (destroyed)";
            } else if (a instanceof RewardSprite) {
                kind = "reward";
            } else if (a instanceof QuestActor) {
                kind = "questGiver";
            } else if (a instanceof DialogActor) {
                kind = "npc";
            } else if (a instanceof PortalActor) {
                kind = "portal";
            } else if (a instanceof EntryActor) {
                kind = "exit";
            } else {
                continue;
            }
            if (!a.isVisible()) continue;
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("id", a.getObjectId());
            am.put("kind", kind);
            if (label != null) am.put("label", label);
            Vector2 c = a.getCenter();
            am.put("pos", c);
            am.put("distance", Math.round(me.dst(c)));
            actors.add(am);
        }
        m.put("actors", actors);
        return m;
    }

    // ------------------------------------------------------------------ dialogs and generic UI

    private Map<String, Object> dialog(Scene scene) {
        Dialog dialog = currentDialog(scene);
        if (dialog == null)
            return null;
        Map<String, Object> m = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        collectText(dialog.getContentTable(), text);
        if (dialog.getTitleLabel() != null && dialog.getTitleLabel().getText().length() > 0)
            m.put("title", Jsons.plain(dialog.getTitleLabel().getText().toString()));
        m.put("text", Jsons.plain(text.toString()));
        List<Object> options = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        collectButtons(dialog, buttons);
        int i = 0;
        for (Button b : buttons) {
            String id = "d" + (++i);
            actorsById.put(id, b);
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("id", id);
            om.put("text", buttonText(b));
            om.put("enabled", !b.isDisabled());
            options.add(om);
        }
        m.put("options", options);
        // MapDialog hides its option buttons until the text has finished typing; a click on the
        // dialog skips to the end. Tell the agent so it sends `advance` instead of guessing.
        int all = countButtons(dialog);
        if (all > buttons.size()) {
            m.put("typing", true);
            m.put("hint", "text still typing - send {\"cmd\":\"advance\"} and read the state again for the options");
        }
        return m;
    }

    private static int countButtons(Group root) {
        int n = 0;
        for (Actor a : root.getChildren()) {
            if (a instanceof Button) n++;
            else if (a instanceof Group) n += countButtons((Group) a);
        }
        return n;
    }

    private List<Object> ui(Scene scene) {
        List<Object> out = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        Stage st = stageFor(scene);
        if (st != null)
            collectButtons(st.getRoot(), buttons);
        if (scene instanceof HudScene)
            collectButtons(GameHUD.getInstance().getRoot(), buttons);
        int i = 0;
        for (Button b : buttons) {
            if (actorsById.containsValue(b))
                continue; // already listed as a dialog option
            if (b.isDisabled())
                continue;
            String id = "u" + (++i);
            actorsById.put(id, b);
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("id", id);
            String text = buttonText(b);
            if (!text.isEmpty()) bm.put("text", text);
            if (b.getName() != null) bm.put("name", b.getName());
            if (text.isEmpty() && b.getName() == null) bm.put("class", b.getClass().getSimpleName());
            out.add(bm);
            if (out.size() >= 80)
                break;
        }
        return out;
    }

    private Map<String, Object> shop(RewardScene scene) {
        Map<String, Object> m = new LinkedHashMap<>();
        // The buy buttons know the price (a private field on RewardScene.BuyButton); read it
        // reflectively rather than parsing the label - the label is markup plus the number.
        Map<Integer, int[]> buy = new LinkedHashMap<>(); // reward index -> {price, disabled}
        Stage st = stageFor(scene);
        if (st != null) {
            List<Button> buttons = new ArrayList<>();
            collectButtons(st.getRoot(), buttons);
            for (Button b : buttons) {
                if (!b.getClass().getSimpleName().equals("BuyButton"))
                    continue;
                try {
                    java.lang.reflect.Field fi = b.getClass().getDeclaredField("index");
                    java.lang.reflect.Field fp = b.getClass().getDeclaredField("price");
                    fi.setAccessible(true);
                    fp.setAccessible(true);
                    buy.put(fi.getInt(b), new int[]{fp.getInt(b), b.isDisabled() ? 1 : 0});
                } catch (Exception ignored) {
                }
            }
        }
        List<Object> items = new ArrayList<>();
        int i = 0;
        int gold = hasGame() ? Current.player().getGold() : 0;
        String kind = AgentSceneAccess.rewardSceneType(scene);
        boolean isShop = "Shop".equalsIgnoreCase(kind);
        m.put("kind", kind);
        for (RewardActor ra : scene.getGeneratedRewards()) {
            Map<String, Object> im = new LinkedHashMap<>();
            int index = i++;
            im.put("index", index);
            forge.adventure.util.Reward r = ra.getReward();
            im.put("type", String.valueOf(r.getType()));
            if (r.getCard() != null) im.put("name", r.getCard().getName());
            else if (r.getItem() != null) im.put("name", r.getItem().name);
            else if (r.getDeck() != null) im.put("name", r.getDeck().getName());
            if (r.getCount() > 1) im.put("count", r.getCount());
            int[] p = buy.get(index);
            if (p != null) {
                im.put("price", p[0]);
                im.put("canBuy", p[1] == 0 && p[0] <= gold);
            } else if (isShop) {
                im.put("bought", true); // no buy button left for it
            }
            if (r.isAutoSell()) im.put("autoSell", true);
            items.add(im);
        }
        m.put("items", items);
        m.put("gold", gold);
        return m;
    }

    // ------------------------------------------------------------------ Forge's own toolkit (match screen, win/lose view, option panes)

    private final Map<String, forge.toolbox.FButton> forgeById = new LinkedHashMap<>();

    forge.toolbox.FButton forgeButton(String id) {
        return forgeById.get(id);
    }

    Map<String, forge.toolbox.FButton> lastForgeButtons() {
        return forgeById;
    }

    /** Every visible, enabled FButton on the visible overlays (top-down): a prompt waiting for the agent. */
    static List<forge.toolbox.FButton> forgeButtons() {
        List<forge.toolbox.FButton> out = new ArrayList<>();
        try {
            for (forge.toolbox.FOverlay overlay : forge.toolbox.FOverlay.getOverlaysTopDown()) {
                if (overlay != null && overlay.isVisible())
                    collectForgeButtons(overlay, out);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** The current FScreen's own buttons (a match's Pause and speed toggle): controls, not a prompt. */
    static List<forge.toolbox.FButton> forgeScreenButtons() {
        List<forge.toolbox.FButton> out = new ArrayList<>();
        try {
            forge.screens.FScreen screen = Forge.getCurrentScreen();
            if (screen != null)
                collectForgeButtons(screen, out);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void collectForgeButtons(forge.toolbox.FContainer container, List<forge.toolbox.FButton> out) {
        for (forge.toolbox.FDisplayObject child : container.getChildren()) {
            if (!child.isVisible())
                continue;
            if (child instanceof forge.toolbox.FButton) {
                if (child.isEnabled())
                    out.add((forge.toolbox.FButton) child);
            } else if (child instanceof forge.toolbox.FContainer) {
                collectForgeButtons((forge.toolbox.FContainer) child, out);
            }
        }
    }

    private List<Object> forgeUi() {
        forgeById.clear();
        List<Object> out = new ArrayList<>();
        int i = 0;
        for (forge.toolbox.FButton b : forgeButtons()) {
            String id = "f" + (++i);
            forgeById.put(id, b);
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("id", id);
            bm.put("text", Jsons.plain(String.valueOf(b.getText())));
            bm.put("prompt", true);
            out.add(bm);
            if (out.size() >= 40)
                break;
        }
        for (forge.toolbox.FButton b : forgeScreenButtons()) {
            if (forgeById.containsValue(b))
                continue;
            String id = "f" + (++i);
            forgeById.put(id, b);
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("id", id);
            bm.put("text", Jsons.plain(String.valueOf(b.getText())));
            bm.put("prompt", false);
            out.add(bm);
            if (out.size() >= 60)
                break;
        }
        return out;
    }

    static Stage stageFor(Scene scene) {
        return scene == null ? null : AgentSceneAccess.stageOf(scene);
    }

    static boolean visible(Actor a) {
        for (Actor x = a; x != null; x = x.getParent())
            if (!x.isVisible())
                return false;
        return a.getStage() != null;
    }

    private static Dialog findDialog(Group root) {
        for (Actor a : root.getChildren()) {
            if (a instanceof Dialog && visible(a))
                return (Dialog) a;
            if (a instanceof Group) {
                Dialog d = findDialog((Group) a);
                if (d != null)
                    return d;
            }
        }
        return null;
    }

    static void collectButtons(Group root, List<Button> out) {
        for (Actor a : root.getChildren()) {
            if (!a.isVisible())
                continue;
            if (a instanceof Button) {
                Button b = (Button) a;
                if (b.getTouchable() != Touchable.disabled)
                    out.add(b);
                continue; // buttons do not nest usefully
            }
            if (a instanceof Group)
                collectButtons((Group) a, out);
        }
    }

    private static void collectText(Group root, StringBuilder sb) {
        for (Actor a : root.getChildren()) {
            if (!a.isVisible())
                continue;
            if (a instanceof TypingLabel)
                sb.append(((TypingLabel) a).getOriginalText()).append(' ');
            else if (a instanceof TextraLabel)
                sb.append(((TextraLabel) a).storedText).append(' ');
            else if (a instanceof Label)
                sb.append(((Label) a).getText()).append(' ');
            else if (a instanceof Group && !(a instanceof Button))
                collectText((Group) a, sb);
        }
    }

    static String buttonText(Button b) {
        if (b instanceof TextraButton)
            return Jsons.plain(((TextraButton) b).getText());
        if (b instanceof TextButton)
            return Jsons.plain(((TextButton) b).getText().toString());
        StringBuilder sb = new StringBuilder();
        collectText(b, sb);
        return Jsons.plain(sb.toString());
    }
}
