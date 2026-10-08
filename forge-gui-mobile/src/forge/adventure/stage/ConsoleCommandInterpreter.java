package forge.adventure.stage;


import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.StaticData;
import forge.adventure.character.PlayerSprite;
import forge.adventure.data.*;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.scene.InnScene;
import forge.adventure.scene.InventoryScene;
import forge.adventure.util.AdventureEventController;
import forge.adventure.util.CardUtil;
import forge.adventure.util.ColorReputation;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.util.Paths;
import forge.adventure.util.ResourceSpawns;
import forge.adventure.util.TerritoryControl;
import forge.adventure.util.TownRestoration;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.card.CardEdition;
import forge.card.ColorSet;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckProxy;
import forge.game.GameType;
import forge.gui.FThreads;
import forge.item.PaperCard;
import forge.model.CardBlock;
import forge.model.FModel;
import forge.screens.CoverScreen;
import forge.util.Aggregates;
import forge.util.ScreenUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConsoleCommandInterpreter {
    private static ConsoleCommandInterpreter instance;
    Command root = new Command();
    private final ArrayList<String> matchTokenList = new ArrayList<>(32);
    private final StringBuilder completionBuilder = new StringBuilder(128);
    private static final String[] emptyStringArray = new String[0];

    static class Command {
        HashMap<String, Command> children = new HashMap<>();
        Function<String[], String> function;
    }

    public String complete(String text) {
        String[] words = splitOnSpace(text);
        Command currentCommand = root;

        completionBuilder.setLength(0);

        for (String name : words) {
            if (!currentCommand.children.containsKey(name)) {
                for (String key : currentCommand.children.keySet()) {
                    if (key.startsWith(name)) {
                        // append directly
                        completionBuilder.append(key).append(" ");
                        return completionBuilder.toString();
                    }
                }
                break;
            }
            completionBuilder.append(name).append(" ");
            currentCommand = currentCommand.children.get(name);
        }
        return text;
    }

    private String[] splitOnSpace(String text) {
        matchTokenList.clear();

        Pattern regex = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'");
        Matcher regexMatcher = regex.matcher(text);
        while (regexMatcher.find()) {
            if (regexMatcher.group(1) != null) {
                matchTokenList.add(regexMatcher.group(1));
            } else if (regexMatcher.group(2) != null) {
                matchTokenList.add(regexMatcher.group(2));
            } else {
                matchTokenList.add(regexMatcher.group());
            }
        }

        // reuse
        return matchTokenList.toArray(emptyStringArray);
    }

    public String command(String text) {
        String[] words = splitOnSpace(text);
        Command currentCommand = root;
        int i;

        for (i = 0; i < words.length; i++) {
            String name = words[i];
            if (!currentCommand.children.containsKey(name)) break;
            currentCommand = currentCommand.children.get(name);
        }
        if (currentCommand.function == null) {
            return "Command not found. Available commands:\n" + String.join(" ", Arrays.copyOfRange(words, 0, i)) + "\n" + String.join("\n", currentCommand.children.keySet());
        }
        String[] parameters = Arrays.copyOfRange(words, i, words.length);
        // this removes apostrophe...
        /*for (int j = 0; j < parameters.length; j++)
            parameters[j] = parameters[j].replaceAll("[\"']", "");*/
        return currentCommand.function.apply(parameters);
    }

    /** Round 331: the item whose commandOnUse is running - see useItem(). Null for a typed or scripted command. */
    private ItemData itemInUse;

    /**
     * Round 331: runs an item's commandOnUse knowing which item paid for it, so a command that finds nothing to do
     * (a rune whose place is gone) can hand the item's shards back - refundItemInUse(). The four item-use paths come
     * through here: the HUD ability button, the inventory and armory Use buttons and the agent bridge; each charges
     * the shards first, as they always have. Returns the command's own answer, "" for an item with no command.
     */
    public String useItem(ItemData item) {
        if (item == null || item.commandOnUse == null || item.commandOnUse.isEmpty())
            return "";
        itemInUse = item;
        try {
            return command(item.commandOnUse);
        } finally {
            itemInUse = null;
        }
    }

    /** Hands the running item's shards back (when an item is running the command at all), shows `message` on the
     *  HUD, and returns a note for the command's own answer. A typed or scripted command refunds nothing. */
    private String refundItemInUse(String message) {
        ItemData item = itemInUse;
        if (item == null)
            return "";
        if (item.shardsNeeded > 0)
            Current.player().addShards(item.shardsNeeded);
        GameHUD.getInstance().addNotification(message);
        System.out.println("[TFR-Rune] " + item.name + " did nothing: " + message + " (" + item.shardsNeeded
                + " shard(s) refunded)");
        return " - " + item.name + ": " + item.shardsNeeded + " shard(s) refunded";
    }

    /** The banner for a teleport whose place is not on the map - a fallen color's capital says so. */
    private String missingTargetMessage(String poiName) {
        String what = itemInUse == null ? "the rune" : "the " + itemInUse.name;
        String color = TerritoryControl.colorOfCapitalName(poiName);
        if (color != null && Current.world() != null && Current.world().isColorDefeated(color))
            return poiName + " fell with its color - " + what + " stays quiet. No shard spent.";
        return poiName + " is not on the map - " + what + " stays quiet. No shard spent.";
    }

    void registerCommand(String[] path, Function<String[], String> function) {
        if (path.length == 0) return;
        Command currentCommand = root;

        for (String name : path) {
            if (!currentCommand.children.containsKey(name))
                currentCommand.children.put(name, new Command());
            currentCommand = currentCommand.children.get(name);
        }
        currentCommand.function = function;
    }

    public static ConsoleCommandInterpreter getInstance() {
        if (instance == null)
            instance = new ConsoleCommandInterpreter();
        return instance;
    }

    GameStage currentGameStage() {
        return MapStage.getInstance().isInMap() ? MapStage.getInstance() : WorldStage.getInstance();
    }

    PlayerSprite currentSprite() {
        return currentGameStage().getPlayerSprite();
    }

    private ConsoleCommandInterpreter() {
        registerCommand(new String[]{"teleport", "to"}, s -> {
            if (s.length < 2)
                return "Command needs 2 parameters";
            try {
                int x = Integer.parseInt(s[0]);
                int y = Integer.parseInt(s[1]);
                WorldStage.getInstance().setPosition(new Vector2(x, y));
                WorldStage.getInstance().player.playEffect(Paths.EFFECT_TELEPORT, 10);
                return "teleport to (" + s[0] + "," + s[1] + ")";
            } catch (Exception e) {
                return "Exception occurred, Invalid input";
            }
        });
        registerCommand(new String[]{"teleport", "to", "poi"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: PoI name.";
            PointOfInterest poi = Current.world().findPointsOfInterest(s[0]);
            if (poi == null) {
                // Round 331 (VeggieShark on Discord, v1.14: "When the red player was defeated and his cities
                // disappeared, I was still able to use the red teleport token. It didn't teleport me, but used 1 shard
                // nevertheless"; the user: "When an AI is defeated, rune does not work anymore"). A defeated color's
                // capital is transformed into a neutral town (TerritoryControl.defeatColor()), so the rune's target is
                // no longer on the map - and every item-use path charged the shards before this ran. The rune stays
                // in the bag, does nothing, says so and costs nothing: the Rally rune's own no-target rule.
                return "PoI " + s[0] + " not found" + refundItemInUse(missingTargetMessage(s[0]));
            }
            // Round 310: a map is left properly first - its exit rules and its place in the scene history - as the
            // Teleporter (EconomyBuildings.travelTo()) and every portal do. Loading one map over another without
            // leaving it is how a chain of hops ended in a lost duel whose result reached the world stage (the NPE in
            // WorldStage.setWinner, agent log forge.r301-crash.log).
            // Round 383b (agent test): a teleport issued while a duel ran loaded the place under it, and the duel's end
            // then reached MapStage.setWinner with no mob of that map - an NPE that closed the game. Not during a duel.
            if (Forge.getCurrentScene() instanceof forge.adventure.scene.DuelScene)
                return "Not during a duel.";
            if (MapStage.getInstance().isInMap())
                MapStage.getInstance().exitDungeon(false, false);

            Forge.advFreezePlayerControls = true;
            FThreads.invokeInEdtNowOrLater(() -> Forge.setTransitionScreen(new CoverScreen(() -> {
                Forge.advFreezePlayerControls = false;
                WorldStage.getInstance().setPosition(new Vector2(poi.getPosition().x - 16f, poi.getPosition().y + 16f));
                WorldStage.getInstance().loadPOI(poi);
                Forge.clearTransitionScreen();
            }, ScreenUtil.getInstance().takeScreenshot())));
            return "Teleported to " + s[0] + "(" + poi.getPosition() + ")";
        });
        // Homeward rune (MOD_CHANGELOG.md 2026-08-22, user request: "take you to the spawn area,
        // until you have a Capitol, then take you to just outside the cap"). Round 253 (user: "So the
        // homeward ruin will basically work like it has once you have a capitol from the start"): there
        // IS a home from the first minute now - Orazca, the ruin at the centre of the star - so the two
        // halves collapse into one. Always position-only (no loadPOI(), same as the raw "teleport to X Y"
        // command above): the player lands just outside home on the overworld rather than being dropped
        // inside it, per the user's original "just outside" wording. findHome() falls back to the spawn
        // cave for a world generated before round 253, which is where this used to go.
        registerCommand(new String[]{"teleport", "home"}, s -> {
            PointOfInterest home = TownRestoration.findHome();
            if (home == null)
                return "No home to return to";
            WorldStage.getInstance().setPosition(new Vector2(home.getPosition().x - 16f, home.getPosition().y + 16f));
            WorldStage.getInstance().player.playEffect(Paths.EFFECT_TELEPORT, 10);
            return "Teleported outside " + home.getDisplayName() + "(" + home.getPosition() + ")";
        });
        // Rally rune (round 122, user request 2026-09-05): "teleport rally" carries the player just
        // outside the next player town under attack - TerritoryControl.nextRallyTarget() cycles
        // through every targeted player town, one per use, before starting over. Both item-use
        // paths (InventoryScene.triggerUse, GameHUD.setAbilityButton) charge the rune's shards
        // BEFORE the command runs and ignore its result, so the no-target case refunds them here
        // and says so on the HUD: while nothing of the player's is under attack the rune is a
        // no-op, not a wasted shard. Position-only like "teleport home" (no loadPOI), so the
        // player lands on the overworld beside the town and can intercept the mage on the road
        // rather than being dropped inside the town.
        registerCommand(new String[]{"teleport", "rally"}, s -> {
            List<PointOfInterest> underAttack = TerritoryControl.rallyTargets(); // round 337: the Ring Cities too
            PointOfInterest target = TerritoryControl.nextRallyTarget(Current.world(), underAttack);
            if (target == null) {
                ItemData rune = ItemListData.getItem("Rally rune");
                if (rune != null && rune.shardsNeeded > 0)
                    Current.player().addShards(rune.shardsNeeded);
                GameHUD.getInstance().addNotification("None of your towns and no Ring City is under attack - the Rally rune stays quiet.");
                System.out.println("[TFR-RallyRune] no player town or Ring City under attack - nothing to rally to, shards refunded");
                return "No player town or Ring City is under attack";
            }
            WorldStage.getInstance().setPosition(new Vector2(target.getPosition().x - 16f, target.getPosition().y + 16f));
            WorldStage.getInstance().player.playEffect(Paths.EFFECT_TELEPORT, 10);
            boolean ringCity = TerritoryControl.isRallyRingCity(Current.world(), target); // round 337
            GameHUD.getInstance().addNotification("Rallied to " + target.getDisplayName() + (ringCity ? " - a Ring City under attack" : "")
                    + (underAttack.size() > 1 ? " - " + underAttack.size() + " places are under attack" : ""));
            System.out.println("[TFR-RallyRune] rallied to " + target.getDisplayName() + (ringCity ? " (Ring City)" : " (own town)")
                    + ", " + underAttack.size() + " under attack");
            return "Teleported outside " + target.getDisplayName() + "(" + target.getPosition() + ")";
        });
        // Round 331 (testing aid): defeat a color as its last castle falling would - TerritoryControl.defeatColor() -
        // so a fallen color's rune can be tried without a whole campaign.
        registerCommand(new String[]{"defeat", "color"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: white/blue/black/red/green.";
            String color = s[0].toLowerCase();
            if (!Arrays.asList(TerritoryControl.COLORS).contains(color)) return "No color " + s[0];
            if (Current.world().isColorDefeated(color)) return color + " is already defeated";
            TerritoryControl.defeatColor(Current.world(), color);
            return "Defeated " + color;
        });
        registerCommand(new String[]{"spawn", "enemy"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: enemy name.";

            if (WorldStage.getInstance().spawn(s[0]))
                return "Spawn " + s[0];
            return "Can not find enemy " + s[0];
        });
        // Round 375, the legend table: sight a legend of this color's pool now (no chance roll, cooldown or cap - the
        // one-of-each rule and the rank gate still hold).
        registerCommand(new String[]{"legend"}, s -> {
            if (s.length < 1)
                return "Command needs a color (white/blue/black/red/green).";
            return WorldStage.getInstance().forceLegendSighting(s[0].toLowerCase());
        });
        // Round 375: draw from a color's legend pool n times (default 2000) against the save's sighting counts and report
        // the pool, how many share the fewest sightings, and the names drawn most - the least-seen-first check. Nothing
        // is spawned or counted.
        registerCommand(new String[]{"legendroll"}, s -> {
            if (s.length < 1)
                return "Command needs a color (white/blue/black/red/green) and optionally a draw count.";
            int n = 2000;
            if (s.length > 1) {
                try {
                    n = Integer.parseInt(s[1]);
                } catch (Exception e) {
                    return "Can not convert " + s[1] + " to number";
                }
            }
            String color = s[0].toLowerCase();
            if (forge.adventure.util.LegendSpawns.colorLetterOf(color) == null)
                return "No color " + s[0];
            forge.adventure.world.World world = WorldSave.getCurrentSave().getWorld();
            float rank = Current.player().getStatistic().rank();
            java.util.List<EnemyData> pool = forge.adventure.util.LegendSpawns.candidatesFor(color, rank, null);
            if (pool.isEmpty())
                return "No legend fits " + color + " at rank " + rank;
            Map<String, Integer> drawn = new TreeMap<>();
            java.util.Random random = new java.util.Random();
            forge.adventure.util.LegendSpawns.Pick first = null;
            for (int i = 0; i < n; i++) {
                forge.adventure.util.LegendSpawns.Pick pick = forge.adventure.util.LegendSpawns.pick(world, pool, random);
                if (first == null)
                    first = pick;
                drawn.merge(pick.enemy.name, 1, Integer::sum);
            }
            java.util.List<Map.Entry<String, Integer>> top = new java.util.ArrayList<>(drawn.entrySet());
            top.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder most = new StringBuilder();
            for (int i = 0; i < Math.min(8, top.size()); i++)
                most.append(i == 0 ? "" : ", ").append(top.get(i).getKey()).append(' ').append(top.get(i).getValue())
                        .append(" (seen ").append(forge.adventure.util.LegendSpawns.sightings(world, top.get(i).getKey())).append("x)");
            String line = "[TFR-LegendTable] legendroll " + color + " x" + n + " at rank " + rank + ": pool " + pool.size()
                    + ", " + first.leastSeen + " at the fewest sightings (" + first.fewestSightings + "), " + drawn.size()
                    + " distinct drawn; most: " + most + "; " + ColorReputation.getStatus(color).label + " now";
            System.out.println(line);
            return line;
        });
        // Round 311: roll a land's spawn picker n times (default 2000) at the player's rank and report how often the
        // roaming champions came up, by name, and the frontier legends - the share check. Nothing is spawned.
        // Round 375: legends left the ordinary roll for the legend table - every count here should read 0.
        registerCommand(new String[]{"spawnroll"}, s -> {
            if (s.length < 1)
                return "Command needs a biome name (white/blue/black/red/green/waste/player) and optionally a roll count.";
            int n = 2000;
            if (s.length > 1) {
                try {
                    n = Integer.parseInt(s[1]);
                } catch (Exception e) {
                    return "Can not convert " + s[1] + " to number";
                }
            }
            BiomeData biome = null;
            for (BiomeData b : WorldSave.getCurrentSave().getWorld().getData().GetBiomes())
                if (b.name.equalsIgnoreCase(s[0]))
                    biome = b;
            if (biome == null)
                return "No biome " + s[0];
            // Round 447: the rank a spawn really rolls against (a 30+ streak steps it up), and the rank mix it gives.
            float rank = forge.adventure.util.SpawnTierWeighting.effectiveRank(Current.player().getStatistic().rank());
            Map<String, Integer> champions = new TreeMap<>();
            int[] ranks = new int[4];
            int roaming = 0, frontier = 0;
            for (int i = 0; i < n; i++) {
                EnemyData e = biome.getEnemy(rank);
                if (e != null)
                    ranks[EnemyData.tierRank(e.tier)]++;
                if (forge.adventure.util.RoamingChampions.isChampion(e)) {
                    roaming++;
                    champions.merge(e.getName(), 1, Integer::sum);
                } else if (forge.adventure.util.FrontierSpawns.isCandidate(e))
                    frontier++;
            }
            String mix = String.format("Apprentice %.1f%% Adept %.1f%% Master %.1f%% Archmage %.1f%%", 100f * ranks[0] / n,
                    100f * ranks[1] / n, 100f * ranks[2] / n, 100f * ranks[3] / n);
            String line = "[TFR-SpawnRoll] spawnroll " + biome.name + " x" + n + " at rank " + rank + ", "
                    + Current.player().notorietyStreak() + " wins in a row: " + mix + " | champions " + roaming + " "
                    + champions + ", frontier legends " + frontier + " (both 0 since round 375)";
            System.out.println(line);
            return line;
        });
        registerCommand(new String[]{"give", "gold"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: Amount.";
            int amount;
            try {
                amount = Integer.parseInt(s[0]);
            } catch (Exception e) {
                return "Can not convert " + s[0] + " to number";
            }
            Current.player().giveGold(amount);
            return "Added " + amount + " gold";
        });
        // Color Reputation (MOD_SCOPE.md #1) testing: shift one color's reputation by a display-
        // value amount (negative allowed), net-zero preserved by spreading the negation across
        // the other 4 colors - added specifically so tier thresholds (+-20/+-80) can be tested
        // without grinding ~40 real duel wins per tier.
        registerCommand(new String[]{"give", "rep"}, s -> {
            if (s.length < 2) return "Command needs 2 parameters: Color (white/blue/black/red/green) and Amount.";
            String color = s[0].toLowerCase();
            boolean known = false;
            for (String c : ColorReputation.COLORS)
                if (c.equals(color)) { known = true; break; }
            if (!known) return "Unknown color \"" + s[0] + "\" - use white, blue, black, red or green.";
            int amount;
            try {
                amount = Integer.parseInt(s[1]);
            } catch (Exception e) {
                return "Can not convert " + s[1] + " to number";
            }
            ColorReputation.debugShiftReputation(color, amount);
            StringBuilder sb = new StringBuilder("Shifted " + color + " by " + amount + ". Now:");
            for (String c : ColorReputation.COLORS)
                sb.append(" ").append(c).append("=").append(ColorReputation.displayValue(Current.player().getColorReputationHalfPoints(c)));
            return sb.toString();
        });
        // Wood/Stone testing (MOD_SCOPE.md #9). "lumber" is a deliberate alias for wood - the
        // two words kept getting interchanged during design, and per user decision "wood" is the
        // canonical resource name (the building stays "Lumber Mill"; it produces wood).
        Function<String[], String> giveWood = s -> {
            if (s.length < 1) return "Command needs 1 parameter: Amount.";
            int amount;
            try {
                amount = Integer.parseInt(s[0]);
            } catch (Exception e) {
                return "Can not convert " + s[0] + " to number";
            }
            Current.player().addWood(amount);
            // Same feedback sound the sparkle pickups use for wood/stone (user request 2026-08-13
            // - gold/shards already sound via their own addGold/addShards; addWood is silent).
            forge.sound.SoundSystem.instance.play(forge.sound.SoundEffectType.CoinsDrop, false);
            System.out.println("[TFR-Give] wood +" + amount);
            return "Added " + amount + " wood";
        };
        registerCommand(new String[]{"give", "wood"}, giveWood);
        registerCommand(new String[]{"give", "lumber"}, giveWood);
        // Drops one random resource pickup next to the player - for testing the spawn mechanic
        // (icon, twinkle, walk-over pickup) without hunting one of the ~20 across the whole map.
        registerCommand(new String[]{"spawn", "resource"}, s -> ResourceSpawns.debugSpawnNearPlayer());
        registerCommand(new String[]{"give", "stone"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: Amount.";
            int amount;
            try {
                amount = Integer.parseInt(s[0]);
            } catch (Exception e) {
                return "Can not convert " + s[0] + " to number";
            }
            Current.player().addStone(amount);
            forge.sound.SoundSystem.instance.play(forge.sound.SoundEffectType.CoinsDrop, false);
            System.out.println("[TFR-Give] stone +" + amount);
            return "Added " + amount + " stone";
        });
        registerCommand(new String[]{"give", "quest"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: QuestID";
            int ID;
            try {
                ID = Integer.parseInt(s[0]);
            } catch (Exception e) {
                return "Can not convert " + s[0] + " to number";
            }
            Current.player().addQuest(ID, false);
            return "Quest generated";
        });
        // Main-quest testing commands (2026-08-26 user request: "some of the other stuff might
        // be easier if you could build in some F9 commands to help"). Every new main-quest
        // objective is a flag comparison, so setting the flag from the console completes the
        // stage exactly as the real event would (set* fires the quest event; advance* wouldn't).
        // e.g.: "set charflag capitolBuilt 1", "set questflag townsRestored 5",
        //       "set charflag researchComplete 1", "set questflag mainQuest 2".
        registerCommand(new String[]{"set", "charflag"}, s -> {
            if (s.length < 2) return "Command needs 2 parameters: FlagName Value";
            int value;
            try {
                value = Integer.parseInt(s[1]);
            } catch (Exception e) {
                return "Can not convert " + s[1] + " to number";
            }
            Current.player().setCharacterFlag(s[0], value);
            return "Character flag " + s[0] + " set to " + value + " (value 0 removes the flag)";
        });
        registerCommand(new String[]{"set", "questflag"}, s -> {
            if (s.length < 2) return "Command needs 2 parameters: FlagName Value";
            int value;
            try {
                value = Integer.parseInt(s[1]);
            } catch (Exception e) {
                return "Can not convert " + s[1] + " to number";
            }
            Current.player().setQuestFlag(s[0], value);
            return "Quest flag " + s[0] + " set to " + value + " (value 0 removes the flag)";
        });
        // Per-POI MAP flags (quest 30's "townRestored"/"economyBuilt_10" stages key these) -
        // must be run while STANDING IN the target town's map, since the flag lives on that
        // POI's own changes and the MAPFLAG quest event carries the current map's context.
        registerCommand(new String[]{"set", "mapflag"}, s -> {
            if (s.length < 2) return "Command needs 2 parameters: FlagName Value";
            if (!MapStage.getInstance().isInMap()) return "Not in a map - enter the target town first";
            int value;
            try {
                value = Integer.parseInt(s[1]);
            } catch (Exception e) {
                return "Can not convert " + s[1] + " to number";
            }
            MapStage.getInstance().setQuestFlag(s[0], value);
            return "Map flag " + s[0] + " set to " + value + " on the current location";
        });
        registerCommand(new String[]{"give", "shards"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: Amount.";
            int amount;
            try {
                amount = Integer.parseInt(s[0]);
            } catch (Exception e) {
                return "Can not convert " + s[0] + " to number";
            }
            Current.player().addShards(amount);
            return "Added " + amount + " shards";
        });
        registerCommand(new String[]{"give", "life"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: Amount.";
            int amount;
            try {
                amount = Integer.parseInt(s[0]);
            } catch (Exception e) {
                return "Can not convert " + s[0] + " to number";
            }
            Current.player().addMaxLife(amount);
            return "Added " + amount + " max life";
        });
        registerCommand(new String[]{"leave"}, s -> {
            if (!MapStage.getInstance().isInMap()) return "not on a map";
            // Round 448: not during a duel - leaving the map mid-duel closed the game (an NPE in FDropDown when the
            // duel's screen updated after the scene had switched; round 443's agent test). teleport to poi has had
            // this guard since round 383b.
            if (Forge.getCurrentScene() instanceof forge.adventure.scene.DuelScene)
                return "Not during a duel.";
            MapStage.getInstance().exitDungeon(false, false);
            return "Got out";
        });
        registerCommand(new String[]{"debug", "collision"}, s -> {
            currentGameStage().debugCollision(true);
            return "Debug collision ON";
        });
        registerCommand(new String[]{"give", "card"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: Card name.";
            PaperCard card = StaticData.instance().fetchCard(s[0]);
            if (card == null) return "Cannot find card: " + s[0];
            if (s.length >= 2) {
                try {
                    int amount = Integer.parseInt(s[1]);
                    Current.player().addCard(card, amount);
                    return String.format("Added %d cards: %s", amount, card.getName());
                } catch (NumberFormatException ignored) {
                }
            }
            Current.player().addCard(card);
            return "Added card: " + card.getName();
        });
        registerCommand(new String[]{"give", "nosell", "card"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: Card name.";
            PaperCard card = StaticData.instance().fetchCard(s[0]);
            if (card == null) return "Cannot find card: " + s[0];
            if (s.length >= 2) {
                try {
                    int amount = Integer.parseInt(s[1]);
                    Current.player().addCard(card.getNoSellVersion(), amount);
                    return String.format("Added %d cards: %s", amount, card.getName());
                } catch (NumberFormatException ignored) {
                }
            }
            Current.player().addCard(card.getNoSellVersion());
            return "Added card: " + card.getName();
        });
        registerCommand(new String[]{"give", "print"}, s -> {
            if (s.length < 2) return "Command needs 2 parameters: Edition code, collector number.";
            CardEdition edition = StaticData.instance().getCardEdition(s[0]);
            if (edition == null) return "Cannot find edition: " + s[0];
            CardEdition.EditionEntry cis = edition.getCardFromCollectorNumber(s[1]);
            if (cis == null)
                return String.format("Set '%s' does not have a card with collector number '%s'.", edition.getName(), s[1]);
            PaperCard card = StaticData.instance().fetchCard(cis.name(), edition.getCode(), cis.collectorNumber());
            if (card == null) {
                //Found in the set, not supported.
                return String.format("Failed to fetch (%s, %s, %s) - Not currently supported.", cis.name(), edition.getCode(), cis.collectorNumber());
            }
            if (s.length >= 3) {
                try {
                    int amount = Integer.parseInt(s[2]);
                    Current.player().addCard(card, amount);
                    return String.format("Added %d cards: %s", amount, card.getName());
                } catch (NumberFormatException ignored) {
                }
            }
            Current.player().addCard(card);
            return "Added card: " + card.getName();
        });
        registerCommand(new String[]{"give", "set"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: Edition code.";
            CardEdition edition = StaticData.instance().getCardEdition(s[0]);
            if (edition == null) return "Cannot find edition: " + s[0];

            for (CardEdition.EditionEntry entry : edition.getObtainableCards()) {
                PaperCard card = StaticData.instance().fetchCard(entry.name(), edition.getCode(), entry.collectorNumber());

                if (card != null) {
                    Current.player().addCard(card.getNoSellVersion(), 4);
                } else {
                    System.out.println("Card " + entry.name() + " (" + entry.collectorNumber() + ") does not exist.");
                }
            }

            return "Added all cards from: " + edition.getCode();
        });
        registerCommand(new String[]{"give", "boosters"}, s -> {
            if (s.length < 1)
                return "Command needs at least 1 parameter: Edition code.";
            CardEdition edition = StaticData.instance().getCardEdition(s[0]);
            if (edition == null)
                return "Cannot find edition: " + s[0];
            if (!edition.hasBoosterTemplate())
                return edition.getCode() + " doesn't have a booster template.";

            int amount = 1;
            if (s.length >= 2) {
                try {
                    amount = Integer.parseInt(s[1]);
                } catch (NumberFormatException ignored) {
                }
            }

            for (int i = 0; i < amount; i++) {
                Current.player().addBooster(AdventureEventController.instance().generateBooster(edition.getCode()));
            }

            return "Added " + amount + " " + edition.getCode() + " booster(s)";
        });
        registerCommand(new String[]{"clearnosell"}, s -> {
            CardPool cards = Current.player().getCards();
            for (PaperCard c : cards.getFilteredPool(c -> c.getMarkedFlags().noSellValue).toFlatList()) {
                cards.remove(c);
            }
            return "Removed all no-sell flagged cards.";
        });
        registerCommand(new String[]{"sanitize", "editions"}, s -> {
            ConfigData configData = Config.instance().getConfigData();
            if (configData.allowedEditions == null || configData.allowedEditions.length == 0)
                return "No allowedEditions configured for this plane.";
            int replaced = CardUtil.sanitizeCardPool(Current.player().getCards());
            for (int i = 0; i < Current.player().getDeckCount(); i++) {
                Deck d = Current.player().getDeck(i);
                for (java.util.Map.Entry<forge.deck.DeckSection, CardPool> section : d) {
                    replaced += CardUtil.sanitizeCardPool(section.getValue());
                }
            }
            if (replaced == 0)
                return "All cards already from allowed editions.";
            return "Replaced " + replaced + " card(s) with allowed edition printings.";
        });
        registerCommand(new String[]{"give", "item"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: Item name.";
            if (Current.player().addItem(s[0])) {
                if (s[0].contains("Key"))
                    GameHUD.getInstance().updateKeys();
                return "Added item " + s[0] + ".";
            }
            return "Cannot find item " + s[0];
        });
        registerCommand(new String[]{"fullHeal"}, s -> {
            Current.player().fullHeal();
            currentSprite().playEffect(Paths.EFFECT_HEAL);
            return "Player fully healed. Health set to " + Current.player().getLife() + ".";
        });
        // Round 343 (testing aid): raise the Capitol from inside Orazca without the five-town gate, cost still paid -
        // the way to see player_capital.tmx in play after a layout change. Cheats only, like every command here.
        registerCommand(new String[]{"capitol", "raise"}, s ->
                forge.adventure.util.TownRestoration.debugRaiseCapitol(MapStage.getInstance()));
        // Round 411: test cheat - "notoriety 10" sets the wins in a row (level = wins / notorietyWinsPerLevel).
        registerCommand(new String[]{"notoriety"}, s -> {
            if (s.length < 1) return "Notoriety: " + Current.player().notorietyStreak() + " wins in a row, level "
                    + Current.player().notorietyLevel() + ". Give a number to set it.";
            try {
                Current.player().setNotorietyStreak(Integer.parseInt(s[0]));
            } catch (Exception e) {
                return "Can not convert " + s[0] + " to number";
            }
            return "Notoriety set to " + Current.player().notorietyStreak() + " wins in a row, level " + Current.player().notorietyLevel();
        });
        // Round 465: test cheat - 'winstreak "Wild Rat" 3' sets your wins in a row over one enemy (by name).
        registerCommand(new String[]{"winstreak"}, s -> {
            if (s.length < 1) return "Give an enemy name, and a number to set its streak.";
            if (s.length < 2) return s[0] + ": " + Current.player().winStreak(s[0]) + " win(s) in a row of "
                    + Current.player().winStreakNeeded();
            try {
                Current.player().setWinStreak(s[0], Integer.parseInt(s[1]));
            } catch (Exception e) {
                return "Can not convert " + s[1] + " to number";
            }
            return s[0] + " win streak set to " + Current.player().winStreak(s[0]) + " of " + Current.player().winStreakNeeded();
        });
        registerCommand(new String[]{"listPOI"}, s -> {
            ArrayList<String> poiNames = new ArrayList<>();
            List<BiomeData> biomeData = WorldSave.getCurrentSave().getWorld().getData().GetBiomes();
            for (BiomeData data : biomeData) {
                for (PointOfInterestData poi : data.getPointsOfInterest())
                    poiNames.add(poi.name + " - " + poi.type);
            }
            System.out.println("POI Names - Types\n" + String.join("\n", poiNames));
            return "POI lists dumped to stdout.";
        });
        // Territory Control (MOD_SCOPE.md #7): the actual, generated-map count of town/capital
        // POIs, not the theoretical max from points_of_interest.json's count fields (listPOI
        // above only dumps the latter, and world-gen doesn't always place every requested
        // instance). "Neutral" is TownRestoration.isWastelandTown() - still a Waste Town, not
        // yet captured by a color.
        registerCommand(new String[]{"count", "towns"}, s -> {
            List<PointOfInterest> all = WorldSave.getCurrentSave().getWorld().getAllPointOfInterest();
            int total = 0, neutral = 0;
            Map<String, Integer> byName = new TreeMap<>();
            for (PointOfInterest poi : all) {
                String type = poi.getData().type;
                if (!"town".equals(type) && !"capital".equals(type))
                    continue;
                total++;
                if (TownRestoration.isWastelandTown(poi.getData()))
                    neutral++;
                byName.merge(poi.getData().name, 1, Integer::sum);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Towns on map: ").append(total).append(" total, ").append(neutral)
                    .append(" still neutral, ").append(total - neutral).append(" captured/other.\n");
            for (Map.Entry<String, Integer> e : byName.entrySet())
                sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            System.out.println(sb);
            return "Towns: " + total + " total (" + neutral + " neutral). Full breakdown printed to stdout.";
        });
        registerCommand(new String[]{"setColorID"}, s -> {
            if (s.length < 1)
                return "Please specify color ID: Valid choices: B, G, R, U, W, C. Example:\n\"setColorID G\"";
            Current.player().setColorIdentity(s[0]);
            return "Player color identity set to " + Current.player().getColorIdentity() + ".";
        });
        registerCommand(new String[]{"resetQuests"}, s -> {
            Current.player().resetQuestFlags();
            return "All global quest flags have been reset.";
        });
        registerCommand(new String[]{"resetMapQuests"}, s -> {
            if (!MapStage.getInstance().isInMap()) return "Only supported inside a map.";
            MapStage.getInstance().resetQuestFlags();
            return "All local quest flags have been reset.";
        });
        registerCommand(new String[]{"dumpEnemyDeckColors"}, s -> {
            for (EnemyData E : new Array.ArrayIterator<>(WorldData.getAllEnemies())) {
                Deck D = E.generateDeck(Current.player().isFantasyMode(), Current.player().isUsingCustomDeck() || Current.player().isHardorInsaneDifficulty());
                DeckProxy DP = new DeckProxy(D, "Constructed", GameType.Constructed, null);
                ColorSet colorSet = DP.getColor();
                System.out.printf("%s: Colors: %s (%s%s%s%s%s%s)\n", D.getName(), DP.getColor(),
                        (colorSet.hasBlack() ? "B" : ""),
                        (colorSet.hasGreen() ? "G" : ""),
                        (colorSet.hasRed() ? "R" : ""),
                        (colorSet.hasBlue() ? "U" : ""),
                        (colorSet.hasWhite() ? "W" : ""),
                        (colorSet.isColorless() ? "C" : "")
                );
            }
            return "Enemy deck color list dumped to stdout.";
        });
        registerCommand(new String[]{"dumpEnemyDeckList"}, s -> {
            for (EnemyData E : new Array.ArrayIterator<>(WorldData.getAllEnemies())) {
                Deck D = E.generateDeck(Current.player().isFantasyMode(), Current.player().isUsingCustomDeck() || Current.player().isHardorInsaneDifficulty());
                DeckProxy DP = new DeckProxy(D, "Constructed", GameType.Constructed, null);
                System.out.printf("Deck: %s\n%s\n\n", D.getName(), DP.getDeck().getMain().toCardList("\n")
                );
            }
            return "Enemy deck list dumped to stdout.";
        });
        registerCommand(new String[]{"dumpEnemyColorIdentity"}, s -> {
            for (EnemyData E : new Array.ArrayIterator<>(WorldData.getAllEnemies())) {
                Deck D = E.generateDeck(Current.player().isFantasyMode(), Current.player().isUsingCustomDeck() || Current.player().isHardorInsaneDifficulty());
                DeckProxy DP = new DeckProxy(D, "Constructed", GameType.Constructed, null);
                System.out.printf("%s Colors: %s | Deck Colors: %s (%s)%s\n", E.name, E.colors, DP.getColorIdentity().toEnumSet().toString(), DP.getName()
                        , E.boss ? " - BOSS" : "");
            }
            return "Enemy color Identity dumped to stdout.";
        });
        registerCommand(new String[]{"heal", "amount"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: Amount";
            int N;
            try {
                N = Integer.parseInt(s[0]);
            } catch (Exception e) {
                return "Can not convert " + s[0] + " to integer";
            }
            Current.player().heal(N);
            currentSprite().playEffect(Paths.EFFECT_HEAL);
            return "Player healed to " + Current.player().getLife() + "/" + Current.player().getMaxLife();
        });
        registerCommand(new String[]{"heal", "percent"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: Amount";
            float value;
            try {
                value = Float.parseFloat(s[0]);
            } catch (Exception e) {
                return "Can not convert " + s[0] + " to integer";
            }
            Current.player().heal(value);
            currentSprite().playEffect(Paths.EFFECT_HEAL);
            return "Player healed to " + Current.player().getLife() + "/" + Current.player().getMaxLife();
        });
        registerCommand(new String[]{"heal", "full"}, s -> {
            Current.player().fullHeal();
            currentSprite().playEffect(Paths.EFFECT_HEAL);
            return "Player healed to " + Current.player().getLife() + "/" + Current.player().getMaxLife();
        });

        registerCommand(new String[]{"getShards", "amount"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: Amount";
            int value;
            try {
                value = Integer.parseInt(s[0]);
            } catch (Exception e) {
                return "Can not convert " + s[0] + " to integer";
            }
            Current.player().addShards(value);
            return "Player now has " + Current.player().getShards() + " shards";
        });
        registerCommand(new String[]{"debug", "map"}, s -> {
            GameHUD.getInstance().setDebug(true);
            return "Debug map ON";
        });
        registerCommand(new String[]{"debug", "off"}, s -> {
            GameHUD.getInstance().setDebug(false);
            currentGameStage().debugCollision(false);
            return "Debug map and collision OFF";
        });
        registerCommand(new String[]{"remove", "enemy", "all"}, s -> {
            if (!MapStage.getInstance().isInMap()) {
                WorldStage ws = WorldStage.getInstance();
                int enemiesCount = ws.enemies.size();
                for (int i = 0; i < enemiesCount; i++) {
                    ws.removeNearestEnemy();
                }
            } else {
                MapStage.getInstance().removeAllEnemies();
            }
            return "Removed all enemies";
        });
        // Round 299: collect every pickup on the current map level through the normal reward path (testing aid).
        registerCommand(new String[]{"take", "loot", "all"}, s -> {
            if (!MapStage.getInstance().isInMap())
                return "Only inside a map";
            return MapStage.getInstance().takeAllLoot();
        });

        registerCommand(new String[]{"hide"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: Amount";
            float value;
            try {
                value = Float.parseFloat(s[0]);
            } catch (Exception e) {
                return "Can not convert " + s[0] + " to float";
            }
            currentGameStage().hideFor(value);
            return "Hiding";
        });

        registerCommand(new String[]{"fly"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: Amount";
            float value;
            try {
                value = Float.parseFloat(s[0]);
            } catch (Exception e) {
                return "Can not convert " + s[0] + " to float";
            }
            currentGameStage().flyFor(value);
            return "Flying";
        });
        registerCommand(new String[]{"sprint"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: Amount";
            float value;
            try {
                value = Float.parseFloat(s[0]);
            } catch (Exception e) {
                return "Can not convert " + s[0] + " to float";
            }
            currentGameStage().sprintFor(value);
            return "removed all enemies";
        });
        registerCommand(new String[]{"remove", "enemy", "nearest"}, s -> {
            WorldStage.getInstance().removeNearestEnemy();
            return "removed all enemies";
        });
        registerCommand(new String[]{"remove", "enemy"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: Enemy map ID.";
            int id;
            try {
                id = Integer.parseInt(s[0]);
            } catch (Exception e) {
                return "Cannot convert " + s[0] + " to number";
            }
            if (!MapStage.getInstance().isInMap())
                return "Only supported for PoI";
            MapStage.getInstance().deleteObject(id);
            return "Removed enemy " + s[0];
        });
        // this is for test purposes unless you want to crack your items
        registerCommand(new String[]{"crack"}, s -> {
            ItemData itemData = Current.player().getRandomEquippedItem();
            String value = Current.player().isHardorInsaneDifficulty() ? "items" : "armor";
            String message = "Ok, no equipped " + value + " to crack... :)";
            if (itemData != null) {
                itemData.isCracked = true;
                Current.player().equip(itemData); //Unequipped the itemData
                InventoryScene.instance().clearItemDescription();
                message = itemData.name + " " + Forge.getLocalizer().getMessage("lblCracked");
            }
            return message;
        });
        registerCommand(new String[]{"set", "event"}, s -> {
            if(s.length < 1) return "Command needs 1 parameter: Block name or edition code. ";
            String blockName = s[0];
            if(MapStage.getInstance().findLocalInn() == null)
                return "Must be used within a town with an inn.";
            CardBlock eventCardBlock = FModel.getBlocks().find(b -> b.getName().equalsIgnoreCase(blockName));
            if(eventCardBlock == null) {
                CardEdition edition = FModel.getMagicDb().getEditions().find(e -> e.getCode().equalsIgnoreCase(blockName) || e.getName().equalsIgnoreCase(blockName));
                if(edition == null)
                    return "Unable to find edition or block: " + blockName;
                eventCardBlock = Aggregates.random(AdventureEventData.getValidDraftBlocks(List.of(edition)));
                if(eventCardBlock == null)
                    return "Unable to find a valid event block that exclusively contains edition " + edition.getName();
            }
            AdventureEventController.EventFormat eventFormat = s.length > 1 ? AdventureEventController.EventFormat.smartValueOf(s[1])
                    : eventCardBlock.getName().contains("Jumpstart") ? AdventureEventController.EventFormat.Jumpstart : AdventureEventController.EventFormat.Draft;
            if(eventFormat == null)
                return "Unknown event format: " + s[1];
            InnScene.replaceLocalEvent(eventFormat, eventCardBlock);
            return "Replaced local event with " + eventFormat.name() + " - " + eventCardBlock.getName();
        });
        // QC diagnostic (2026-08-13, user request: "hard for me to test... hoping you can have
        // some QC steps in the background") - dumps everything needed to verify the edition-
        // progression shard assignments on demand, without hunting forge.log for the individual
        // [TFR-ShopEditions]/[TFR-LootEditions]/[TFR-InnEditions] lines each action already prints.
        registerCommand(new String[]{"edition", "status"}, s -> {
            forge.adventure.world.World world = WorldSave.getCurrentSave().getWorld();
            if (!world.isEditionProgressionEnabled())
                return "Edition progression is not enabled for this plane/save.";
            StringBuilder sb = new StringBuilder("Edition progression status:\n");
            Map<String, List<String>> shards = world.getColorEditionShards();
            if (shards == null || shards.isEmpty()) {
                sb.append("  No shards seeded yet.\n");
            } else {
                for (String color : new String[]{"white", "blue", "black", "red", "green", forge.adventure.util.EditionProgression.NEUTRAL}) {
                    List<String> shard = shards.get(color);
                    sb.append("  ").append(color).append(" (").append(shard == null ? 0 : shard.size()).append("): ")
                            .append(shard == null ? "(none)" : String.join(", ", shard)).append("\n");
                }
            }
            java.util.Set<String> unlocked = Current.player().getUnlockedEditions();
            sb.append("  player-unlocked (").append(unlocked == null ? 0 : unlocked.size()).append("): ")
                    .append(unlocked == null || unlocked.isEmpty() ? "(none)" : String.join(", ", unlocked)).append("\n");
            // rootPoint is set on POI entry and never cleared on exit (2026-08-13 holistic
            // review) - without the isInMap() check, running this from the overworld reported the
            // LAST-visited POI as "current", with its territory color, exactly when the readout
            // matters most for QC.
            PointOfInterest rootPoint = forge.adventure.scene.TileMapScene.instance().rootPoint;
            if (rootPoint == null || !MapStage.getInstance().isInMap()) {
                sb.append("  Not currently at a PoI - no local restriction to report.\n");
            } else {
                String territoryColor = forge.adventure.util.TerritoryControl.currentColorAtPoi(world, rootPoint);
                sb.append("  current PoI: \"").append(rootPoint.getData().name).append("\" (type=")
                        .append(rootPoint.getData().type).append(", territory color=")
                        .append(territoryColor == null ? "(none)" : territoryColor).append(")\n");
            }
            System.out.println(sb);
            return sb.toString();
        });
        // One-shot save repair for the 2026-08-13 fully-explored bug (see MOD_SCOPE.md): rebuilds
        // fog-of-war exploration from actual ownership (owned ground + owned-town vision circles)
        // and re-arms the 80% full-reveal trigger. Opt-in because it also forgets walked ground.
        // Torch pulse (round 124, user request 2026-09-06): the Torch / Grand Torch's commandOnUse. The item's
        // shardsNeeded (1) is charged by the ability button / inventory Use button BEFORE this runs; the flare
        // itself is WorldBackground.pulseVision() (torchPulse* in settings.json). World map with fog of war only -
        // anywhere else the shard is handed back, like the Rally rune with nothing to rally to.
        registerCommand(new String[]{"torch", "pulse"}, s -> {
            forge.adventure.data.TuningData tuning = Config.instance().getTuningData();
            boolean inMap = MapStage.getInstance().isInMap();
            if (inMap || Current.world() == null || !Current.world().isFogOfWarEnabled()) {
                ItemData torch = ItemListData.getItem("Torch");
                int refund = torch != null && torch.shardsNeeded > 0 ? torch.shardsNeeded : 1;
                Current.player().addShards(refund);
                GameHUD.getInstance().addNotification(inMap ? "The torch only flares out on the world map."
                        : "There is no fog here for the torch to burn away.");
                System.out.println("[TFR-TorchPulse] refused (inMap=" + inMap + ") - " + refund + " shard(s) refunded");
                return "Torch pulse needs the world map with fog of war on - shards refunded";
            }
            int radius = WorldStage.getInstance().pulseVision(tuning.torchPulseMultiplier, tuning.torchPulseSeconds,
                    tuning.torchPulseMaxRadiusTiles);
            WorldStage.getInstance().player.playEffect(Paths.EFFECT_SPARKS, 1f);
            // Round 134 (user: "I do it a lot and don't need to see it each time. Maybe fire it the
            // first time."): the flare is obvious on screen, so the banner is only worth showing
            // once. A character flag rather than a session static, so it stays quiet across saves
            // and reloads for a character who has already seen it - and a New Game+ clears every
            // character flag, so a fresh run explains it again.
            if (Current.player().getCharacterFlag("torchPulseSeen") == 0) {
                Current.player().setCharacterFlag("torchPulseSeen", 1);
                GameHUD.getInstance().addNotification("The torch flares - the fog draws back for a moment.");
            }
            return "Torch pulse: vision flared to " + radius + " tiles";
        });
        // Round 336: the Bonfire kit - "bonfire place" builds a fire on the player's tile (World.addBonfire) that keeps the
        // fog lifted 15 tiles around, one tile less each day. Ten fires to a kit, one shard each (shardsNeeded); a spent
        // kit offers its rebuild (WorldStage.showBonfireRepairDialog). Every refusal hands the shard back.
        // Round 478: the lost-treasure hunts (util/TreasureHunt). "treasure dig" is the Spade's command (5 shards a dig, charged
        // by the item-use paths before this runs); a dig that cannot happen hands them back. The rest are test cheats.
        registerCommand(new String[]{"treasure", "dig"}, s -> {
            forge.adventure.world.World world = Current.world();
            if (!forge.adventure.util.TreasureHunt.isEnabled() || world == null)
                return "No treasure hunts here" + refundItemInUse("There is nothing to dig for in this world.");
            if (MapStage.getInstance().isInMap())
                return "Not inside a place" + refundItemInUse("Dig under the open sky, on the world map.");
            WorldStage stage = WorldStage.getInstance();
            int tx = stage.playerTileX(), ty = stage.playerTileY();
            int region = forge.adventure.util.TreasureHunt.treasureAt(world, tx, ty);
            int owner = forge.adventure.util.TreasureHunt.digOwner(world, tx, ty);
            if (region < 0 && forge.adventure.util.TreasureHunt.isClaimed(world, owner)) // round 480: nothing left to find
                return "Already claimed here" + refundItemInUse("The " + forge.adventure.util.TreasureHunt.REGION_NAMES[owner]
                        + " treasure is already yours - nothing else lies buried in these lands.");
            stage.player.playEffect(Paths.EFFECT_SPARKS, 0.5f);
            forge.adventure.util.TreasureHunt.addHole(world, tx, ty); // the user's dig art: a hole until its treasure is found
            if (region < 0) {
                GameHUD.getInstance().addNotification("You dig, and find nothing but dirt and stones.");
                System.out.println("[TFR-Treasure] dig at (" + tx + "," + ty + ") - nothing");
                return "Nothing here at " + tx + "," + ty;
            }
            System.out.println("[TFR-Treasure] dig at (" + tx + "," + ty + ") - the " + forge.adventure.util.TreasureHunt.REGION_NAMES[region]
                    + " treasure");
            if (!forge.adventure.util.TreasureHunt.startGuardian(world, region))
                return "The treasure is here but its guardian is missing" + refundItemInUse("Something is buried here, but nothing stirs.");
            return "The " + forge.adventure.util.TreasureHunt.REGION_NAMES[region] + " treasure's guardian rises";
        });
        // Round 484: test cheats for the town pillage (util/TownPillage) - start one now at the nearest town that could be
        // pillaged (the dungeon count waived), or list what is running and why the nearest towns qualify or not.
        registerCommand(new String[]{"pillage", "start"}, s -> {
            if (MapStage.getInstance().isInMap())
                return "Only on the world map";
            return forge.adventure.util.TownPillage.cheatStart(s.length > 0 ? String.join(" ", s) : null);
        });
        registerCommand(new String[]{"pillage", "info"}, s -> forge.adventure.util.TownPillage.cheatInfo());
        // Round 489: the Armory storage screen without a Capitol Armory - for testing its layouts.
        registerCommand(new String[]{"armory", "open"}, s -> {
            if (Forge.getCurrentScene() instanceof forge.adventure.scene.DuelScene)
                return "Not during a duel.";
            forge.adventure.scene.ArmoryScene.instance().open(null);
            return "Armory storage opened";
        });
        // Round 493: Ascendance test cheats - "asc give <power>" (as an award, times the levelingSpeed), "asc set <level>"
        // (straight there, each level's rewards paid), "asc info".
        registerCommand(new String[]{"asc", "give"}, s -> {
            try {
                return forge.adventure.util.Ascendance.cheatGive(s.length > 0 ? Integer.parseInt(s[0]) : 100);
            } catch (NumberFormatException e) {
                return "Can not convert " + s[0] + " to number";
            }
        });
        registerCommand(new String[]{"asc", "set"}, s -> {
            try {
                return forge.adventure.util.Ascendance.cheatSetLevel(s.length > 0 ? Integer.parseInt(s[0]) : 1);
            } catch (NumberFormatException e) {
                return "Can not convert " + s[0] + " to number";
            }
        });
        registerCommand(new String[]{"asc", "info"}, s -> forge.adventure.util.Ascendance.cheatInfo());
        // Round 494: "asc choose" opens the waiting reward (or the status) as the HUD panel's tap does; "asc pick <id>"
        // takes that reward now (ids in ascendance.json "choices") - for testing each one.
        registerCommand(new String[]{"asc", "choose"}, s -> {
            forge.adventure.util.AscendanceUI.openFromHud();
            return "Ascendance dialog opened (" + forge.adventure.util.Ascendance.pendingChoices() + " waiting)";
        });
        registerCommand(new String[]{"asc", "pick"}, s -> {
            if (s.length < 1)
                return "Command needs 1 parameter: a reward id from ascendance.json";
            String result = forge.adventure.util.Ascendance.cheatPick(s[0]);
            return result == null ? "Could not take " + s[0] : result;
        });
        // Round 490: test cheats for roaming guards on pillage duty. "guard add [tier] [help]" hires a guard carrying a COPY
        // of the selected deck (cheat cards: they come home on a dismissal), "help" ticking its "Help with pillaged towns"
        // order; "guard orders" opens the Capitol's Guards dialog over the current menu scene (after "armory open").
        registerCommand(new String[]{"guard", "add"}, s -> {
            if (!forge.adventure.util.RoamingGuards.isEnabled())
                return "Roaming guards are off in this plane";
            String tier = "Rare";
            for (String t : forge.adventure.util.RoamingGuards.TIERS_ASCENDING)
                if (s.length > 0 && (t.equalsIgnoreCase(s[0]) || forge.adventure.util.RoamingGuards.displayName(t).equalsIgnoreCase(s[0])))
                    tier = t;
            forge.deck.Deck deck = Current.player().getSelectedDeck();
            if (deck == null || deck.getMain().countAll() == 0)
                return "Select a deck first";
            forge.adventure.data.RoamingGuardData guard = forge.adventure.util.RoamingGuards.hire(tier, Current.world().getCurrentDay());
            guard.deckName = deck.getName();
            guard.deckCards = deck.getMain().toCardList("\n").split("\n");
            guard.helpPillage = s.length > 1 && "help".equalsIgnoreCase(s[1]);
            return forge.adventure.util.RoamingGuards.displayName(tier) + " guard hired with \"" + deck.getName() + "\""
                    + (guard.helpPillage ? ", helping with pillaged towns" : "");
        });
        registerCommand(new String[]{"guard", "orders"}, s -> {
            if (!(Forge.getCurrentScene() instanceof forge.adventure.scene.UIScene))
                return "Open a menu scene first (armory open)";
            PointOfInterest capitol = forge.adventure.util.RoamingGuards.capitol();
            forge.adventure.util.RoamingGuardUI.openGuardChooser((forge.adventure.scene.UIScene) Forge.getCurrentScene(),
                    capitol == null ? null : WorldSave.getCurrentSave().getPointOfInterestChanges(capitol.getID()),
                    forge.adventure.util.TownRestoration.CAPITOL_POI_NAME, 0);
            return "Guards dialog opened";
        });
        registerCommand(new String[]{"pillage", "beaten"}, s -> {
            try {
                return forge.adventure.util.TownPillage.cheatSetBeaten(s.length > 0 ? Integer.parseInt(s[0]) : 0);
            } catch (NumberFormatException e) {
                return "Can not convert " + s[0] + " to number";
            }
        });
        // Round 488: a Map Fragment item's use (items.json "treasure piece <Region>") - one more piece of that map. Used
        // up only when the piece took; otherwise it stays in the bag and the player is told why.
        registerCommand(new String[]{"treasure", "piece"}, s -> {
            if (s.length < 1)
                return "Command needs 1 parameter: Region (Wastes, White, Blue, Black, Red, Green)";
            String refusal = forge.adventure.util.TreasureHunt.usePiece(Current.world(), s[0]);
            if (refusal != null) {
                GameHUD.getInstance().addNotification(refusal);
                return refusal;
            }
            if (itemInUse != null)
                Current.player().removeItem(itemInUse);
            return "The " + s[0] + " map has one more piece";
        });
        registerCommand(new String[]{"treasure", "info"}, s -> {
            forge.adventure.world.World world = Current.world();
            return world == null ? "No world" : forge.adventure.util.TreasureHunt.describe(world);
        });
        registerCommand(new String[]{"treasure", "fragments"}, s -> {
            if (s.length < 2)
                return "Command needs 2 parameters: Region (Wastes, White, Blue, Black, Red, Green) and a count 0-9";
            forge.adventure.world.World world = Current.world();
            try {
                return forge.adventure.util.TreasureHunt.setFragments(world, s[0], Integer.parseInt(s[1]));
            } catch (NumberFormatException e) {
                return "Can not convert " + s[1] + " to number";
            }
        });
        registerCommand(new String[]{"treasure", "obelisk"}, s -> {
            if (s.length < 1)
                return "Command needs 1 parameter: Region (Wastes, White, Blue, Black, Red, Green)";
            if (MapStage.getInstance().isInMap())
                return "Only on the world map";
            return forge.adventure.util.TreasureHunt.obeliskHere(Current.world(), s[0]);
        });
        registerCommand(new String[]{"treasure", "map"}, s -> {
            int region = s.length > 0 ? forge.adventure.util.TreasureHunt.regionIndex(s[0]) : -1;
            forge.adventure.scene.TreasureMapScene.show(region);
            return "Treasure maps opened" + (region >= 0 ? " at the " + forge.adventure.util.TreasureHunt.REGION_NAMES[region] + " map" : "");
        });
        registerCommand(new String[]{"treasure", "here"}, s -> {
            if (s.length < 1)
                return "Command needs 1 parameter: Region (Wastes, White, Blue, Black, Red, Green)";
            if (MapStage.getInstance().isInMap())
                return "Only on the world map";
            return forge.adventure.util.TreasureHunt.treasureHere(Current.world(), s[0]);
        });
        registerCommand(new String[]{"bonfire", "place"}, s -> {
            boolean inMap = MapStage.getInstance().isInMap();
            forge.adventure.world.World world = Current.world();
            if (inMap || world == null || !world.isFogOfWarEnabled())
                return "A bonfire needs the world map with fog of war on" + refundItemInUse(inMap
                        ? "A bonfire is built under the open sky - not in here." : "There is no fog here for a bonfire to hold back.");
            ItemData kit = itemInUse != null ? itemInUse : ItemListData.getItem("Bonfire");
            forge.adventure.player.AdventurePlayer player = Current.player();
            WorldStage stage = WorldStage.getInstance();
            if (kit != null && kit.uses > 0 && player.usesLeft(kit) <= 0) {
                String note = refundItemInUse("The bonfire kit is spent - rebuild it for " + kit.repairShards + " [+Shards].");
                stage.showBonfireRepairDialog(kit);
                return "Bonfire kit spent" + note;
            }
            int[] near = world.liveBonfireNear(stage.playerTileX(), stage.playerTileY(), 3);
            if (near != null)
                return "A fire already burns here" + refundItemInUse("A fire already burns here - move a few tiles on.");
            if (kit != null)
                player.spendUse(kit);
            int[] fire = stage.placeBonfire();
            stage.player.playEffect(Paths.EFFECT_SPARKS, 1f);
            int left = kit == null ? -1 : player.usesLeft(kit);
            GameHUD.getInstance().addNotification("You build a bonfire. The fog draws back " + forge.adventure.world.World.BONFIRE_RADIUS
                    + " tiles around it, one tile less each day." + (left >= 0 ? " Fires left in the kit: " + left + "." : ""));
            System.out.println("[TFR-Bonfire] built at (" + fire[0] + "," + fire[1] + ") on day " + fire[2] + ", radius "
                    + forge.adventure.world.World.BONFIRE_RADIUS + ", kit fires left " + left);
            return "Bonfire built at " + fire[0] + "," + fire[1] + (left >= 0 ? ", " + left + " fire(s) left in the kit" : "");
        });
        // Round 340 (user: "yin/yan rune... When you use it, 1 shard. half the rune will drop on the ground. then when you
        // use it again, you will teleport to the spot you dropped it. Should work on overworld only."): the first use sets
        // the light half down on the player's tile (World.yinYangAnchor, drawn by WorldStage - round 349: "leave the white
        // half on the ground", the rune's icon shows the dark half meanwhile), the second brings the player
        // back to it and picks it up. Inside a place, or with nowhere walkable left at the half, the shard comes back.
        registerCommand(new String[]{"yinyang"}, s -> {
            if (MapStage.getInstance().isInMap() || Current.world() == null)
                return "The Yin-Yang rune works on the world map only" + refundItemInUse("The rune answers only under the open sky.");
            WorldStage stage = WorldStage.getInstance();
            if (Current.world().getYinYangAnchor() == null) {
                int[] tile = stage.setYinYangHalf();
                stage.player.playEffect(Paths.EFFECT_SPARKS, 1f);
                GameHUD.getInstance().addNotification("You set the light half of the rune down here and keep the dark one. Use the rune again to return to it.");
                System.out.println("[TFR-YinYang] half set down at (" + tile[0] + "," + tile[1] + ")");
                return "Yin-Yang half set down at " + tile[0] + "," + tile[1];
            }
            int[] landing = stage.returnToYinYangHalf();
            if (landing == null)
                return "Nowhere to land at the half" + refundItemInUse("Something has grown over the rune's other half - it cannot draw you there.");
            stage.player.playEffect(Paths.EFFECT_TELEPORT, 10);
            GameHUD.getInstance().addNotification("The halves rejoin - you stand where you left the other.");
            System.out.println("[TFR-YinYang] returned to (" + landing[0] + "," + landing[1] + "), the half taken up");
            return "Returned to the Yin-Yang half at " + landing[0] + "," + landing[1];
        });
        // Round 340 (testing aid): shows the named item glyphs on a banner - which of the items atlas pages the HUD font
        // can draw. "glyph test YinYangRune Bonfire" -> "[+YinYangRune] [+Bonfire]".
        registerCommand(new String[]{"glyph", "test"}, s -> {
            StringBuilder b = new StringBuilder("[BLACK]glyphs:");
            for (String name : s)
                b.append(" ").append(name).append("=[+").append(name).append("]");
            GameHUD.getInstance().addNotification(b.toString(), true);
            return "shown: " + b;
        });
        registerCommand(new String[]{"fog", "reset"}, s ->
                WorldSave.getCurrentSave().getWorld().resetFogOfWarToOwnership());
        // TESTING ONLY (user request 2026-08-14) - REMOVE once the Color Defeat mechanic
        // (MOD_SCOPE.md #61) has been playtested and confirmed working. The real trigger is
        // clearing one of the 5 castle boss fights, which is deliberately very difficult - this
        // fires the exact same consequence without needing to actually beat one first. Best-effort
        // "completes the quest" too: writes the real Ch1<Color>CastleComplete flag onto the
        // castle's own POI and fires the same notification the boss-defeat dialog action fires,
        // not just the downstream territory/reputation/mage-cap side effects.
        registerCommand(new String[]{"defeat", "castle"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: Color (white/blue/black/red/green).";
            String color = s[0].toLowerCase();
            boolean known = false;
            for (String c : TerritoryControl.COLORS)
                if (c.equals(color)) { known = true; break; }
            if (!known) return "Unknown color \"" + s[0] + "\" - use white, blue, black, red or green.";
            World world = WorldSave.getCurrentSave().getWorld();
            if (world.isColorDefeated(color))
                return "\"" + color + "\" is already defeated.";
            String flagName = TerritoryControl.castleCompleteFlagName(color);
            if (flagName == null)
                return "Could not resolve a castle-complete flag for \"" + color + "\".";
            // Calls the EXACT same method the real boss-defeat dialog action calls (Current.player().
            // setQuestFlag(), confirmed via MapDialog.java's "setQuestFlag" action-key dispatcher) -
            // fixed 2026-08-14 after adversarial review caught the original version manually
            // replicating a DIFFERENT, wrong code path (MapStage.setQuestFlag(), which backs the
            // unrelated "setMapFlag" action key and was never what the real dialog actually fires).
            // This one call now exercises the real trigger hook end to end, not a parallel bypass.
            Current.player().setQuestFlag(flagName, 1);
            String capitalized = Character.toUpperCase(color.charAt(0)) + color.substring(1);
            return capitalized + "'s castle marked defeated - terrain reverted, consequences applied. Check forge.log for [TFR-ColorDefeat].";
        });
        registerCommand(new String[]{"reset", "map"}, s -> {
            if(!MapStage.getInstance().isInMap()) {
                return "Can only be used in maps.";
            }

            MapStage.getInstance().clearOnExit();
            
            return "Exit the map to reset it.";
        });
    }
}
