package forge.adventure.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * TFR round 1041 (logs-1116 P2-5): Android's java.util.regex is ICU, which throws PatternSyntaxException on a bare
 * {@code }} that desktop Java reads as a literal ({@code "\\{([\\w.]+)}"} in TfrTips.fill hid 1.29's War Begins pages
 * from every Android player and crashed "The War"). Desktop can't run ICU, so this scans the source every Android build
 * runs for regex string literals (Pattern.compile / replaceAll / replaceFirst / split / matches) and fails on a bare
 * {@code }} outside a {n,m} quantifier, or a bare {@code ]} outside a character class. Escape the character instead.
 * Patterns built from variables or constants are out of its reach.
 */
public class RegexAndroidSafeTest {
    /** The modules an Android build runs, relative to forge-gui-mobile (the test's working directory). */
    private static final String[] ROOTS = {
            "src", "../forge-gui/src/main/java", "../forge-core/src/main/java", "../forge-game/src/main/java",
            "../forge-ai/src/main/java", "../forge-gui-android/src"};

    /** Stock lines left as upstream ships them: a bare ] (ICU reads ] outside a class as a literal; only } throws). */
    private static final List<String> STOCK_ALLOWED = Arrays.asList(
            "CardDetailUtil.java: LEVEL [0-9]+-[0-9]+ \\\\[[0-99]+/[0-99]]+ ",
            "CardDetailUtil.java: LEVEL [0-9]+\\\\+ \\\\[[0-99]+/[0-99]]+ ");

    private static final Pattern CALL = Pattern.compile(
            "(?:Pattern\\.compile|\\.replaceAll|\\.replaceFirst|\\.split|\\.matches)\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern QUANTIFIER = Pattern.compile("\\{\\d+(,\\d*)?\\}");

    /** A Java string literal's body as the regex it becomes. */
    static String unescapeJava(String lit) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lit.length(); i++) {
            char c = lit.charAt(i);
            if (c == '\\' && i + 1 < lit.length()) {
                char n = lit.charAt(++i);
                switch (n) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case '"': case '\'': case '\\': out.append(n); break;
                    default: out.append('\\').append(n);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The first bare } or ] in a regex (a description), or null when ICU-safe. */
    static String problem(String r) {
        boolean inClass = false;
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            if (c == '\\') {
                if (i + 1 < r.length() && r.charAt(i + 1) == 'Q') { // \Q...\E quotes everything
                    int e = r.indexOf("\\E", i + 2);
                    i = e < 0 ? r.length() : e + 1;
                    continue;
                }
                if (i + 2 < r.length() && "pPxN".indexOf(r.charAt(i + 1)) >= 0 && r.charAt(i + 2) == '{') {
                    int e = r.indexOf('}', i);
                    if (e < 0)
                        return "unclosed \\" + r.charAt(i + 1) + "{ at " + i;
                    i = e;
                    continue;
                }
                i++;
                continue;
            }
            if (inClass) {
                if (c == ']')
                    inClass = false;
                continue;
            }
            if (c == '[') {
                inClass = true;
                int j = i + 1;
                if (j < r.length() && r.charAt(j) == '^')
                    j++;
                if (j < r.length() && r.charAt(j) == ']') // a leading ] is part of the class
                    j++;
                i = j - 1;
            } else if (c == '{') {
                Matcher q = QUANTIFIER.matcher(r).region(i, r.length());
                if (q.lookingAt())
                    i = q.end() - 1;
            } else if (c == '}') {
                return "bare } at index " + i + " (Android throws PatternSyntaxException)";
            } else if (c == ']') {
                return "bare ] at index " + i;
            }
        }
        return null;
    }

    @Test
    public void catchesTheOneThatHid129sWarPages() {
        // round 1005's TfrTips.fill pattern, as it shipped in 1.29
        AssertJUnit.assertNotNull(problem(unescapeJava("\\\\{([\\\\w.]+)}")));
        AssertJUnit.assertNotNull(problem(unescapeJava("(\\\\[\\\\+\\\\w+])")));
        // and their round-1041 forms, plus the shapes that are fine
        AssertJUnit.assertNull(problem(unescapeJava("\\\\{([\\\\w.]+)\\\\}")));
        AssertJUnit.assertNull(problem(unescapeJava("(\\\\[\\\\+\\\\w+\\\\])")));
        AssertJUnit.assertNull(problem("a{2,3}b{4}[]x]\\p{M}+\\Q}]\\E[^]]"));
    }

    @Test
    public void tfrSourceHasNoIcuBreakingRegex() throws IOException {
        Path base = Paths.get("").toAbsolutePath();
        List<String> bad = new ArrayList<>();
        int scanned = 0;
        for (String root : ROOTS) {
            Path dir = base.resolve(root).normalize();
            if (!Files.isDirectory(dir))
                continue;
            List<Path> files;
            try (Stream<Path> s = Files.walk(dir)) {
                files = s.filter(p -> p.toString().endsWith(".java")).collect(java.util.stream.Collectors.toList());
            }
            for (Path f : files) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int n = 0; n < lines.size(); n++) {
                    Matcher m = CALL.matcher(lines.get(n));
                    while (m.find()) {
                        scanned++;
                        String why = problem(unescapeJava(m.group(1)));
                        if (why != null && !STOCK_ALLOWED.contains(f.getFileName() + ": " + m.group(1)))
                            bad.add(base.relativize(f) + ":" + (n + 1) + "  \"" + m.group(1) + "\"  " + why);
                    }
                }
            }
        }
        AssertJUnit.assertTrue("found no regex literals - wrong working directory? " + base, scanned > 100);
        AssertJUnit.assertTrue("regexes Android's ICU engine may reject (escape the } or ]):\n" + String.join("\n", bad),
                bad.isEmpty());
    }
}
