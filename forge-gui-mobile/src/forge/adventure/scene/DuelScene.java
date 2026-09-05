package forge.adventure.scene;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Array;
import com.google.common.collect.ImmutableList;
import forge.Adventure;
import forge.Forge;
import forge.Graphics;
import forge.LobbyPlayer;
import forge.card.CardRarity;
import forge.card.CardRenderer;
import forge.card.CardRenderer.CardStackPosition;
import forge.card.CardZoom;
import forge.adventure.character.EnemySprite;
import forge.adventure.character.PlayerSprite;
import forge.adventure.data.*;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.IAfterMatch;
import forge.adventure.stage.MapStage;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.util.AdventureEventController;
import forge.adventure.util.ColorReputation;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.util.SpawnTierWeighting;
import forge.adventure.util.TownRestoration;
import forge.adventure.world.WorldSave;
import forge.assets.FBufferedImage;
import forge.assets.FSkin;
import forge.card.ColorSet;
import forge.deck.*;
import forge.game.card.CardView;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.GameOutcome;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.quest.QuestUtil;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.gui.FThreads;
import forge.gui.interfaces.IGuiGame;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.player.GamePlayerUtil;
import forge.player.PlayerControllerHuman;
import forge.screens.FScreen;
import forge.screens.LoadingOverlay;
import forge.screens.TransitionScreen;
import forge.screens.match.MatchController;
import forge.sound.MusicPlaylist;
import forge.sound.SoundSystem;
import forge.toolbox.FCardPanel;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FOptionPane;
import forge.trackable.TrackableCollection;
import forge.util.Aggregates;
import forge.util.Localizer;
import forge.util.ScreenUtil;
import forge.util.StreamUtil;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;

/**
 * DuelScene
 * Forge screen scene that contains the duel screen
 */
public class DuelScene extends ForgeScene {
    private static DuelScene object;

    public static DuelScene instance() {
        if (object == null)
            object = new DuelScene();
        return object;
    }

    //GameLobby lobby;
    HostedMatch hostedMatch;
    EnemySprite enemy;
    PlayerSprite player;
    RegisteredPlayer humanPlayer;
    private EffectData dungeonEffect;
    Deck playerDeck;
    boolean chaosBattle = false;
    boolean callbackExit = false;
    boolean arenaBattleChallenge = false;
    boolean isArena = false;
    AdventureEventData eventData;
    // Deck Tester "Simulated" mode (2026-08-13) - true only for a fully-AI-vs-AI test duel, see
    // the initDuels() overload's own comment.
    boolean aiControlsPlayerSide = false;
    final int enemyAvatarKey = 90001;
    final int playerAvatarKey = 90000;
    FOptionPane bossDialogue;
    List<IPaperCard> playerExtras = new ArrayList<>();
    List<IPaperCard> AIExtras = new ArrayList<>();


    private DuelScene() {
    }


    @Override
    public void dispose() {
    }

    public boolean hasCallbackExit() {
        return callbackExit;
    }

