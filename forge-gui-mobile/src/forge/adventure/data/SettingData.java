package forge.adventure.data;

import java.util.HashMap;

/**
 * Data class that will be used to read Json configuration files
 * SettingData
 * contains settings outside of the chosen adventure
 */
public class SettingData {

	public int width;
    public int height;
    public String plane;
    public boolean fullScreen;
    public String videomode;
    public String lastActiveSave;
    public Float rewardCardAdj;
    public Float cardTooltipAdj;
    public Float rewardCardAdjLandscape;
    public Float cardTooltipAdjLandscape;
    public boolean dayNightBG;
    public boolean disableWinLose;
    public boolean disableNotForSale;
    public boolean showShopOverlay;
    public boolean useAllCardVariants;
    public boolean disableCrackedItems;
    public boolean excludeAlchemyVariants;
    public boolean generateLDADecks;
    public boolean bindEquipmentLoadoutsToDecks;
    public boolean drawChevronsToHiddenEnemiesInClearQuest;
    public boolean preferEraMatchedTokenArt;
    // Default ON for The Forsaken Realms standalone (MOD_SCOPE.md #89, user decision
    // 2026-08-19); the per-plane fogOfWar config flag still gates the feature, so stock planes
    // are unaffected regardless of this user setting.
    public boolean fogOfWarEnabled = true;
    // Restricted-edition ART avoidance (2026-08-30 user request, prompted by Innistrad: Double
    // Feature - "DBL" - being genuinely printed in black-and-white). When on, a reward card that
    // resolved to a printing from config.json's restrictedEditions is swapped for an unrestricted
    // printing of the SAME card, preferring matching rarity. Purely cosmetic - see
    // CardUtil.remapAwayFromRestrictedEditions() for why no card can be lost and no gameplay
    // changes. Default ON (that is the behaviour this was asked for), and inert on any plane
    // whose restrictedEditions list is empty, so stock planes are untouched either way.
    public boolean avoidRestrictedEditionArt = true;
    // Round 723: the Font (util/TfrFonts), "Clean" (the duel screen's font, the default) or "Pixel" (LanaPixel). Read
    // once when the font is made, so a change needs a restart. Only on a plane with cleanFontEnabled.
    public String tfrFont = "Clean";
    // Inn tournament AI-vs-AI match simulation (2026-08-17 user spec: "I assume, currently it's
    // just a coin flip... have the two AI's actually simulate their match, behind the science...
    // By Default, have this unchecked"). Confirmed the assumption was correct - EventScene.
    // startRound()'s AI-vs-AI branch was a bare MyRandom.percentTrue(50) with a "//Todo: Actually
    // run match simulation here" comment. Off by default since DeckTesterSimulator.runBatch() -
    // the same real Match/Game engine the Arena's Deck Tester uses - is real gameplay, not free:
    // a full round with the human eliminated can have several AI-vs-AI pairings, each running up
    // to gamesPerMatch independent games on its own background thread.
    public boolean simulateInnTournamentAIMatches;
    // Round 410 (user: "add the digital-only cards setting"): when on, config.json's digitalOnlyEditions (MTG Arena's
    // Alchemy and Arena-only sets) are NOT folded into restrictedEditions, so their cards reach rewards, shops, boosters
    // and enemy decks. Off by default - round 395's block. Read once at startup (Config), so a change needs a restart.
    public boolean allowDigitalOnlyCards;
    // Round 493 (the user: "I do think the XP/Leveling system is going to be controversial, so maybe add an option is
    // settings to turn it all off"): true switches Ascendance off entirely - no Power, no main-slot limit, no HUD panel.
    // Stored as "disabled" so a settings file from before it reads as on (the default).
    public boolean ascendanceDisabled;
    // Round 615 (the user: "Default Undo to Single step in TFR ... keep the Settings choice"): set once the profile's
    // Undo mode was moved to Single step - every save of Forge's preferences since the 10.14 merge wrote upstream's
    // "Free" into the file. After that the player's own choice in Settings stands.
    public boolean undoDefaultApplied;
    // Round 698 (the user: the game log "automatically zipped and posted" to Discord; "Default should be 'Yes'"): log
    // sharing (util/LogShare). logShareAsked - the one-time question was answered; logShareEnabled - the player's answer
    // and the Settings switch; logShareInstallId - an anonymous random id, so posts from one install can be told apart;
    // logShareLastSent - the archived log last posted, never sent twice.
    public boolean logShareAsked;
    public boolean logShareEnabled;
    public String logShareInstallId;
    public String logShareLastSent;
    // Round 756 (the user: "a little prompt in the game after a certain number of game playing hours"): util/SupportPrompt.
    // supportPlaySeconds - real play time across every save, window in focus; supportAsksShown - how many of the 5/15/40
    // hour asks were shown; supportNeverAsk - the player's "Don't ask again"; supportClicked - Support TFR was clicked
    // in an ask (round 756 follow-up, the user: "once a player clicks Support TFR, don't show the later support asks").
    public double supportPlaySeconds;
    public int supportAsksShown;
    public boolean supportNeverAsk;
    public boolean supportClicked;
    // Round 772 (the user: "Disable Notoriety system option. By default unchecked"): true switches every Notoriety
    // effect off (AdventurePlayer.notorietyDisabled) - the score still counts. Off by default, so an older file reads as on.
    public boolean notorietyDisabled;
    // Preserve explicitly stored global choices from the original background settings.
    public Boolean enableExtraBattleBackgrounds;
    public HashMap<String, Boolean> extraBattleBackgroundsByPlane = new HashMap<>();
    public HashMap<String, String> battleBackgroundSources = new HashMap<>();
    public HashMap<String, Boolean> useCustomBattleBackgroundSources = new HashMap<>();
}
