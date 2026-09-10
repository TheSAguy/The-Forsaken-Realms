package forge.adventure.agent;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.utils.JsonValue;
import forge.Forge;
import forge.adventure.character.MapActor;
import forge.adventure.data.ItemData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.scene.AgentSceneAccess;
import forge.adventure.scene.GameScene;
import forge.adventure.scene.HudScene;
import forge.adventure.scene.RewardScene;
import forge.adventure.scene.Scene;
import forge.adventure.scene.UIScene;
import forge.adventure.stage.AgentStageAccess;
import forge.adventure.stage.ConsoleCommandInterpreter;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.AdventureQuestController;
import forge.adventure.util.Current;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.screens.TransitionScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Round 161: the agent's actions. Runs on the render thread. Long actions (a walk, a wait) return a
 * future that completes when they end; everything else completes immediately.
 */
final class AgentActions {
    private final AgentBridge bridge;
    private final AgentObserver observer;
    private final WalkController walker;

    AgentActions(AgentBridge bridge, AgentObserver observer, WalkController walker) {
        this.bridge = bridge;
        this.observer = observer;
        this.walker = walker;
    }

    CompletableFuture<Map<String, Object>> execute(String cmd, JsonValue a) {
        try {
            switch (cmd) {
                case "goto": return gotoCmd(a);
                case "explore": return explore(a);
                case "stop": walker.cancel("stop requested"); return now(true, "stopped");
                case "wait": return walker.waitDays(a.getInt("days", 1));
                case "fasttime": return fastTime(a.getBoolean("on", true));
                case "leave": return leave();
                case "click": return click(a);
                case "advance": return advance();
                case "back": return back();
                case "key": return key(a.getString("key", "ESCAPE"));
                case "equip": return equip(a.getString("item", ""), true);
                case "unequip": return equip(a.getString("item", ""), false);
                case "use": return use(a.getString("item", ""));
                case "sell": return sell(a.getString("item", ""));
                case "deck": return deck(a);
                case "buy": return buy(a.getInt("index", 0));
                case "save": return save(a.getInt("slot", 4), a.getString("name", "agent"));
                case "load": return load(a.getInt("slot", 1));
                case "newgame": return now(AgentSceneAccess.startNewGame(), "new game requested from the New Game screen's current settings");
                case "autobattle": bridge.autoBattle = a.getBoolean("on", true); return now(true, "autoBattle=" + bridge.autoBattle);
                case "console": return now(true, ConsoleCommandInterpreter.getInstance().command(a.getString("text", "")));
                case "": return now(false, "no cmd given");
                default: return now(false, "unknown cmd: " + cmd);
            }
        } catch (Exception e) {
            return now(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static CompletableFuture<Map<String, Object>> now(boolean ok, String message) {
        return CompletableFuture.completedFuture(WalkController.result(ok, message));
    }

    private boolean needGame() {
        return observer.hasGame();
    }

    // ------------------------------------------------------------------ movement

    private CompletableFuture<Map<String, Object>> gotoCmd(JsonValue a) {
        if (!needGame()) return now(false, "no game loaded");
        if (!(Forge.getCurrentScene() instanceof HudScene)) return now(false, "not on a map scene (" + Forge.getCurrentScene().getClass().getSimpleName() + ")");
        World world = Current.world();
        int ts = world.getTileSize();
        if (a.has("poi")) {
            String name = a.getString("poi");
            PointOfInterest poi = findPoi(name);
            if (poi == null) return now(false, "no discovered point of interest named '" + name + "'");
            if (MapStage.getInstance().isInMap()) return now(false, "inside a map - `leave` first");
            return walker.walkTo(WalkController.poiCenter(poi), poi.getDisplayName());
        }
        if (a.has("actor")) {
            if (!MapStage.getInstance().isInMap()) return now(false, "actors exist only inside a map");
            int id = a.getInt("actor");
            for (MapActor m : AgentStageAccess.mapActors())
                if (m.getObjectId() == id)
                    return walker.walkTo(m.getCenter(), "actor " + id);
            return now(false, "no actor with id " + id);
        }
        if (a.has("tile")) {
            JsonValue t = a.get("tile");
            float x = (t.getInt(0) + 0.5f) * ts, y = (t.getInt(1) + 0.5f) * ts;
            return walker.walkTo(new Vector2(x, y), "tile " + t.getInt(0) + "," + t.getInt(1));
        }
        if (a.has("x") && a.has("y"))
            return walker.walkTo(new Vector2(a.getFloat("x"), a.getFloat("y")), "point " + a.getFloat("x") + "," + a.getFloat("y"));
        return now(false, "goto needs poi, actor, tile or x/y");
    }

    private CompletableFuture<Map<String, Object>> explore(JsonValue a) {
        if (!needGame() || MapStage.getInstance().isInMap()) return now(false, "explore works on the world map");
        String dir = a.getString("dir", "N").toUpperCase();
        int tiles = a.getInt("tiles", 8);
        int dx = dir.contains("E") ? 1 : dir.contains("W") ? -1 : 0;
        int dy = dir.contains("N") ? 1 : dir.contains("S") ? -1 : 0;
        World world = Current.world();
        int ts = world.getTileSize();
        Vector2 me = WalkController.playerCenter(WorldStage.getInstance());
        int tx = (int) (me.x / ts) + dx * tiles, ty = (int) (me.y / ts) + dy * tiles;
        tx = Math.max(0, Math.min(world.getWidthInTiles() - 1, tx));
        ty = Math.max(0, Math.min(world.getHeightInTiles() - 1, ty));
        // aim for the nearest passable tile along the way
        for (int back = 0; back <= tiles; back++) {
            int cx = tx - dx * back, cy = ty - dy * back;
            if (!world.isColliding(cx, cy))
                return walker.walkTo(new Vector2((cx + 0.5f) * ts, (cy + 0.5f) * ts), dir + " " + (tiles - back) + " tiles");
        }
        return now(false, "nothing passable that way");
    }

    private PointOfInterest findPoi(String name) {
        World world = Current.world();
        int ts = world.getTileSize();
        PointOfInterest best = null;
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (!poi.getActive() || poi.getDisplayName() == null) continue;
            Vector2 c = poi.getCenter();
            if (!bridge.cheatsAllowed() && !world.isExploredWorld((int) (c.x / ts), (int) (c.y / ts))) continue;
            if (poi.getDisplayName().equalsIgnoreCase(name)) return poi;
            if (best == null && poi.getDisplayName().toLowerCase().contains(name.toLowerCase())) best = poi;
        }
        return best;
    }

    private CompletableFuture<Map<String, Object>> fastTime(boolean on) {
        if (!needGame()) return now(false, "no game loaded");
        WorldStage.getInstance().setFastTimeEnabled(on);
        return now(true, "fast time " + (on ? "on" : "off"));
    }

    private CompletableFuture<Map<String, Object>> leave() {
        if (!needGame()) return now(false, "no game loaded");
        if (!MapStage.getInstance().isInMap()) return now(false, "not inside a map");
        walker.cancel("leaving");
        MapStage.getInstance().exitDungeon(false, false);
        return now(true, "leaving the map");
    }

    // ------------------------------------------------------------------ generic UI

    private CompletableFuture<Map<String, Object>> click(JsonValue a) {
        // Forge's own toolkit first (win/lose view, option panes, the match screen): ids "fN" from
        // the last state, or a text match against the buttons visible right now.
        forge.toolbox.FButton fb = null;
        if (a.has("id") && a.getString("id").startsWith("f"))
            fb = observer.forgeButton(a.getString("id"));
        if (fb == null && a.has("text")) {
            String want = a.getString("text").trim().toLowerCase();
            List<forge.toolbox.FButton> all = new ArrayList<>(AgentObserver.forgeButtons());
            all.addAll(AgentObserver.forgeScreenButtons());
            for (forge.toolbox.FButton b : all) {
                String t = Jsons.plain(String.valueOf(b.getText())).toLowerCase();
                if (t.equals(want) || t.contains(want)) { fb = b; break; }
            }
        }
        if (fb != null) {
            if (!fb.isEnabled() || !fb.isVisible())
                return now(false, "that button is not available");
            String label = Jsons.plain(String.valueOf(fb.getText()));
            fb.tap(fb.getWidth() / 2f, fb.getHeight() / 2f, 1);
            return now(true, "tapped '" + label + "'");
        }
        Actor target = null;
        if (a.has("id"))
            target = observer.actor(a.getString("id"));
        if (target == null && a.has("text")) {
            String want = a.getString("text").trim().toLowerCase();
            // refresh the table against the live UI first
            observer.snapshot(bridge.cheatsAllowed());
            for (Map.Entry<String, Actor> e : observer.lastActors().entrySet()) {
                if (e.getValue() instanceof Button && AgentObserver.buttonText((Button) e.getValue()).toLowerCase().equals(want)) { target = e.getValue(); break; }
            }
            if (target == null)
                for (Map.Entry<String, Actor> e : observer.lastActors().entrySet())
                    if (e.getValue() instanceof Button && AgentObserver.buttonText((Button) e.getValue()).toLowerCase().contains(want)) { target = e.getValue(); break; }
            if (target == null)
                for (Map.Entry<String, Actor> e : observer.lastActors().entrySet())
                    if (want.equals(e.getValue().getName())) { target = e.getValue(); break; }
        }
        if (target == null)
            return now(false, "nothing to click - take a fresh /state and use its ids, or a button text");
        if (!AgentObserver.visible(target))
            return now(false, "that button is no longer visible - take a fresh /state");
        if (target instanceof Button && ((Button) target).isDisabled())
            return now(false, "that button is disabled");
        String label = target instanceof Button ? AgentObserver.buttonText((Button) target) : String.valueOf(target.getName());
        press(target);
        return now(true, "clicked " + (label.isEmpty() ? String.valueOf(target.getName()) : "'" + label + "'"));
    }

    /** A real touch on the actor's own stage: hit-testing, capture listeners and modality all apply. */
    static void press(Actor target) {
        Stage stage = target.getStage();
        Vector2 v = target.localToStageCoordinates(new Vector2(target.getWidth() / 2f, target.getHeight() / 2f));
        stage.stageToScreenCoordinates(v);
        int sx = Math.round(v.x), sy = Math.round(v.y);
        stage.touchDown(sx, sy, 0, Input.Buttons.LEFT);
        stage.touchUp(sx, sy, 0, Input.Buttons.LEFT);
    }

    /** Click the open dialog itself: skips a still-typing MapDialog to its end so its options appear. */
    private CompletableFuture<Map<String, Object>> advance() {
        com.badlogic.gdx.scenes.scene2d.ui.Dialog d = observer.currentDialog(Forge.getCurrentScene());
        if (d == null) return now(false, "no dialog is open");
        press(d);
        return now(true, "advanced the dialog - read the state for its options");
    }

    private CompletableFuture<Map<String, Object>> back() {
        Scene scene = Forge.getCurrentScene();
        if (scene instanceof UIScene) {
            ((UIScene) scene).back();
            return now(true, "back");
        }
        return now(false, "back applies to menu scenes; on a map use `leave` or click the HUD");
    }

    private CompletableFuture<Map<String, Object>> key(String name) {
        int code = Input.Keys.valueOf(name.toUpperCase());
        if (code < 0) return now(false, "unknown key " + name);
        Scene scene = Forge.getCurrentScene();
        if (scene instanceof UIScene) {
            ((UIScene) scene).keyPressed(code);
            ((UIScene) scene).keyReleased(code);
        } else if (scene instanceof HudScene) {
            ((HudScene) scene).keyDown(code);
            ((HudScene) scene).keyUp(code);
        } else {
            return now(false, "no key handling on " + scene.getClass().getSimpleName());
        }
        return now(true, "pressed " + name);
    }

    // ------------------------------------------------------------------ items

    private ItemData findItem(String name, Boolean equipped) {
        AdventurePlayer p = Current.player();
        ItemData loose = null;
        for (ItemData it : p.getItems()) {
            if (it.name == null || !it.name.equalsIgnoreCase(name)) continue;
            boolean worn = it.isEquipped && it.longID != null && p.getEquippedItems().contains(it.longID);
            if (equipped == null || equipped == worn) return it;
            if (loose == null) loose = it;
        }
        return null;
    }

    private CompletableFuture<Map<String, Object>> equip(String name, boolean on) {
        if (!needGame()) return now(false, "no game loaded");
        ItemData it = findItem(name, !on);
        if (it == null) return now(false, (on ? "no unequipped " : "no equipped ") + "item named '" + name + "'");
        if (it.equipmentSlot == null || it.equipmentSlot.isEmpty()) return now(false, it.name + " is not equipment");
        Current.player().equip(it);
        return now(true, (on ? "equipped " : "unequipped ") + it.name + " (" + it.equipmentSlot + ")");
    }

    private CompletableFuture<Map<String, Object>> use(String name) {
        if (!needGame()) return now(false, "no game loaded");
        ItemData it = findItem(name, true);
        if (it == null) return now(false, "no equipped item named '" + name + "' (abilities must be equipped to use, as in the HUD)");
        if (it.commandOnUse == null || it.commandOnUse.isEmpty()) return now(false, it.name + " has no use action");
        boolean inMap = MapStage.getInstance().isInMap();
        if (!(inMap ? it.usableInPoi : it.usableOnWorldMap)) return now(false, it.name + " cannot be used " + (inMap ? "inside a map" : "on the world map"));
        if (it.shardsNeeded > Current.player().getShards()) return now(false, "not enough shards (" + it.shardsNeeded + " needed)");
        if (Forge.advFreezePlayerControls) return now(false, "controls are frozen right now");
        Current.player().addShards(-it.shardsNeeded);
        String out = ConsoleCommandInterpreter.getInstance().command(it.commandOnUse);
        AdventureQuestController.instance().updateItemUsed(it);
        return now(true, "used " + it.name + ": " + out);
    }

    private CompletableFuture<Map<String, Object>> sell(String name) {
        if (!needGame()) return now(false, "no game loaded");
        ItemData it = findItem(name, null);
        if (it == null) return now(false, "no item named '" + name + "'");
        if (it.questItem) return now(false, it.name + " is a quest item");
        int price = (int) (it.cost * 0.25f); // InventoryScene.sellPrice
        it.isEquipped = false;
        Current.player().giveGold(price);
        Current.player().removeItem(it);
        return now(true, "sold " + it.name + " for " + price + " gold");
    }

    // ------------------------------------------------------------------ decks

    private CompletableFuture<Map<String, Object>> deck(JsonValue a) {
        if (!needGame()) return now(false, "no game loaded");
        AdventurePlayer p = Current.player();
        String op = a.getString("op", "list");
        switch (op) {
            case "select": {
                int i = a.getInt("index", 0);
                if (i < 0 || i >= p.getDeckCount()) return now(false, "deck index out of range");
                p.setSelectedDeckSlot(i);
                return now(true, "selected deck " + i + " '" + p.getDeck(i).getName() + "'");
            }
            case "list": {
                Deck d = a.has("index") ? p.getDeck(a.getInt("index")) : p.getSelectedDeck();
                if (d == null) return now(false, "no such deck");
                Map<String, Object> m = WalkController.result(true, d.getName() + ": " + d.getMain().countAll() + " cards");
                List<String> cards = new ArrayList<>();
                for (Map.Entry<PaperCard, Integer> e : d.getMain())
                    cards.add(e.getValue() + "x " + e.getKey().getName());
                m.put("cards", cards);
                return CompletableFuture.completedFuture(m);
            }
            case "add":
            case "remove": {
                Deck d = a.has("index") ? p.getDeck(a.getInt("index")) : p.getSelectedDeck();
                if (d == null) return now(false, "no such deck");
                String card = a.getString("card", "");
                int n = a.getInt("count", 1);
                PaperCard pc = null;
                for (Map.Entry<PaperCard, Integer> e : p.getCards())
                    if (e.getKey().getName().equalsIgnoreCase(card)) { pc = e.getKey(); break; }
                if (pc == null) return now(false, "you do not own '" + card + "'");
                if (op.equals("add")) {
                    int owned = p.getCards().count(pc);
                    int used = p.getCopiesUsedInDecks(pc);
                    if (used + n > owned) return now(false, "only " + owned + " owned and " + used + " already listed in decks");
                    d.getMain().add(pc, n);
                    return now(true, "added " + n + "x " + pc.getName() + " to '" + d.getName() + "' (" + d.getMain().countAll() + " cards)");
                }
                int have = d.getMain().count(pc);
                if (have <= 0) return now(false, "'" + d.getName() + "' has no " + pc.getName());
                d.getMain().remove(pc, Math.min(n, have));
                return now(true, "removed " + Math.min(n, have) + "x " + pc.getName() + " (" + d.getMain().countAll() + " cards)");
            }
            case "collection": {
                Map<String, Object> m = WalkController.result(true, p.getCards().countAll() + " cards");
                List<String> cards = new ArrayList<>();
                for (Map.Entry<PaperCard, Integer> e : p.getCards())
                    cards.add(e.getValue() + "x " + e.getKey().getName());
                cards.sort(String::compareTo);
                m.put("cards", cards);
                return CompletableFuture.completedFuture(m);
            }
            default:
                return now(false, "deck op must be select, list, add, remove or collection");
        }
    }

    // ------------------------------------------------------------------ shops

    private CompletableFuture<Map<String, Object>> buy(int index) {
        Scene scene = Forge.getCurrentScene();
        if (!(scene instanceof RewardScene)) return now(false, "no shop open");
        List<Button> buttons = new ArrayList<>();
        Stage st = AgentObserver.stageFor(scene);
        if (st != null) AgentObserver.collectButtons(st.getRoot(), buttons);
        List<Button> buys = new ArrayList<>();
        for (Button b : buttons)
            if (b.getClass().getSimpleName().equals("BuyButton"))
                buys.add(b);
        if (index < 0 || index >= buys.size()) return now(false, "buy index out of range (0-" + (buys.size() - 1) + ")");
        Button b = buys.get(index);
        if (b.isDisabled()) return now(false, "that item cannot be bought right now");
        press(b);
        return now(true, "pressed buy on item " + index + " (" + AgentObserver.buttonText(b) + ")");
    }

    // ------------------------------------------------------------------ saves

    private CompletableFuture<Map<String, Object>> save(int slot, String name) {
        if (!needGame()) return now(false, "no game loaded");
        boolean ok = WorldSave.getCurrentSave().save(name, slot);
        return now(ok, ok ? "saved to slot " + slot : "save failed");
    }

    private CompletableFuture<Map<String, Object>> load(int slot) {
        walker.cancel("loading a save");
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        Forge.setTransitionScreen(new TransitionScreen(() -> {
            boolean ok = WorldSave.load(slot);
            if (ok)
                Forge.switchScene(GameScene.instance());
            f.complete(WalkController.result(ok, ok ? "loaded slot " + slot : "load failed: " + slot));
        }, null, false, true, "Loading"));
        return f;
    }
}
