package forge.game;

import forge.StaticData;
import forge.ai.AITest;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * TFR round 931 (logs-1102b P1-2): DraftEffect looks every Spellbook$ name up with getUniqueByName and no null check, so
 * one misspelled name (Thoughtsteal Sprites' "Vendillion Clique") ended about 1 draft in 5 in a crash. Every name in
 * every card script's spellbook must be a card the database knows (or its "A-" rebalanced printing, as DraftEffect tries).
 */
public class SpellbookNamesTest extends AITest {
    private static final Pattern SPELLBOOK = Pattern.compile("Spellbook\\$\\s*([^|\\r\\n]+)");

    @Test
    public void everySpellbookNameIsACard() throws IOException {
        List<String> bad = new ArrayList<>();
        int books = 0, names = 0;
        try (Stream<Path> files = Files.walk(Paths.get(ForgeConstants.CARD_DATA_DIR))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".txt"))::iterator) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher m = SPELLBOOK.matcher(text);
                while (m.find()) {
                    books++;
                    // As DraftEffect reads it: the param trimmed, split on commas, no trim per name, and a ";" standing
                    // for a comma inside a name (Tovolar; Dire Overlord).
                    for (String raw : m.group(1).trim().split(",")) {
                        String name = raw.replace(";", ",");
                        names++;
                        if (find(name) == null && find("A-" + name) == null)
                            bad.add(file.getFileName() + ": " + name);
                    }
                }
            }
        }
        System.out.println("[SpellbookNamesTest] " + books + " spellbooks, " + names + " names, " + bad.size() + " unknown");
        AssertJUnit.assertTrue("no spellbooks found under " + ForgeConstants.CARD_DATA_DIR, books > 0);
        AssertJUnit.assertTrue("spellbook names with no card: " + bad, bad.isEmpty());
    }

    private static PaperCard find(String name) {
        PaperCard pc = StaticData.instance().getCommonCards().getUniqueByName(name);
        if (pc == null) {
            StaticData.instance().attemptToLoadCard(name);
            pc = StaticData.instance().getCommonCards().getUniqueByName(name);
        }
        return pc;
    }
}