    public void GameEnd() {
        //TODO: Progress towards applicable Adventure quests also needs to be reported here.
        if (eventData != null)
            eventData.nextOpponent = null;
        boolean winner = false;
        List<PaperCard> anteWonCards = Collections.emptyList();
        List<PaperCard> anteLostCards = Collections.emptyList();
        try {
            // Defensive null-check (2026-08-13, found via the user's own forge.log after
            // extended play - a real, reproducible NPE, not the different one already fixed
            // above). hostedMatch.getGame() can already be null by the time actionOnQuit() ->
            // GameEnd() runs, specifically observed for Deck Tester matches: HostedMatch.
            // endCurrentGame() nulls its game field once the match's own auto-decision
            // resolves, which can race ahead of the player's win/lose-screen click for a
            // noAnte/single-game/no-rewards match like Deck Tester. Without this guard, the
            // exception aborted this ENTIRE try block at its very first line - not just the
            // winner computation, but shard persistence and ante handling too - and printed a
            // full stack trace every single time it happened (confirmed twice in one session).
            // winner correctly stays at its already-initialized false default here - Deck
            // Tester's own deckTesterMatch guard in ArenaScene.setWinner() doesn't track win/
            // loss meaningfully anyway (just resets UI state, per its own comment).
            if (hostedMatch.getGame() != null) {
                winner = humanPlayer == hostedMatch.getGame().getMatch().getWinner();
            } else {
                System.out.println("[TFR-DuelEndRace] hostedMatch.getGame() was already null in GameEnd() - match already torn down before this callback ran, winner defaults false");
            }

            //Persists expended (or potentially gained) shards back to Adventure
            if (eventData == null || eventData.eventRules.allowsShards) {
                List<PlayerControllerHuman> humans = hostedMatch.getHumanControllers();
                {
                    // Deck Tester "Simulated" mode (2026-08-13, adversarial review finding):
                    // with BOTH duel seats AI-controlled, HostedMatch's humanCount==0 branch
                    // registers exactly one spectator controller (WatchLocalGame) into this same
                    // humanControllers list - so humans.size()==1 is still true, but its
                    // getPlayer() is null (a spectator, not a seated player), and
                    // getNumManaShards() would NPE. Harmless before this null-check (the
                    // surrounding try/catch already swallowed it), but printed a stack trace on
                    // every single Simulated match - this codebase's own standing practice is
                    // clean, greppable diagnostics, not a guaranteed-every-time caught exception.
                    if (humans.size() == 1 && humans.get(0).getPlayer() != null) {
                        Current.player().setShards(humans.get(0).getPlayer().getNumManaShards());
                    }
                }
            }

            // Mostly for ante handling, but also blacker lotus
            GameOutcome.AnteResult anteResult = hostedMatch.getAnteResult(humanPlayer);
            // Diagnostic (2026-08-21 dungeon buy-back report): two clean session logs showed
            // GameEnd running with NO ante processing after ante fights lost in dungeons.
            // Match.getAnteResult() can never return null (it aggregates into a fresh
            // accumulator), so the skip means EMPTY results - which leaves exactly two
            // suspects this line discriminates: the outcome map not containing our
            // humanPlayer key (identity mismatch -> "humanNotFound"), or the engine not
            // flagging the human as the loser (hasLost=false on a lost duel).
            String matchProbe = "game=null";
            if (hostedMatch.getGame() != null) {
                forge.game.player.Player humanGamePlayer = null;
                for (forge.game.player.Player p : hostedMatch.getGame().getPlayers()) {
                    if (p.getRegisteredPlayer() == humanPlayer) {
                        humanGamePlayer = p;
                        break;
                    }
                }
                matchProbe = humanGamePlayer == null ? "humanNotFound"
                        : "hasLost=" + humanGamePlayer.hasLost()
                        + " anteZone=" + humanGamePlayer.getCardsIn(forge.game.zone.ZoneType.Ante).size();
            }
            System.out.println("[TFR-AnteResult] raw=" + (anteResult == null ? "null"
                    : "won=" + anteResult.wonCards.size() + " lost=" + anteResult.lostCards.size())
                    + " " + matchProbe);
            if (anteResult != null) {
                if (eventData != null) {
                    //In an event. Apply the ante result to the current event deck.
                    eventData.registeredDeck.getOrCreate(DeckSection.Sideboard).add(anteResult.wonCards);
                    if(eventData.rewardDeck != null)
                        eventData.rewardDeck.getOrCreate(DeckSection.Sideboard).add(anteResult.wonCards);
                    for(PaperCard card : anteResult.lostCards) {
                        eventData.registeredDeck.removeAnteCard(card);
                        if(eventData.rewardDeck != null)
                            eventData.rewardDeck.removeAnteCard(card);
                    }
                    //Could also add the cards to the opponent's pool, but their games aren't simulated and they never edit their decks.
                }
                else {
                    for (PaperCard card : anteResult.wonCards) {
                        Current.player().addCard(card);
                    }
                    for (PaperCard card : anteResult.lostCards) {
                        // We could clean this up by trying to combine all the lostCards into a mapping, but good enough for now
                        Current.player().removeLostCardFromPools(card);
                    }
                }
                anteWonCards = new ArrayList<>(anteResult.wonCards);
                anteLostCards = new ArrayList<>(anteResult.lostCards);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        String enemyName = enemy.getName();
        String insult = enemy.getBossInsult();
        boolean showMessages = enemy.getData().boss || (enemy.getData().copyPlayerDeck && Current.player().isUsingCustomDeck());
        Current.player().clearBlessing();
        // Color reputation (MOD_SCOPE.md #1) Partner-tier Inn overheal: "used up" by the next duel
        // regardless of outcome, same funnel/timing as clearBlessing() above.
        Current.player().clearPartnerOverhealIfActive();

        boolean finalWinner = winner;
        boolean isBossLoss = (chaosBattle || showMessages) && !finalWinner;
        boolean hasAnteResults = !anteWonCards.isEmpty() || !anteLostCards.isEmpty();
        // Diagnostic (2026-08-21 user report: "lost in a dungeon and got kicked out without the
        // opportunity to buy back my ante" while the same flow worked on the overworld) - one
        // line per duel end with everything the popup decision depends on, so the next
        // occurrence pinpoints which link skipped: no ante result at all (exception above /
        // noAnte fight), empty lostCards, or popups queued but never shown.
        System.out.println("[TFR-AnteResult] winner=" + finalWinner + " won=" + anteWonCards.size()
                + " lost=" + anteLostCards.size() + " inMap=" + MapStage.getInstance().isInMap()
                + " event=" + (eventData != null) + " bossLoss=" + isBossLoss);

        // No popups needed, preserve original behavior
        if (!hasAnteResults && !isBossLoss) {
            afterGameEnd(enemyName, finalWinner);
            return;
        }

        // Build popup chain: ante results -> boss dialogue -> exit
        callbackExit = true;
        Runnable exitChain = () -> {
            afterGameEnd(enemyName, finalWinner);
            exitDuelScene();
        };

        Runnable afterAnte;
        if (isBossLoss) {
            afterAnte = () -> {
                final FBufferedImage fb = getFBEnemyAvatar();
                String bossInsultMsg = insult != null ? insult
                        : Forge.getLocalizer().getMessage("AdvBossInsult" + Aggregates.randomInt(1, 44));
                // Tiered title matches the boss INTRO dialog (2026-08-13 holistic review - the
                // intro was tiered but this loss dialog still showed the raw name). enemyName
                // itself stays raw above: it's the statistics identity key in afterGameEnd().
                bossDialogue = createFOption(bossInsultMsg,
                        enemy.getTieredDisplayName(), fb, () -> {
                            exitChain.run();
                            fb.dispose();
                        });
                FThreads.invokeInEdtNowOrLater(() -> bossDialogue.show());
            };
        } else {
            afterAnte = exitChain;
        }

        if (hasAnteResults) {
            showAnteResults(anteWonCards, anteLostCards, afterAnte);
        } else {
            afterAnte.run();
        }
    }

    Runnable endRunnable = null;

    void afterGameEnd(String enemyName, boolean winner) {
        // Color reputation (MOD_SCOPE.md #1): every ordinary duel WIN shifts the player's
        // standing across the 5-color wheel. This is the single funnel every duel's end passes
        // through, and the only place all three exclusions are cheaply knowable at once: losses
        // (winner false), Arena brackets (isArena), and Inn tournaments (eventData != null).
        // No-op unless the plane's config enables colorReputationEnabled; colorless enemies
        // no-op inside the call. Deliberately outside the endRunnable below - that only runs
        // once the transition screen finishes, and reputation has no rendering dependency.
        if (winner && !isArena && eventData == null && enemy != null) {
            ColorReputation.onPlayerWonDuel(enemy.getData(), enemy.territoryColor != null);
            // Town Reputation (user spec 2026-08-17): defeating an attacking mage grants +1
            // reputation with the town it was attacking - only when that target is a player-
            // owned/restored town, since this mechanic "only affects the player's towns" (an
            // AI-vs-AI or neutral-town mage fight the player happens to intercept shouldn't
            // grant player-town standing).
            if (enemy.territoryColor != null && enemy.territoryTarget != null) {
                PointOfInterestChanges targetChanges = WorldSave.getCurrentSave()
                        .getPointOfInterestChanges(enemy.territoryTarget.getID());
                if (TownRestoration.isTownRestored(targetChanges))
                    targetChanges.addMapReputation(1);
            }
        }
        Forge.advFreezePlayerControls = winner;
        endRunnable = () -> Gdx.app.postRunnable(() -> {
            GameHUD.getInstance().updateBGM();
            dungeonEffect = null;
            callbackExit = false;
            Forge.clearTransitionScreen();
            Forge.clearScreenStack();
            Forge.advFreezePlayerControls = false;
            Scene last = Forge.switchToLast();
            // Deck Tester matches (all modes) are documented as consequence-free - no rewards, no
            // reputation, no bracket - but previously still wrote a win/loss row into the player's
            // permanent duel statistics under "Deck Tester" (2026-08-13 holistic review; in Watch
            // mode the recorded result was whatever the AI-piloted seat happened to do).
            // EnemyData.fixedDeck is only ever set on Deck Tester's synthetic per-fight clone
            // (see its own field comment), making it the reliable discriminator here.
            // Inn tournament matches are excluded alongside Deck Tester (user decision
            // 2026-08-29): a tournament is played with eventData.registeredDeck - the sealed/draft
            // deck the EVENT built (see initDuels: playerDeck is overwritten with a copy of it) -
            // not the player's own adventure deck, so those results say nothing about how the
            // player's real deck fares against that enemy. They were also being DOUBLE-COUNTED:
            // PlayerStatistic already tracks every event match independently via completedEvents
            // (eventMatchWins()/eventMatchLosses() sum each event's own matchesWon/matchesLost),
            // while this line additionally folded them into winLossRecord - the same map that
            // totalWins()/totalLoss()/winLossRatio() sum. Two knock-on effects that also stop
            // now: PlayerStatistic.rank() (overworld spawn difficulty) reads those summed wins, so
            // grinding tournaments was quietly making the overworld harder, and
            // AdventurePlayer's sell-price formula scales off winLossRatio().
            // Arena is deliberately NOT excluded here - it runs on the player's own deck
            // (eventData == null), so it belongs in the record.
            // Bronze Coin ante ransom reclaim (user spec 2026-08-29, user decision on the
            // trigger: the coin comes back when you DEFEAT that enemy again, not merely meet
            // them). Keyed on the same raw enemyName the statistics record uses, so it survives
            // tiered display names. Arena is deliberately allowed to reclaim (only PAYING is
            // restricted to ordinary duels) - beating the enemy that holds your coin should
            // return it wherever that rematch happens.
            //
            // The reclaim itself MOVED OUT of this scene on 2026-09-01. It used to grant the item
            // right here, which worked but was invisible: the player saw the loot screen, no coin
            // on it, and had to open the inventory to discover the mechanic had fired at all
            // ("there was nothing that told me i got it back"). It is now appended as a loot tile
            // by whichever payout site this win reaches - WorldStage.setWinner (overworld),
            // MapStage.getReward (dungeon/town) or ArenaScene.done (bracket payout) - each of
            // which clears the mark and adds the tile in the same statement, so the two cannot
            // drift apart. Nothing is done here beyond leaving the mark standing for them.
            if ((enemy == null || enemy.getData().fixedDeck == null) && eventData == null) {
                Current.player().getStatistic().setResult(enemyName, winner);
                // Weighted spawn tier system, Layer 3 (2026-08-23) - same guarded funnel as the
                // win/loss record above (Deck Tester + tournaments excluded, only a confirmed win
                // registers). Tournaments are excluded for the same reason: registerKill() decays
                // an enemy's future OVERWORLD spawn share, which a win piloting an event-built
                // deck shouldn't drive.
                // enemy can be null here (see the guard above); WorldData.getEnemy() resolves the
                // EnemyData by name in that case - registerKill() itself is null-safe either way.
                if (winner)
                    SpawnTierWeighting.registerKill(enemy != null ? enemy.getData() : WorldData.getEnemy(enemyName));
            }

            if (last instanceof IAfterMatch) {
                ((IAfterMatch) last).setWinner(winner, isArena);
            }
        });
    }

    public void exitDuelScene() {
        Forge.setTransitionScreen(new TransitionScreen(endRunnable, ScreenUtil.getInstance().takeScreenshot(), false, false));
    }

    private FOptionPane createFOption(String message, String title, FBufferedImage icon, Runnable runnable) {
        return new FOptionPane(message, null, title, icon, null, ImmutableList.of(Forge.getLocalizer().getMessage("lblOK")), -1, result -> {
            if (runnable != null)
                runnable.run();
        });
    }

    /**
     * Ante cards already recovered by an individual Buy Back during THIS loss, so a later
     * whole-ante Bronze Coin ransom does not hand them over a second time.
     * <p>
     * 2026-09-01 release review. The two recovery routes are offered on the SAME popup and are not
     * mutually exclusive across cards: with a 2-card ante the player can Buy Back card A on popup
     * 1, then choose Use Bronze Coin on popup 2 - and payCoinRansomForAll() runs over the FULL
     * lost list, so A was added to the collection and to the deck twice, while the buy-back gold
     * stayed spent. DuelScene is a singleton, so this must be cleared per duel, not per instance.
     */
    private final List<PaperCard> anteAlreadyRecovered = new ArrayList<>();

    private void showAnteResults(List<PaperCard> wonCards, List<PaperCard> lostCards, Runnable onDone) {
        anteAlreadyRecovered.clear();
        // Show won cards one at a time, then lost cards, then continue
        showAnteCardsSequentially(wonCards, 0, true, () ->
            showAnteCardsSequentially(lostCards, 0, false, onDone));
    }

    private void showAnteCardsSequentially(List<PaperCard> cards, int index, boolean won, Runnable onDone) {
        if (index >= cards.size()) {
            onDone.run();
            return;
        }
        PaperCard card = cards.get(index);
        Runnable next = () -> showAnteCardsSequentially(cards, index + 1, won, onDone);
        // Bronze Coin ante ransom (user spec 2026-08-29). ONE coin recovers the whole ante, not
        // one card - the user called this out explicitly ("Best out of 3 matches, you'd get both
        // back, so take that into account"). So paying it here refunds every card in this loss's
        // list and jumps straight to onDone, skipping the remaining per-card popups: there is
        // nothing left to decide about them, and showing "Card Lost" for a card just recovered
        // would be a lie.
        Runnable ransomAll = won ? null : () -> {
            payCoinRansomForAll(cards);
            onDone.run();
        };
        showAnteCardPopup(won ? "Card Gained" : "Card Lost", card, won, next, ransomAll);
    }

    /**
     * Is the Bronze Coin ransom offered for this loss at all? Ordinary duels only (user spec):
     * never in Inn tournaments or Arena brackets - those already have their own entry-fee and
     * bracket economies - and never against a boss, so the mechanic can't be used to trivialize
     * a set-piece fight. NOTE: `boss` is the only "this is a special enemy" flag EnemyData
     * carries (78 entries); there is no separate "unique" marker to also exclude on.
     */
    private boolean coinRansomEligible() {
        return eventData == null && !isArena
                && enemy != null && !enemy.getData().boss
                && Current.player().hasItem(AdventurePlayer.BRONZE_COIN_ITEM)
                // ONE COIN PER ENEMY (user spec 2026-08-31). coinRansomedEnemies is a Set keyed by
                // enemy NAME, so a second coin paid to the same enemy was silently swallowed: it
                // left the inventory, the Set.add() returned false, and nothing recorded it - so
                // it could never be reclaimed. Two losses to the same fox cost two coins and
                // returned exactly one. Refusing the second ante is what the player expected the
                // mechanic to do, and it turns a silent coin sink into an explicit "not this
                // time". The key expression is character-identical to the pay path below and the
                // reclaim path, so the gate can never key differently from the mark.
                && !Current.player().owesCoinRansom(enemy.getName());
    }

    /** Pays one Bronze Coin: every ante card from this loss comes back, and the defeat's gold
     *  penalty is waived (life loss still applies - see AdventurePlayer.defeated()). */
    private void payCoinRansomForAll(List<PaperCard> lostCards) {
        Current.player().payCoinRansom(enemy != null ? enemy.getName() : null);
        for (PaperCard lost : lostCards) {
            // remove(Object), not contains(): it consumes exactly ONE occurrence, so a player who
            // anted two copies of the same card and bought back one still gets the other returned.
            if (anteAlreadyRecovered.remove(lost))
                continue;
            Current.player().addCard(lost);
            // Same in-place restore Buy Back does (2026-08-20 user report) - the cards were part
            // of this deck when they were ante'd away, so recovering them puts them back there
            // rather than only into the collection.
            if (Current.player().getSelectedDeck() != null)
                Current.player().getSelectedDeck().getMain().add(lost);
        }
    }

    // Ante Buy Back price floor by rarity (2026-08-17 user report: 150% of a heavily
    // sellFactor-scaled Insane-difficulty sell price rounded to "3 gold"). Special/BasicLand/
    // anything else not called out explicitly falls back to the Common floor - the lowest tier,
    // never a reason to charge less than a Common would cost.
    private static int anteBuyBackMinPrice(PaperCard card) {
        TuningData tuning = Config.instance().getTuningData();
        CardRarity rarity = card.getRarity();
        if (rarity == CardRarity.MythicRare)
            return tuning.anteBuyBackMinMythic;
        if (rarity == CardRarity.Rare)
            return tuning.anteBuyBackMinRare;
        if (rarity == CardRarity.Uncommon)
            return tuning.anteBuyBackMinUncommon;
        return tuning.anteBuyBackMinCommon;
    }

    private void showAnteCardPopup(String title, PaperCard card, boolean won, Runnable onDone, Runnable onCoinRansom) {
        Localizer localizer = Forge.getLocalizer();
        CardView cardView = CardView.getCardForUi(card);

        FDisplayObject cardDisplay = new FDisplayObject() {
            @Override
            public boolean tap(float x, float y, int count) {
                CardZoom.show(cardView);
                return true;
            }
            @Override
            public boolean longPress(float x, float y) {
                CardZoom.show(cardView);
                return true;
            }
            @Override
            public void draw(Graphics g) {
                float h = getHeight();
                float w = h / FCardPanel.ASPECT_RATIO;
                float xPos = (getWidth() - w) / 2;
                CardRenderer.drawCard(g, cardView, xPos, 0, w, h, CardStackPosition.Top, true);
            }
        };
        cardDisplay.setHeight(Forge.getScreenHeight() / 3);

        int ownedCount = Current.player().getCollectionCards(true).count(card);
        String ownedInfo = won
                ? (ownedCount == 0 ? " (New!)" : " (Owned: " + ownedCount + ")")
                : (ownedCount > 0 ? " (Remaining: " + ownedCount + ")" : "");
        String message = card.getName() + ownedInfo;
        List<String> buttons;
        // Buy Back (2026-08-16 user request): anteBuyBackMultiplier (TuningData, default 150%) of
        // the card's normal sell value. Reuses cardSellPrice() as the base - the SAME
        // difficulty-scaled (sellFactor) calculation the sibling Auto-Sell button above already
        // uses - rather than introducing a second, separate difficulty multiplier:
        // EconomyBuildings.scaledCost()/difficultyPriceMultiplier() is documented as deliberately
        // NOT applied to card values (that's ShopActor's own reputation-tier scaling's job, and
        // there's no shop/town context here anyway).
        // Only offered when currently affordable - same "only offer when it makes sense" gate
        // the Auto-Sell branch already applies via its own sellPrice>0 check, since FOptionPane's
        // button list has no per-button disabled state to grey one out instead.
        int buyBackPrice = (!won && eventData == null)
                ? Math.max(Math.round(Current.player().cardSellPrice(card) * Config.instance().getTuningData().anteBuyBackMultiplier),
                        anteBuyBackMinPrice(card))
                : 0;
        boolean offerBuyBack = buyBackPrice > 0 && Current.player().getGold() >= buyBackPrice;
        // Diagnostic companion to [TFR-AnteResult] - proves the lost-card popup was actually
        // built, and with which buttons.
        if (!won)
            System.out.println("[TFR-AnteBuyBack] card=" + card.getName() + " price=" + buyBackPrice
                    + " gold=" + Current.player().getGold() + " offering=" + offerBuyBack);
        // 2026-08-22 fix: buyBackPrice>0 but unaffordable used to fall through to a bare "OK"
        // popup with zero mention of Buy Back ever having been an option - indistinguishable from
        // the mechanic simply not existing here. A dungeon-loss report ("never offered") turned
        // out to be exactly this case (gold=280, price=300) rather than a code-level gate - see
        // MOD_CHANGELOG.md. Surface the price/shortfall in the message text itself so "broke" reads
        // differently from "broken."
        if (buyBackPrice > 0 && !offerBuyBack) {
            message += "\nBuy Back available for " + buyBackPrice + " gold - you only have "
                    + Current.player().getGold() + ".";
        }
        // Bronze Coin ante ransom (user spec 2026-08-29), offered alongside Buy Back on a loss.
        // Recovers EVERY card lost this duel and waives the defeat gold penalty for one coin.
        boolean offerCoinRansom = onCoinRansom != null && coinRansomEligible();
        // FOptionPane has no per-button disabled state (see the Buy Back comment above), so the
        // requested "grey it out when you have no coin" is expressed the way this same dialog
        // already handles an unaffordable Buy Back (2026-08-22 fix): the button is omitted and
        // the message says the option existed, so "no coin" reads differently from "no such
        // feature". Only surfaced when a coin is the ONLY thing missing - not on bosses/events,
        // where the option genuinely does not apply and mentioning it would just confuse.
        if (!won && onCoinRansom != null && !offerCoinRansom
                && eventData == null && !isArena && enemy != null && !enemy.getData().boss) {
            // Two different reasons the button is missing, and they must not read the same
            // (2026-08-22: "no coin" has to look different from "no such feature"). The
            // one-coin-per-enemy rule added 2026-08-31 creates a THIRD case where the player does
            // have coins - saying "you have none" there would read as the mechanic being broken.
            if (Current.player().owesCoinRansom(enemy.getName())) {
                message += "\n" + enemy.getName() + " already holds one of your Bronze Challenge "
                        + "Coins. Beat them to win it back.";
            } else {
                message += "\nA Bronze Challenge Coin would buy back your whole ante - you have none.";
            }
        }
        if (!won)
            System.out.println("[TFR-CoinRansom] offering=" + offerCoinRansom
                    + " enemy=" + (enemy == null ? "(null)" : enemy.getName())
                    + " boss=" + (enemy != null && enemy.getData().boss)
                    + " arena=" + isArena + " event=" + (eventData != null)
                    + " hasCoin=" + Current.player().hasItem(AdventurePlayer.BRONZE_COIN_ITEM)
                    + " alreadyOwes=" + (enemy != null && Current.player().owesCoinRansom(enemy.getName())));

        if (won && eventData == null) {
            int sellPrice = Current.player().cardSellPrice(card);
            buttons = sellPrice > 0
                    ? ImmutableList.of(localizer.getMessage("lblOK"), "Auto-Sell (" + sellPrice + " gold)")
                    : ImmutableList.of(localizer.getMessage("lblOK"));
        } else if (offerBuyBack && offerCoinRansom) {
            buttons = ImmutableList.of(localizer.getMessage("lblOK"),
                    "Buy Back (" + buyBackPrice + " gold)", "Use Bronze Coin");
        } else if (offerBuyBack) {
            buttons = ImmutableList.of(localizer.getMessage("lblOK"), "Buy Back (" + buyBackPrice + " gold)");
        } else if (offerCoinRansom) {
            buttons = ImmutableList.of(localizer.getMessage("lblOK"), "Use Bronze Coin");
        } else {
            buttons = ImmutableList.of(localizer.getMessage("lblOK"));
        }
        // Which button index the coin lands on depends on whether Buy Back is also showing.
        final int coinButtonIndex = offerCoinRansom ? (offerBuyBack ? 2 : 1) : -1;

        FOptionPane popup = new FOptionPane(message, null, title, null, cardDisplay, buttons, 0, result -> {
            if (won && result == 1) {
                Current.player().autoSellCards.add(card);
            }
            if (coinButtonIndex >= 0 && result == coinButtonIndex) {
                // Recovers the WHOLE ante and skips the remaining per-card popups - onCoinRansom
                // calls the sequence's own onDone itself, so this must NOT also run onDone below.
                onCoinRansom.run();
                return;
            }
            if (offerBuyBack && result == 1) {
                Current.player().takeGold(buyBackPrice);
                Current.player().addCard(card);
                // Remember it so a Bronze Coin ransom later in the same loss skips it - see
                // anteAlreadyRecovered.
                anteAlreadyRecovered.add(card);
                // 2026-08-20 user report: "When you buy back your ante card you lose, it should
                // go to the current active deck. Currently going to inventory." The card was part
                // of this deck when it was ante'd away, so buying it back restores it in place
                // (addCard above only returns it to the collection).
                if (Current.player().getSelectedDeck() != null)
                    Current.player().getSelectedDeck().getMain().add(card);
            }
            if (onDone != null) onDone.run();
        });
        FThreads.invokeInEdtNowOrLater(popup::show);
    }

    void addEffects(RegisteredPlayer player, Array<EffectData> effects) {
        if (effects == null) return;
        //Apply various combat effects.
        int lifeMod = 0;
        int changeStartCards = 0;
        int extraManaShards = 0;
        Array<IPaperCard> startCards = new Array<>();
        Array<IPaperCard> startCardsTapped = new Array<>();
        Array<IPaperCard> startCardsInCommandZone = new Array<>();

        for (EffectData data : effects) {
            lifeMod += data.lifeModifier;
            changeStartCards += data.changeStartCards;
            startCards.addAll(data.startBattleWithCards());
            startCardsTapped.addAll(data.startBattleWithCardsTapped());
            startCardsInCommandZone.addAll(data.startBattleWithCardsInCommandZone());
            extraManaShards += data.extraManaShards;
        }
        player.addExtraCardsOnBattlefield(startCards);
        player.addExtraCardsOnBattlefieldTapped(startCardsTapped);
        player.addExtraCardsInCommandZone(startCardsInCommandZone);
        if (lifeMod != 0)
            player.setStartingLife(Math.max(1, lifeMod + player.getStartingLife()));
        player.setStartingHand(player.getStartingHand() + changeStartCards);
        player.setManaShards((player.getManaShards() + extraManaShards));
        player.setEnableETBCountersEffect(true); //enable etbcounters on starting cards like Ring of Three Wishes, etc...
    }

    public void setDungeonEffect(EffectData E) {
        dungeonEffect = E;
    }

    @Override
    public void enter() {
        Adventure.getInstance().renderTransitionScreen = false;
        Localizer localizer = Forge.getLocalizer();
        SoundSystem.instance.stopBackgroundMusic();
        GameType mainGameType;
        boolean isDeckMissing = false;
        String isDeckMissingMsg = "";
        if (eventData != null && eventData.eventRules != null) {
            mainGameType = eventData.eventRules.gameType;
        } else if (AdventurePlayer.current().isCommanderMode()){
            mainGameType = GameType.Commander;
        } else {
            mainGameType = GameType.Adventure;
        }
        Set<GameType> appliedVariants = EnumSet.of(mainGameType);

        AdventurePlayer advPlayer = Current.player();

        List<RegisteredPlayer> players = new ArrayList<>();

        applyAdventureDeckRules(mainGameType.getDeckFormat());
        int playerCount = 1;
        EnemyData currentEnemy = enemy.getData();
        for (int i = 0; i < 8 && currentEnemy != null; i++) {
            playerCount++;
            currentEnemy = currentEnemy.nextEnemy;
        }

        humanPlayer = RegisteredPlayer.forVariants(playerCount, appliedVariants, playerDeck, null, false, null, null);
        // Deck Tester "Simulated" mode (2026-08-13, user spec) - the ONLY line that changes for a
        // fully-AI-vs-AI test duel; everything else below (avatar/name wiring, starting life/
        // shards, the humanPlayer RegisteredPlayer itself) is unaffected, since Forge's core
        // HostedMatch already treats a humanCount==0 match as a normal spectated game (same
        // MatchController screen, same AdventureWinLose win/lose flow) with no other plumbing
        // needed.
        LobbyPlayer playerObject = aiControlsPlayerSide
                ? GamePlayerUtil.createAiPlayer(advPlayer.getName(), "")
                : GamePlayerUtil.getGuiPlayer();
        FSkin.getAvatars().put(playerAvatarKey, advPlayer.avatar());
        playerObject.setAvatarIndex(playerAvatarKey);
        humanPlayer.setPlayer(playerObject);
        humanPlayer.setTeamNumber(0);
        humanPlayer.setStartingLife(eventData != null ? eventData.eventRules.startingLife : advPlayer.getLife());
        if (eventData == null || eventData.eventRules.allowsShards)
            humanPlayer.setManaShards(advPlayer.getShards());

        Array<EffectData> playerEffects = new Array<>();
        Array<EffectData> oppEffects = new Array<>();

        Map<DeckProxy, Pair<List<String>, List<String>>> deckProxyMapMap = null;
        DeckProxy deckProxy = null;
        if (chaosBattle) {
            deckProxyMapMap = DeckProxy.getAllQuestChallenges();
            deckProxy = Aggregates.random(deckProxyMapMap.keySet());
            //playerextras
            List<IPaperCard> playerCards = new ArrayList<>();
            for (String s : deckProxyMapMap.get(deckProxy).getLeft()) {
                playerCards.add(QuestUtil.readExtraCard(s));
            }
            humanPlayer.addExtraCardsOnBattlefield(playerCards);
        }

        if (eventData == null || eventData.eventRules.allowsItems) {
            //Collect and add items effects first.
            for (Long id : advPlayer.getEquippedItems()) {
                ItemData item = Current.player().getEquippedItem(id);
                if (item != null && item.effect != null) {
                    playerEffects.add(item.effect);
                    if (item.effect.opponent != null) oppEffects.add(item.effect.opponent);
                } else {
                    System.err.printf("Item %s not found.", id);
                }
            }
        }
        if (eventData == null || eventData.eventRules.allowsBlessings) {
            //Collect and add player blessings.
            if (advPlayer.getBlessing() != null) {
                playerEffects.add(advPlayer.getBlessing());
                if (advPlayer.getBlessing().opponent != null) oppEffects.add(advPlayer.getBlessing().opponent);
            }

            //Collect and add enemy effects (same as blessings but for individual enemies).
            if (enemy.effect != null) {
                oppEffects.add(enemy.effect);
                if (enemy.effect.opponent != null)
                    playerEffects.add(enemy.effect.opponent);
            }
        }
        //Collect and add dungeon-wide effects.
        if (dungeonEffect != null) {
            oppEffects.add(dungeonEffect);
            if (dungeonEffect.opponent != null)
                playerEffects.add(dungeonEffect.opponent);
        }

        addEffects(humanPlayer, playerEffects);

        currentEnemy = enemy.getData();
        boolean bossBattle = currentEnemy.boss;
        for (int i = 0; i < playerCount && currentEnemy != null; i++) {
            Deck deck;

            if (this.chaosBattle) { //random challenge for chaos mode
                if (deckProxyMapMap == null)
                    continue;
                //aiextras
                List<IPaperCard> aiCards = new ArrayList<>();
                for (String s : deckProxyMapMap.get(deckProxy).getRight()) {
                    aiCards.add(QuestUtil.readExtraCard(s));
                }
                this.AIExtras = aiCards;
                deck = deckProxy.getDeck();
            } else if (this.arenaBattleChallenge) {
                if (Config.instance().getConfigData().enableGeneticAI) {
                    deck = Aggregates.random(DeckProxy.getAllGeneticAIDecks()).getDeck();
                } else {
                    deck = currentEnemy.generateDeck(Current.player().isFantasyMode(), false);
                }
            } else if (this.eventData != null) {
                deck = eventData.nextOpponent.getDeck();
            } else if (currentEnemy.fixedDeck != null) {
                // Deck Tester (MOD_SCOPE.md #20, 2026-08-11): the AI plays an EXACT pre-built
                // Deck (one of the player's own saved decks, not the one they're currently
                // piloting) rather than anything resolved from `deck`/`randomizeDeck`/
                // `copyPlayerDeck` - see EnemyData.fixedDeck's own comment.
                deck = currentEnemy.fixedDeck;
            } else {
                boolean useGeneticAI = Config.instance().getConfigData().enableGeneticAI && (Current.player().isUsingCustomDeck() || Current.player().isHardorInsaneDifficulty());
                deck = currentEnemy.copyPlayerDeck ? this.playerDeck : currentEnemy.generateDeck(Current.player().isFantasyMode(), useGeneticAI);
            }
            if (deck == null) {
                isDeckMissing = true;
                boolean canUseGeneticAI = Config.instance().getConfigData().enableGeneticAI;
                isDeckMissingMsg = localizer.getMessage("advDeckMissingForEnemy", currentEnemy.getName())
                        + (this.eventData == null ? (canUseGeneticAI ? localizer.getMessage("advGeneticAiDeckWillBeUsed") : localizer.getMessage("advPlayerDeckWillBeUsed")) : localizer.getMessage("advPlayerDeckWillBeUsed"));
                System.err.println(isDeckMissingMsg);
                deck = this.eventData == null && canUseGeneticAI ? Aggregates.random(DeckProxy.getAllGeneticAIDecks()).getDeck() : this.playerDeck;
            }
            RegisteredPlayer aiPlayer = RegisteredPlayer.forVariants(playerCount, appliedVariants, deck, null, false, null, null);

            // Tiered display name (user spec 2026-08-13, e.g. "Red Wizard (Adept)") - display-only,
            // gated on showEnemyTierInName inside the helper; the LobbyPlayer name is never used
            // for identity (winner detection is reference-equality on RegisteredPlayer, quest
            // matching goes through EnemyData.match() on the raw name field). Inn-tournament event
            // duels (eventData != null) stay RAW - EventScene's standings/bracket/vs-screen all
            // show raw participant names, and a tiered in-duel nameplate would give the same
            // opponent two different names within one event (2026-08-13 holistic review).
            boolean tierNames = eventData == null;
            LobbyPlayer enemyPlayer = GamePlayerUtil.createAiPlayer(
                    tierNames ? currentEnemy.getTieredDisplayName() : currentEnemy.getName(), selectAI(currentEnemy.ai));
            // The head sprite's display-name override (a .tmx displayNameOverride - how "The
            // Warden" is an Adept Black Wizard underneath) applies to seat 0 ONLY (2026-09-01
            // fix). It used to overwrite EVERY seat's name with the head's, so a 1-vs-2 against
            // a Fox chained with a Wolf showed "Fox" and "2nd Fox" - the old trailing comment
            // ("only supported for 1 enemy atm") was the original author admitting exactly this.
            // Later seats keep the name built from currentEnemy above; the engine de-dupes any
            // genuine same-name pack to "2nd ...".
            if (i == 0)
                enemyPlayer.setName(tierNames ? enemy.getTieredDisplayName() : enemy.getName());
            // Per-seat portrait (2026-09-01 fix). Three things changed here:
            //  - getAvatar(i) now clamps instead of throwing IndexOutOfBoundsException on the
            //    491-of-493 single-Avatar-frame atlases, which crashed every chained duel except
            //    Goblin Pack the moment this scene opened;
            //  - it can return null (umber_hulk.atlas has zero Avatar regions) - skip the wiring
            //    and let the default AI avatar stand rather than NPE on the flip;
            //  - the flip now happens on a COPY. The cached Sprite is shared process-wide
            //    (CharacterSprite.load() addAll's Config's cached instances), so flipping it in
            //    place made the portrait's facing ALTERNATE on every successive duel against the
            //    same enemy type - and with the clamp, two seats sharing one frame would have
            //    flipped it twice in a single fight.
            Sprite seatAvatar = enemy.getAvatar(i);
            if (seatAvatar != null) {
                TextureRegion enemyAvatar = new TextureRegion(seatAvatar);
                enemyAvatar.flip(true, false); //flip facing left
                FSkin.getAvatars().put(enemyAvatarKey + i, enemyAvatar);
                enemyPlayer.setAvatarIndex(enemyAvatarKey + i);
            }
            aiPlayer.setPlayer(enemyPlayer);
            aiPlayer.setTeamNumber(currentEnemy.teamNumber);
            int enemyStartingLife = Math.round((float) currentEnemy.life * advPlayer.getDifficulty().enemyLifeFactor);
            int lifeBeforeTerrainModifier = enemyStartingLife;
            // Day/night terrain life modifier (user spec 2026-08-12): OVERWORLD roaming fights
            // only - events (Arena/Inn, eventData != null) use their own rules line below, and
            // town/dungeon interiors are excluded via isInMap(). The enemy sprite's own tile
            // decides the terrain; the whole nextEnemy chain fights on that same tile.
            if (this.eventData == null && !MapStage.getInstance().isInMap()) {
                int tileSize = Current.world().getTileSize();
                enemyStartingLife = Current.world().applyDayNightTerrainLife(enemyStartingLife,
                        (int) enemy.getX() / tileSize, (int) enemy.getY() / tileSize);
            }
            // Diagnostic logging standard (user request 2026-08-13) - unconditional (unlike
            // [TFR-DayNight] above, which only fires for the colored-terrain/day-night-enabled
            // subset of overworld fights), so difficulty-scaled starting life is verifiable for
            // EVERY fight, including neutral terrain, dungeons/towns, and Arena/Inn events.
            System.out.println("[TFR-EnemyLife] " + currentEnemy.getName() + " rawLife=" + currentEnemy.life
                    + " enemyLifeFactor=" + advPlayer.getDifficulty().enemyLifeFactor
                    + " -> difficultyScaled=" + lifeBeforeTerrainModifier
                    + (enemyStartingLife != lifeBeforeTerrainModifier ? " -> terrainAdjusted=" + enemyStartingLife : "")
                    + " (eventOverride=" + (eventData != null) + ")");
            aiPlayer.setStartingLife(eventData != null ? eventData.eventRules.startingLife : enemyStartingLife);

            Array<EffectData> equipmentEffects = new Array<>();
            if (eventData != null && eventData.eventRules.allowsItems) {
                if (currentEnemy.equipment != null) {
                    for (String oppItem : currentEnemy.equipment) {
                        ItemData item = ItemListData.getItem(oppItem);
                        if (item == null)
                            continue;
                        equipmentEffects.add(item.effect);
                        if (item.effect.opponent != null)
                            playerEffects.add(item.effect.opponent);
                    }
                }
            }
            addEffects(aiPlayer, oppEffects);
            addEffects(aiPlayer, equipmentEffects);

            //add extra cards for challenger mode
            if (chaosBattle) {
                aiPlayer.addExtraCardsOnBattlefield(AIExtras);
            }

            players.add(aiPlayer);

            if (eventData == null) {
                Current.setLatestDeck(deck);
            }

            currentEnemy = currentEnemy.nextEnemy;
        }

        players.add(humanPlayer);

        if(eventData != null && eventData.draft != null) {
            for(RegisteredPlayer p : players)
                p.assignConspiracies();
        }

        final Map<RegisteredPlayer, IGuiGame> guiMap = new HashMap<>();
        guiMap.put(humanPlayer, MatchController.instance);

        hostedMatch = MatchController.hostMatch();

        GameRules rules;

        if (eventData != null) {
            rules = new GameRules(eventData.eventRules.gameType);
            rules.setGamesPerMatch(eventData.eventRules.gamesPerMatch);
            bossBattle = false;
        } else {
            rules = new GameRules(GameType.Adventure);
            rules.setGamesPerMatch(enemy.getData().gamesPerMatch);
        }
        // Arena matches disable ante regardless of the player's global setting (user spec
        // 2026-08-11) - see EnemyData.noAnte, set only on a per-fight clone by ArenaScene.
        // Inn tournament matches disable ante too (user spec 2026-08-17) - every match that is
        // part of an event (eventData != null) skips ante, both the human's own bracket match
        // (this line) and AI-vs-AI event pairings (already off unconditionally - see
        // DeckTesterSimulator's own rules.setPlayForAnte(false) used when "simulate AI matches"
        // is enabled; the default coin-flip AI-vs-AI resolution in EventScene.startRound() never
        // constructs a Game/GameRules at all).
        rules.setPlayForAnte(eventData == null && !enemy.getData().noAnte && FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ANTE));
        rules.setMatchAnteRarity(FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ANTE_MATCH_RARITY));
        rules.setAnteIncludeBasicLands(FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ANTE_INCLUDE_BASIC_LANDS));
        rules.setManaBurn(false);
        rules.setWarnAboutAICards(false);

