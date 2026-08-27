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

    static class Command {
        HashMap<String, Command> children = new HashMap<>();
        Function<String[], String> function;
    }

    public String complete(String text) {
        String[] words = splitOnSpace(text);
        Command currentCommand = root;
        StringBuilder completionString = new StringBuilder();
        for (String name : words) {
            if (!currentCommand.children.containsKey(name)) {
                for (String key : currentCommand.children.keySet()) {
                    if (key.startsWith(name)) {
                        return completionString + key + " ";
                    }
                }
                break;
            }
            completionString.append(name).append(" ");
            currentCommand = currentCommand.children.get(name);
        }
        return text;
    }

    private String[] splitOnSpace(String text) {
        List<String> matchList = new ArrayList<>();
        Pattern regex = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'");
        Matcher regexMatcher = regex.matcher(text);
        while (regexMatcher.find()) {
            if (regexMatcher.group(1) != null) {
                matchList.add(regexMatcher.group(1));
            } else if (regexMatcher.group(2) != null) {
                matchList.add(regexMatcher.group(2));
            } else {
                matchList.add(regexMatcher.group());
            }
        }
        return matchList.toArray(new String[0]);
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
            if (poi == null)
                return "PoI " + s[0] + " not found";

            Forge.advFreezePlayerControls = true;
            FThreads.invokeInEdtNowOrLater(() -> Forge.setTransitionScreen(new CoverScreen(() -> {
                Forge.advFreezePlayerControls = false;
                WorldStage.getInstance().setPosition(new Vector2(poi.getPosition().x - 16f, poi.getPosition().y + 16f));
                WorldStage.getInstance().loadPOI(poi);
                Forge.clearTransitionScreen();
            }, Forge.takeScreenshot())));
            return "Teleported to " + s[0] + "(" + poi.getPosition() + ")";
        });
        // Colorless rune (MOD_CHANGELOG.md 2026-08-22, user request: "take you to the spawn area,
        // until you have a Capitol, then take you to just outside the cap"). Before a Capitol
        // exists, reproduces the item's original "teleport to poi Spawn" behavior exactly (enters
        // Spawn's interior). Once TownRestoration.capitolExists(), goes to the Capitol instead -
        // deliberately position-only (no loadPOI(), same as the raw "teleport to X Y" command
        // above) so the player lands just outside the Capitol on the overworld rather than being
        // dropped inside it, per the user's explicit "just outside" wording.
        registerCommand(new String[]{"teleport", "home"}, s -> {
            PointOfInterest capitol = TownRestoration.findCapitol();
            if (capitol == null) {
                PointOfInterest spawn = Current.world().findPointsOfInterest("Spawn");
                if (spawn == null)
                    return "PoI Spawn not found";

                Forge.advFreezePlayerControls = true;
                FThreads.invokeInEdtNowOrLater(() -> Forge.setTransitionScreen(new CoverScreen(() -> {
                    Forge.advFreezePlayerControls = false;
                    WorldStage.getInstance().setPosition(new Vector2(spawn.getPosition().x - 16f, spawn.getPosition().y + 16f));
                    WorldStage.getInstance().loadPOI(spawn);
                    Forge.clearTransitionScreen();
                }, Forge.takeScreenshot())));
                return "Teleported to Spawn(" + spawn.getPosition() + ")";
            }
            WorldStage.getInstance().setPosition(new Vector2(capitol.getPosition().x - 16f, capitol.getPosition().y + 16f));
            WorldStage.getInstance().player.playEffect(Paths.EFFECT_TELEPORT, 10);
            return "Teleported outside the Capitol(" + capitol.getPosition() + ")";
        });
        registerCommand(new String[]{"spawn", "enemy"}, s -> {
            if (s.length < 1) return "Command needs 1 parameter: enemy name.";

            if (WorldStage.getInstance().spawn(s[0]))
                return "Spawn " + s[0];
            return "Can not find enemy " + s[0];
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
