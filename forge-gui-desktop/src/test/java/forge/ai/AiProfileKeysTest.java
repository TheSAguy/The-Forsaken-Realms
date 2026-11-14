package forge.ai;

import static org.testng.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.testng.annotations.Test;

/**
 * TFR round 1035: every shipped AI profile ({@code forge-gui/res/ai/*.ai}) loads with this build's {@link AiProps}.
 * {@code AiProfileUtil.loadProfile} throws on a key the code doesn't know ({@code AiProps.valueOf}), and a game that
 * can't load its profiles can't start a duel, so a profile key without the code that reads it (or a jar older than the
 * profile) fails here, not in the game. Also checks that TFR's Default.ai sets round 1035's trick allowance keys (the
 * attack half on).
 */
public class AiProfileKeysTest {
    private static final String DIR = "../forge-gui/res/ai";

    @Test
    public void everyShippedProfileLoads() {
        AiProfileUtil.loadAllProfiles(DIR); // throws IllegalArgumentException on an unknown key
        final List<String> profiles = AiProfileUtil.getAvailableProfiles();
        assertTrue(profiles.contains("Default"), "no Default profile in " + new File(DIR).getAbsolutePath() + ": " + profiles);
    }

    /**
     * The attack half is on; the block half's key is there but off (round 1035's follow-up: team-pump decks), so this
     * only checks that the key is set, not its value.
     */
    @Test
    public void defaultSetsTheTrickAllowance() throws Exception {
        final List<String> lines = Files.readAllLines(new File(DIR, "Default.ai").toPath(), StandardCharsets.UTF_8);
        assertTrue(lines.stream().anyMatch(l -> l.trim().startsWith("TRICK_ALLOWANCE_ON_BLOCK=")), "TRICK_ALLOWANCE_ON_BLOCK");
        assertTrue(lines.stream().anyMatch(l -> l.trim().equals("TRICK_ALLOWANCE_ON_ATTACK=true")), "TRICK_ALLOWANCE_ON_ATTACK");
    }
}
