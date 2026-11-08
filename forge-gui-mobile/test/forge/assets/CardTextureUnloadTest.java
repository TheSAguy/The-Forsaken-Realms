package forge.assets;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * TFR round 985 (logs-1108 P3-11): one bad card texture unload skips only that file. Before, one exception stopped the
 * loop and disposeTextures() then cleared the whole list, leaving the rest loaded for good.
 */
public class CardTextureUnloadTest {

    @Test
    public void oneFailureSkipsOnlyThatFile() {
        CardTextureUnload.resetFailureLogForTest();
        Set<String> loaded = new HashSet<>();
        for (int i = 0; i < 50; i++)
            loaded.add("card" + i + ".jpg");
        List<String> unloaded = new ArrayList<>();
        int failed = CardTextureUnload.unloadAll(loaded, path -> {
            if (path.equals("card7.jpg"))
                throw new IllegalStateException("bad texture");
            unloaded.add(path);
        });
        AssertJUnit.assertEquals(1, failed);
        AssertJUnit.assertEquals("every other file is unloaded", 49, unloaded.size());
        AssertJUnit.assertEquals("only the failed file stays listed, for the next unload", Set.of("card7.jpg"), loaded);
    }

    @Test
    public void anAddDuringTheLoopDoesNotStopIt() {
        CardTextureUnload.resetFailureLogForTest();
        Set<String> loaded = new HashSet<>();
        for (int i = 0; i < 20; i++)
            loaded.add("card" + i + ".jpg");
        List<String> unloaded = new ArrayList<>();
        int failed = CardTextureUnload.unloadAll(loaded, path -> {
            if (unloaded.isEmpty())
                loaded.add("downloaded.jpg"); // a download registers a new card mid-loop
            unloaded.add(path);
        });
        AssertJUnit.assertEquals(0, failed);
        AssertJUnit.assertEquals(20, unloaded.size());
        AssertJUnit.assertEquals("the card added mid-loop stays listed, so the next unload frees it",
                Set.of("downloaded.jpg"), loaded);
    }

    @Test
    public void theFailureIsLoggedOnceALaunch() {
        CardTextureUnload.resetFailureLogForTest();
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            Set<String> loaded = new HashSet<>(Set.of("a.jpg", "b.jpg", "c.jpg"));
            CardTextureUnload.unloadAll(loaded, path -> {
                throw new IllegalStateException("bad");
            });
            CardTextureUnload.unloadAll(loaded, path -> {
                throw new IllegalStateException("bad");
            });
        } finally {
            System.setErr(err);
        }
        String log = captured.toString();
        int lines = log.split("\\[TFR-Memory\\] card texture unload failed: ", -1).length - 1;
        AssertJUnit.assertEquals(log, 1, lines);
    }
}
