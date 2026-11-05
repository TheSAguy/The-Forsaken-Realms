package forge.adventure.pointofintrest;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import forge.adventure.data.DialogData;
import forge.adventure.data.PointOfInterestData;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * TFR round 970 (Fable P2-16): a place's quest-flag gates come back from the template on every load (stock never read
 * them back, so after any load every gated place was open). The plane keeps three gates, all on mainQuest 2; the seven
 * relic-trail places lost theirs (quests 50 and 51 sent the player to two of them before their gates opened, and nothing
 * set the second one).
 */
public class PointOfInterestGateTest {
    private static final File PLANE = new File("../forge-gui/res/adventure/The Forsaken Realms");

    private static DialogData.ActionData.QuestFlag flag(String key, int val) {
        DialogData.ActionData.QuestFlag f = new DialogData.ActionData.QuestFlag();
        f.key = key;
        f.val = val;
        return f;
    }

    @Test
    public void gatesAreRebuiltFromTheTemplate() {
        PointOfInterestData template = new PointOfInterestData();
        template.questFlagsToActivate = new DialogData.ActionData.QuestFlag[]{flag("mainQuest", 2)};
        List<DialogData.ActionData.QuestFlag> gates = PointOfInterest.gatesOf(template);
        AssertJUnit.assertEquals(1, gates.size());
        AssertJUnit.assertEquals("mainQuest", gates.get(0).key);
        AssertJUnit.assertEquals(2, gates.get(0).val);
        AssertJUnit.assertTrue(PointOfInterest.gatesOf(new PointOfInterestData()).isEmpty());
        AssertJUnit.assertTrue(PointOfInterest.gatesOf(null).isEmpty());
        template.questFlagsToActivate = null;
        AssertJUnit.assertTrue(PointOfInterest.gatesOf(template).isEmpty());
    }

    @Test
    public void aGateOpensAtItsValue() {
        List<DialogData.ActionData.QuestFlag> gates = new ArrayList<>();
        gates.add(flag("mainQuest", 2));
        Map<String, Integer> flags = new HashMap<>();
        AssertJUnit.assertEquals("mainQuest 0 < 2", PointOfInterest.closedBy(gates, k -> flags.getOrDefault(k, 0)));
        flags.put("mainQuest", 1);
        AssertJUnit.assertEquals("mainQuest 1 < 2", PointOfInterest.closedBy(gates, k -> flags.getOrDefault(k, 0)));
        flags.put("mainQuest", 2);
        AssertJUnit.assertNull(PointOfInterest.closedBy(gates, k -> flags.getOrDefault(k, 0)));
        flags.put("mainQuest", 3);
        AssertJUnit.assertNull(PointOfInterest.closedBy(gates, k -> flags.getOrDefault(k, 0)));
        AssertJUnit.assertNull(PointOfInterest.closedBy(new ArrayList<>(), k -> 0));
        AssertJUnit.assertEquals("mainQuest >= 2", PointOfInterest.describe(gates));
    }

    /** The plane's gates: exactly the three mainQuest 2 places. */
    @Test
    public void thePlaneGatesOnlyTheThreeMainQuestPlaces() {
        Map<String, String> gated = new TreeMap<>();
        for (JsonValue poi : readJson(new File(PLANE, "world/points_of_interest.json"))) {
            JsonValue gates = poi.get("questFlagsToActivate");
            if (gates == null || gates.size == 0)
                continue;
            StringBuilder sb = new StringBuilder();
            for (JsonValue g : gates)
                sb.append(g.getString("key")).append(' ').append(g.getInt("val"));
            gated.put(poi.getString("name"), sb.toString());
        }
        Map<String, String> expected = new TreeMap<>();
        expected.put("Emrakul", "mainQuest 2");
        expected.put("Temple of Chandra", "mainQuest 2");
        expected.put("Temple of Liliana", "mainQuest 2");
        AssertJUnit.assertEquals(expected, gated);
    }

    /** Every gate the plane keeps can open: some quest or map dialog sets its flag to at least its value (the
     *  relic trail's exploreShand1 9 never was). */
    @Test
    public void everyGateFlagIsSetSomewhere() throws IOException {
        Map<String, Integer> highest = new HashMap<>();
        Pattern set = Pattern.compile("\"setQuestFlag\"\\s*:\\s*\\{\\s*\"key\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"val\"\\s*:\\s*(\\d+)");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(PLANE.toPath())) {
            files = walk.filter(p -> p.toString().endsWith(".json") || p.toString().endsWith(".tmx")).toList();
        }
        for (Path p : files) {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace("&quot;", "\"");
            Matcher m = set.matcher(text);
            while (m.find())
                highest.merge(m.group(1), Integer.parseInt(m.group(2)), Math::max);
        }
        for (JsonValue poi : readJson(new File(PLANE, "world/points_of_interest.json"))) {
            JsonValue gates = poi.get("questFlagsToActivate");
            if (gates == null)
                continue;
            for (JsonValue g : gates)
                AssertJUnit.assertTrue(poi.getString("name") + "'s gate " + g.getString("key") + " " + g.getInt("val")
                        + " is never reached", highest.getOrDefault(g.getString("key"), 0) >= g.getInt("val"));
        }
    }

    private static JsonValue readJson(File file) {
        AssertJUnit.assertTrue(file.getAbsolutePath() + " not found", file.isFile());
        return new JsonReader().parse(new com.badlogic.gdx.files.FileHandle(file));
    }
}
