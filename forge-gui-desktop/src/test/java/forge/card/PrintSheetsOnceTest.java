package forge.card;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.Test;

import forge.ImageKeys;
import forge.StaticData;
import forge.gamesimulationtests.util.CardDatabaseHelper;
import forge.util.storage.IStorage;

/**
 * TFR round 985 (logs-1108 P3-9): Adventure builds the print sheets on a background thread after a load, while the GL
 * thread may ask for them for a booster at the same moment. They must be built once, and every caller must get that
 * one fully built storage.
 */
public class PrintSheetsOnceTest extends CardMockTestCase {

    private StaticData data;

    /**
     * No ImageKeys mock: Mockito's static mocks hold only on the thread that opened them, and the build runs on worker
     * threads here, so they reach the real ImageKeys (PaperCard.hasImage). That gets an empty picture folder instead,
     * which is also the real, now locked, ImageKeys code path the warm-up thread takes in the game.
     */
    @Override
    protected void initCardImageMocks() {
        try {
            String empty = Files.createTempDirectory("tfr-printsheets-pics").toFile().getPath() + File.separator;
            ImageKeys.initializeDirs(empty, new HashMap<>(), empty, empty, empty, empty, empty, empty, empty);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected void initializeStaticData() {
        // Its own eagerly loaded database (as on mobile), so no other test has built its print sheets already.
        data = CardDatabaseHelper.createStaticData("PrintSheetsOnceTest", false);
        useStaticData(data);
    }

    @Test
    public void concurrentCallersShareOneBuild() throws Exception {
        assertFalse(data.arePrintSheetsLoaded(), "a fresh database has not built its print sheets");
        final int callers = 6;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        PrintStream out = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        List<IStorage<PrintSheet>> results = new ArrayList<>();
        System.setOut(new PrintStream(captured, true));
        try {
            List<Future<IStorage<PrintSheet>>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return data.getPrintSheets();
                }));
            }
            go.countDown();
            for (Future<IStorage<PrintSheet>> f : futures)
                results.add(f.get(5, TimeUnit.MINUTES));
        } finally {
            System.setOut(out);
            pool.shutdownNow();
        }
        IStorage<PrintSheet> first = results.get(0);
        assertNotNull(first);
        assertTrue(first.size() > 0, "the sheets are built");
        for (IStorage<PrintSheet> r : results)
            assertSame(r, first, "every caller gets the one build");
        assertSame(data.getPrintSheets(), first, "a later call gets it too");
        assertTrue(data.arePrintSheetsLoaded());
        String log = captured.toString();
        int builds = log.split("\\[TFR-Perf\\] print sheets loaded on ", -1).length - 1;
        assertEquals(builds, 1, "one [TFR-Perf] line, for the one build");
    }
}
