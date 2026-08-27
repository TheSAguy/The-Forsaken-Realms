package forge.adventure.util;

import forge.adventure.data.AdventureQuestData;
import forge.adventure.data.ConfigData;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

import java.util.ArrayList;

/**
 * Side-quest timers (user request 2026-08-08): every non-story quest fails SIDE_QUEST_DAYS
 * in-game days after it was accepted, and the quest log shows each quest's remaining days so the
 * player can prioritize. Story quests are exempt entirely.
 * <p>
 * Accepted-day state lives on World (see World.questAcceptedDay's comment for why it is NOT a
 * field on AdventureQuestData), stamped lazily by the daily tick: a quest first seen by the tick
 * starts its clock that day - at most a day of slack after accepting, and every quest already in
 * the log when this feature arrives gets a full fresh window rather than instantly failing.
 * <p>
 * Opt-in per-plane via config.json ("sideQuestTimerEnabled": true), default off - inert on
 * Shandalar and every other stock plane.
 */
public class QuestExpiry {
    // Tunable since 2026-08-20 (TuningData.sideQuestDays, plane settings.json sets 20); the old
    // hardcoded 30 remains the built-in default when no settings.json exists.
    private static int sideQuestDays() {
        return Config.instance().getTuningData().sideQuestDays;
    }

    private QuestExpiry() {}

    private static boolean isEnabled() {
        ConfigData configData = Config.instance().getConfigData();
        return configData != null && configData.sideQuestTimerEnabled;
    }

    /** Called from WorldStage's day-change block, alongside the other daily systems. */
    public static void processDaysPassed(int newDayCount) {
        if (!isEnabled())
            return;
        World world = WorldSave.getCurrentSave().getWorld();
        ArrayList<String> failedNames = new ArrayList<>();
        for (AdventureQuestData quest : new ArrayList<>(Current.player().getQuests())) {
            if (quest.storyQuest || quest.completed || quest.failed)
                continue;
            if (isChainQuest(quest))
                continue;
            String key = String.valueOf(quest.getID());
            Integer accepted = world.getQuestAcceptedDay().get(key);
            if (accepted == null) {
                world.getQuestAcceptedDay().put(key, newDayCount);
                continue;
            }
            if (newDayCount - accepted < sideQuestDays())
                continue;
            // Out of time. fail() marks and untracks it; removing it from the log ourselves keeps
            // the outcome deterministic instead of waiting for the controller's next dialog sweep
            // (which only runs on map transitions and could leave a failed quest lingering).
            quest.fail();
            Current.player().removeQuest(quest);
            world.getQuestAcceptedDay().remove(key);
            System.out.println("[QuestExpiry] Quest failed: " + quest.getName() + " - out of time");
            failedNames.add(quest.getName());
        }
        // A blocking dialog instead of the old corner toast (user request 2026-08-08: "give a
        // popup... when the timer on the quest runs out" - the toast was too easy to miss,
        // especially at 100x fast-forward). One dialog covers every same-day failure.
        if (!failedNames.isEmpty())
            WorldStage.getInstance().showQuestsFailedDialog(failedNames);
    }

    /** Chain side quests (the converted "relic trail" line, 2026-08-26 review finding) are
     *  exempt from the expiry timer: quests 45-51 are only ever issued by their predecessor's
     *  one-shot epilogue and are never board-offerable (their questSourceTags deliberately match
     *  no town), so a mid-chain expiry could only be "recovered" by silently replaying the whole
     *  chain from its board-offered opener - a trap, not a timer. The tag is cloned onto player
     *  quest instances by AdventureQuestData's copy constructor, so it's checkable here. */
    private static boolean isChainQuest(AdventureQuestData quest) {
        if (quest.questSourceTags == null)
            return false;
        for (String tag : quest.questSourceTags)
            if ("relic_trail_chain".equals(tag))
                return true;
        return false;
    }

    /**
     * Days left before this quest fails, or null when no timer applies (feature off, story quest,
     * chain quest, or the clock simply hasn't been stamped yet). Never negative.
     */
    public static Integer daysRemaining(AdventureQuestData quest) {
        if (!isEnabled() || quest == null || quest.storyQuest || isChainQuest(quest))
            return null;
        World world = WorldSave.getCurrentSave().getWorld();
        Integer accepted = world.getQuestAcceptedDay().get(String.valueOf(quest.getID()));
        if (accepted == null)
            return null;
        return Math.max(0, sideQuestDays() - (world.getCurrentDay() - accepted));
    }

    /** Quest-log display suffix, e.g. " (12 days left)" - empty when no timer applies. */
    public static String questLogSuffix(AdventureQuestData quest) {
        Integer remaining = daysRemaining(quest);
        if (remaining == null)
            return "";
        return " [%75](" + remaining + " day" + (remaining == 1 ? "" : "s") + " left)";
    }
}
