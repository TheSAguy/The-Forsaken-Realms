package forge.adventure.data;


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
    // Inn tournament AI-vs-AI match simulation (2026-08-17 user spec: "I assume, currently it's
    // just a coin flip... have the two AI's actually simulate their match, behind the science...
    // By Default, have this unchecked"). Confirmed the assumption was correct - EventScene.
    // startRound()'s AI-vs-AI branch was a bare MyRandom.percentTrue(50) with a "//Todo: Actually
    // run match simulation here" comment. Off by default since DeckTesterSimulator.runBatch() -
    // the same real Match/Game engine the Arena's Deck Tester uses - is real gameplay, not free:
    // a full round with the human eliminated can have several AI-vs-AI pairings, each running up
    // to gamesPerMatch independent games on its own background thread.
    public boolean simulateInnTournamentAIMatches;
}
