package forge.adventure.util;

import forge.game.Game;
import forge.game.player.Player;
import forge.localinstance.achievements.Achievement;
import org.w3c.dom.Element;

import java.util.Set;
import java.util.TreeSet;

/**
 * Round 717: one of TFR's own achievements (TfrAchievements). Told of an event through Achievement.record() rather than
 * read from a finished duel, so evaluate() is never used. Tiered ones climb Forge's Common / Uncommon / Rare / Mythic
 * trophies; a single one is Forge's "special" trophy. A hidden one shows "???" until earned. {@code seen} holds the
 * distinct names a collection counts (legends, gear sets, card sets) across every save of the profile.
 */
public class TfrAchievement extends Achievement {
    private static final String[] TIER_NAMES = {"Common", "Uncommon", "Rare", "Mythic"};

    private final String description;
    private final boolean hidden;
    private final int[] thresholds; // null for a single trophy
    private final String[] goals;   // what each tier asks, as the list shows it ("3 castles")
    private final Set<String> seen = new TreeSet<>();

    /** A tiered achievement: four thresholds, low to high, and the goal text for each. */
    TfrAchievement(String key, String name, String description, boolean hidden, int[] thresholds, String[] goals) {
        super(key, name, description, 0, goals[0], thresholds[0], goals[1], thresholds[1], goals[2], thresholds[2],
                goals[3], thresholds[3]);
        this.description = description;
        this.hidden = hidden;
        this.thresholds = thresholds;
        this.goals = goals;
    }

    /** A single trophy. */
    TfrAchievement(String key, String name, String description, boolean hidden) {
        super(key, name, description, "", 0);
        this.description = description;
        this.hidden = hidden;
        this.thresholds = null;
        this.goals = null;
    }

    @Override
    protected int evaluate(Player player, Game game) {
        return 0; // never read from a duel - see TfrAchievements
    }

    @Override
    protected String getNoun() {
        return null;
    }

    public boolean isHidden() {
        return hidden;
    }

    public boolean isTiered() {
        return thresholds != null;
    }

    public String getDescription() {
        return description;
    }

    /** The highest tier reached ("Rare"), "Earned" for a single trophy, or null. */
    public String tierName() {
        int tier = tierIndex();
        if (tier >= 0)
            return TIER_NAMES[tier];
        return !isTiered() && getBest() > 0 ? "Earned" : null;
    }

    /** The goal of the highest tier reached ("3 castles"), or null. */
    public String tierGoal() {
        int tier = tierIndex();
        return tier >= 0 ? goals[tier] : null;
    }

    private int tierIndex() {
        if (!isTiered())
            return -1;
        for (int i = thresholds.length - 1; i >= 0; i--)
            if (getBest() >= thresholds[i])
                return i;
        return -1;
    }

    /** The name the Statistics list shows: "???" for a hidden achievement not yet earned. */
    public String shownName() {
        return hidden && !isActive() ? "???" : getDisplayName();
    }

    Set<String> seen() {
        return seen;
    }

    /** The description, then where it stands: the tier reached and when, and the next goal. */
    @Override
    public String getSubTitle(boolean includeTimestamp) {
        if (hidden && !isActive())
            return "A hidden achievement.";
        String when = includeTimestamp ? getFormattedTimestamp() : null;
        StringBuilder line = new StringBuilder(description);
        if (!isTiered()) {
            if (getBest() > 0)
                line.append("\nEarned").append(when != null ? " " + when : "");
            return line.toString();
        }
        int tier = tierIndex();
        line.append('\n');
        if (tier >= 0)
            line.append(TIER_NAMES[tier]).append(": ").append(goals[tier]).append(when != null ? " (" + when + ")" : "");
        if (tier < thresholds.length - 1)
            line.append(tier >= 0 ? " - next: " : "First: ").append(goals[tier + 1]);
        return line.toString();
    }

    /** The console's {@code achievement reset} (agent game only): back to not earned. */
    void resetForTest() {
        try {
            Element blank = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
                    .createElement("a");
            blank.setAttribute("best", "0");
            blank.setAttribute("time", "0");
            loadFromXml(blank);
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            System.err.println("[TFR-Achievement] reset failed: " + e);
        }
    }

    @Override
    public void saveToXml(Element el) {
        super.saveToXml(el);
        if (!seen.isEmpty())
            el.setAttribute("seen", String.join("|", seen));
    }

    @Override
    public void loadFromXml(Element el) {
        super.loadFromXml(el);
        seen.clear();
        String list = el.getAttribute("seen");
        if (list != null && !list.isEmpty())
            for (String name : list.split("\\|"))
                if (!name.isEmpty())
                    seen.add(name);
    }
}