        //hostedMatch.setEndGameHook(() -> DuelScene.this.GameEnd());
        hostedMatch.startMatch(rules, appliedVariants, players, guiMap, bossBattle ? MusicPlaylist.BOSS : MusicPlaylist.MATCH);
        MatchController.instance.setGameView(hostedMatch.getGameView());
        boolean showMessages = enemy.getData().boss || (enemy.getData().copyPlayerDeck && Current.player().isUsingCustomDeck());
        LoadingOverlay matchOverlay;
        if (chaosBattle || showMessages || isDeckMissing) {
            final FBufferedImage fb = getFBEnemyAvatar();
            String Intro = enemy.getBossIntro();
            if (Intro != null){
                bossDialogue = createFOption((Intro), enemy.getTieredDisplayName(), fb, fb::dispose);
                }
                else {
                bossDialogue = createFOption(isDeckMissing ? isDeckMissingMsg : localizer.getMessage("AdvBossIntro" + Aggregates.randomInt(1, 35)),
                enemy.getTieredDisplayName(), fb, fb::dispose);
                }
            matchOverlay = new LoadingOverlay(() -> FThreads.delayInEDT(300, () -> FThreads.invokeInEdtNowOrLater(() ->
            bossDialogue.show())), false, true);
        } else {
            matchOverlay = new LoadingOverlay(null);
        }
        for (final Player p : hostedMatch.getGame().getPlayers()) {
            if (p.getController() instanceof PlayerControllerHuman) {
                final PlayerControllerHuman humanController = (PlayerControllerHuman) p.getController();
                humanController.setGui(MatchController.instance);
                MatchController.instance.setOriginalGameController(p.getView(), humanController);
                MatchController.instance.openView(new TrackableCollection<>(p.getView()));
            }
        }
        super.enter();
        matchOverlay.show();
    }

    private static final String PLACEHOLDER_MAIN = "Wastes";
    private static final String PLACEHOLDER_COMMANDER = "Atogatog";
    private static final String PLACEHOLDER_ATTRACTION = "Coin-Operated Pony";
    private static final String PLACEHOLDER_CONTRAPTION = "Automatic Fidget Spinner";

    private void applyAdventureDeckRules(DeckFormat format) {
        if(FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.DEV_MODE_ENABLED)
                && !FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.ENFORCE_DECK_LEGALITY))
            return;

        //Can't just keep the player from entering a battle if their deck is invalid. So instead we'll just edit their deck.
        CardPool mainSection = playerDeck.getMain(), attractions = playerDeck.get(DeckSection.Attractions), contraptions = playerDeck.get(DeckSection.Contraptions);

        if(format.hasCommander()) {
            applyAdventureCommandZoneRules(playerDeck, format);
        }

        removeExcessCopies(mainSection, format);
        removeExcessCopies(attractions, format);
        removeExcessCopies(contraptions, format);

        int mainSize = mainSection.countAll();

        int maxDeckSize = format == DeckFormat.Adventure ? Integer.MAX_VALUE : format.getMainRange().getMaximum();
        int minDeckSize = format == DeckFormat.Adventure ? Config.instance().getConfigData().minDeckSize : format.getMainRange().getMinimum();

        if(format.hasCommander() && playerDeck.has(DeckSection.Commander)) {
            //If they have a partner commander, it counts toward the 99.
            int commandExtras = Math.max(0, playerDeck.get(DeckSection.Commander).countAll() - 1);
            mainSize += commandExtras;
            maxDeckSize = applyCommanderSizeRule(playerDeck.getCommanders(), maxDeckSize);
        }

        int excessCards = mainSize - maxDeckSize;
        if (excessCards > 0) {
            List<PaperCard> removals = Aggregates.random(mainSection.toFlatList(), excessCards);
            mainSection.removeAllFlat(removals);
        }

        int missingCards = minDeckSize - mainSize;
        if (missingCards > 0) //Replace unknown cards for a Wastes.
            mainSection.add(PLACEHOLDER_MAIN, missingCards);

        if(attractions != null && !attractions.isEmpty()) {
            int missingAttractions = 10 - attractions.countAll(); //TODO: These shouldn't be hard coded but DeckFormat's gonna need some reorganizing to fetch this dynamically
            if(missingAttractions > 0)
                attractions.add(PLACEHOLDER_ATTRACTION, missingAttractions);
        }
        if(contraptions != null && !contraptions.isEmpty()) {
            int missingContraptions = 15 - contraptions.countAll();
            if(missingContraptions > 0)
                contraptions.add(PLACEHOLDER_CONTRAPTION, missingContraptions);
        }
    }

    private static void removeExcessCopies(CardPool section, DeckFormat format) {
        if(section == null)
            return;
        Map<String, List<PaperCard>> removals = new HashMap<>();
        for(Map.Entry<PaperCard, Integer> e : section) {
            PaperCard card = e.getKey();
            String cardName = card.getCardName();
            if(removals.containsKey(cardName))
                continue; //Already processed.
            int amount = section.countByName(cardName);
            int limit = format.getMaxCardCopies(card);
            if(amount > limit) {
                removals.put(cardName, getItemsToRemove(section, cardName, amount - limit));
            }
        }
        for(List<PaperCard> list : removals.values())
            section.removeAllFlat(list);
    }

    private static List<PaperCard> getItemsToRemove(CardPool section, String cardName, int copies) {
        return section.toFlatList().stream()
                .filter(e -> e.getCardName().equals(cardName))
                .collect(StreamUtil.random(copies));
    }

    //Applies DeckRule:Size:AdjustMax$ from any commander that has one (e.g. Whtz, the Bibliophile).
    private static int applyCommanderSizeRule(List<PaperCard> commanders, int maxDeckSize) {
        for(PaperCard commander : commanders) {
            for(DeckRule rule : DeckRule.parseAll(commander)) {
                if(!(rule instanceof DeckRuleSize) || !rule.isActiveFor(DeckSection.Commander))
                    continue;
                DeckRuleSize sizeRule = (DeckRuleSize) rule;
                if(sizeRule.removesMaxDeckSize())
                    maxDeckSize = Integer.MAX_VALUE;
                else if(maxDeckSize != Integer.MAX_VALUE)
                    maxDeckSize += sizeRule.getMaxDelta();
            }
        }
        return maxDeckSize;
    }

    private static void applyAdventureCommandZoneRules(Deck playerDeck, DeckFormat format) {
        CardPool commandPool = playerDeck.getOrCreate(DeckSection.Commander);

        //1. Validate command section.
        List<PaperCard> removals = new ArrayList<>();
        List<PaperCard> commanders = playerDeck.getCommanders(); //ordered flat list
        if (commanders.size() > 2) {
            removals.addAll(commanders.subList(2, commanders.size()));
            commanders = commanders.subList(0, 2);
        }
        if (!commanders.isEmpty()) {
            PaperCard mainCommander = commanders.get(0);
            if (!format.isLegalCommander(mainCommander.getRules()))
                removals.add(mainCommander);
            if (commanders.size() > 1) {
                PaperCard partnerCommander = commanders.get(1);
                if (removals.contains(mainCommander)) {
                    if (!format.isLegalCommander(partnerCommander.getRules()))
                        removals.add(partnerCommander); //Main is invalid but partner is valid.
                } else if (!mainCommander.getRules().canBePartnerCommanders(partnerCommander.getRules()))
                    removals.add(partnerCommander); //Invalid partnership.
            }
        }
        commandPool.removeAllFlat(removals);
        CardPool mainPool = playerDeck.getMain();
        mainPool.add(removals); //Dump all the removed cards into the main pool.

        //2. If you're missing a commander, install a terrible one.
        if(commandPool.isEmpty()) {
            commandPool.add(PLACEHOLDER_COMMANDER, 1);
        }

        //3. Validate quantities across command zone and main section
        //In other words if it's your commander, make sure there isn't a copy in your main deck.
        for(Map.Entry<PaperCard, Integer> e : commandPool) {
            PaperCard card = e.getKey();
            int limit = format.getMaxCardCopies(card);
            int amountMain = mainPool.countByName(card);
            if(amountMain > 0) {
                int amountCommand = commandPool.countByName(card);
                int toRemove = Math.max(0, (amountMain + amountCommand) - limit);
                if(toRemove > 0) {
                    mainPool.removeAllFlat(getItemsToRemove(mainPool, card.getCardName(), toRemove));
                }
            }
        }

        //4. Filter for color identity.
        byte cmdCI = 0;
        int wildColors = 0; //For Prismatic Piper and friends.
        List<DeckRuleColorIdentity> ciRules = new ArrayList<>();
        for(PaperCard commander : playerDeck.getCommanders()) {
            cmdCI |= commander.getRules().getColorIdentity().getColor();
            wildColors += commander.getRules().getAddsWildCardColor() ? 1 : 0;
            for(DeckRule rule : DeckRule.parseAll(commander)) {
                if(rule instanceof DeckRuleColorIdentity && rule.isActiveFor(DeckSection.Commander))
                    ciRules.add((DeckRuleColorIdentity) rule);
            }
        }
        for(Map.Entry<PaperCard, Integer> e : mainPool) {
            PaperCard card = e.getKey();
            if(DeckFormat.allowsOffColorIdentity(ciRules, card.getRules())
                    || DeckFormat.approvesAdditionalColor(ciRules, card.getRules(), cmdCI))
                continue; //Exempted or covered by the commander's DeckRule:ColorIdentity.
            ColorSet missingColors = card.getRules().getColorIdentity().getMissingColors(cmdCI);
            if (missingColors.countColors() > 0) {
                if (missingColors.countColors() <= wildColors) {
                    wildColors -= missingColors.countColors();
                    cmdCI |= missingColors.getColor();
                } else {
                    mainPool.removeAll(card);
                }
            }
        }
    }

    @Override
    public FScreen getScreen() {
        return MatchController.getView();
    }

    @Override
    public boolean leave() {
        Adventure.getInstance().renderTransitionScreen = true;
        return super.leave();
    }

    public void initDuels(PlayerSprite playerSprite, EnemySprite enemySprite) {
        initDuels(playerSprite, enemySprite, false, null);
    }

    public void initDuels(PlayerSprite playerSprite, EnemySprite enemySprite, boolean isArena, AdventureEventData eventData) {
        initDuels(playerSprite, enemySprite, isArena, eventData, false);
    }

    /** Deck Tester "Simulated" mode (user spec, 2026-08-13): aiControlsPlayerSide lets the
     *  "player" seat itself be AI-piloted too, for a fully-simulated AI-vs-AI match between two
     *  of the player's own decks (as opposed to the existing mode where the player pilots one
     *  side). See enter()'s own playerObject construction for the one line this actually changes -
     *  everything else (avatar wiring, life/shard totals, win/lose flow via MatchController/
     *  AdventureWinLose) already works unmodified for an AI-controlled seat, since Forge's core
     *  HostedMatch already supports a fully-AI (humanCount==0) match as a normal spectated game. */
    public void initDuels(PlayerSprite playerSprite, EnemySprite enemySprite, boolean isArena, AdventureEventData eventData, boolean aiControlsPlayerSide) {
        this.player = playerSprite;
        this.enemy = enemySprite;
        this.isArena = isArena;
        this.eventData = eventData;
        this.aiControlsPlayerSide = aiControlsPlayerSide;
        if (eventData != null && eventData.eventRules == null)
            eventData.eventRules = new AdventureEventData.AdventureEventRules(AdventureEventController.EventFormat.Constructed);
        this.arenaBattleChallenge = isArena && Current.player().isHardorInsaneDifficulty();
        if (eventData != null && eventData.registeredDeck != null)
            this.playerDeck = (Deck) eventData.registeredDeck.copyTo("EventDeckCopy");
        else
            this.playerDeck = (Deck) Current.player().getSelectedDeck().copyTo("PlayerDeckCopy");
        this.chaosBattle = this.enemy.getData().copyPlayerDeck && Current.player().isFantasyMode();
        this.AIExtras.clear();
        this.playerExtras.clear();
    }

    private String selectAI(String ai) { //Decide opponent AI.
        String AI = ""; //Use user settings if it's null.
        if (ai != null) {
            AI = switch (ai.toLowerCase()) { //We use this way to ensure capitalization is exact.
                //We don't want misspellings here.
                case "default" -> "Default";
                case "reckless" -> "Reckless";
                case "cautious" -> "Cautious";
                case "experimental" -> "Experimental";
                default -> ""; //User settings.
            };
        }
        return AI;
    }

    private FBufferedImage getFBEnemyAvatar() {
        return new FBufferedImage(120, 120) {
            @Override
            protected void draw(Graphics g, float w, float h) {
                if (FSkin.getAvatars().get(enemyAvatarKey) != null)
                    g.drawImage(FSkin.getAvatars().get(enemyAvatarKey), 0, 0, w, h);
            }
        };
    }
}
