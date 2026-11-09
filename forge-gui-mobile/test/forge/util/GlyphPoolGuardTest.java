package forge.util;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFont.BitmapFontData;
import com.badlogic.gdx.graphics.g2d.BitmapFont.Glyph;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Pool;

/**
 * TFR round 995 (logs-1109b P2-1): the End Turn caption laid out from a damaged libGDX glyph run pool, headless. A
 * duplicate run in the pool breaks "End\nTurn" and stays there; a null throws the players' NPE; heal fixes both.
 */
public class GlyphPoolGuardTest {
    private static final String END_TURN = "End\nTurn";
    private static final float BUTTON_WIDTH = 200;

    /** A monospaced font with no texture: BitmapFont skips GL when given a region, and load() only maps the pages. */
    private static BitmapFont headlessFont() {
        BitmapFontData data = new BitmapFontData();
        data.lineHeight = 20;
        data.down = -20;
        data.capHeight = 14;
        data.xHeight = 10;
        data.spaceXadvance = 6;
        for (char c = 32; c < 127; c++) {
            Glyph glyph = new Glyph();
            glyph.id = c;
            glyph.width = 8;
            glyph.height = 14;
            glyph.xadvance = 10;
            data.setGlyph(c, glyph);
        }
        Array<TextureRegion> regions = new Array<>();
        regions.add(new TextureRegion());
        return new BitmapFont(data, regions, true) {
            @Override
            protected void load(BitmapFontData fontData) {
            }
        };
    }

    /** Each run's text, x and y: what the button draws. */
    private static String describe(GlyphLayout layout) {
        StringBuilder sb = new StringBuilder();
        for (GlyphLayout.GlyphRun run : layout.runs) {
            StringBuilder text = new StringBuilder();
            for (Glyph glyph : run.glyphs)
                text.append((char) glyph.id);
            sb.append('[').append(text).append(" x=").append(run.x).append(" y=").append(run.y).append(']');
        }
        return sb.toString();
    }

    private static void layOut(GlyphLayout layout, BitmapFont font) {
        layout.setText(font, END_TURN, Color.WHITE, BUTTON_WIDTH, Align.center, false);
    }

    @BeforeMethod
    @AfterMethod
    public void reset() {
        GlyphPoolGuard.resetForTest();
        GlyphPoolGuard.heal("test reset");
        GlyphPoolGuard.resetForTest();
    }

    @Test
    public void aDuplicateRunBreaksTheEndTurnCaptionUntilHealed() {
        BitmapFont font = headlessFont();
        GlyphLayout layout = new GlyphLayout();
        layOut(layout, font);
        String clean = describe(layout);
        AssertJUnit.assertEquals("a clean pool centers each line", "[End x=86.0 y=0.0][Turn x=81.0 y=-20.0]", clean);

        // the race's result: one run in the free list twice, on top
        layout.reset();
        GlyphPoolGuard.heal("test");
        Pool<GlyphLayout.GlyphRun> pool = GlyphPoolGuard.runPoolForTest();
        AssertJUnit.assertNotNull("the guard reads libGDX's pool", pool);
        GlyphLayout.GlyphRun run = new GlyphLayout.GlyphRun();
        pool.free(run);
        pool.free(run);

        layOut(layout, font);
        AssertJUnit.assertEquals(2, layout.runs.size);
        AssertJUnit.assertSame("both lines get the same run", layout.runs.get(0), layout.runs.get(1));
        AssertJUnit.assertFalse("the caption is no longer \"End\" over \"Turn\", centered: " + describe(layout),
                clean.equals(describe(layout)));

        // libGDX never drops it: freeing the layout puts the run back twice, and the next caption breaks the same way
        for (int frame = 0; frame < 5; frame++) {
            layOut(layout, font);
            AssertJUnit.assertSame("frame " + frame, layout.runs.get(0), layout.runs.get(1));
        }
        layout.reset();
        AssertJUnit.assertEquals(1, GlyphPoolGuard.scan().duplicates);

        GlyphPoolGuard.Report report = GlyphPoolGuard.heal("test");
        AssertJUnit.assertEquals(1, report.duplicates);
        AssertJUnit.assertEquals(0, report.nulls);
        AssertJUnit.assertEquals("cleared", 0, pool.getFree());
        layOut(layout, font);
        AssertJUnit.assertEquals("healed", clean, describe(layout));
    }

    @Test
    public void aNullRunThrowsThePlayersNpeAndHealDropsIt() {
        BitmapFont font = headlessFont();
        GlyphLayout layout = new GlyphLayout();
        GlyphPoolGuard.plantNullForTest();
        AssertJUnit.assertEquals(1, GlyphPoolGuard.scan().nulls);
        try {
            layOut(layout, font);
            AssertJUnit.fail("a null run should throw");
        } catch (NullPointerException expected) {
            // the players' "[TFR-Render] text not drawn: NullPointerException: Cannot assign field "x" because "run" is null"
            AssertJUnit.assertTrue(String.valueOf(expected.getMessage()), String.valueOf(expected.getMessage()).contains("\"run\" is null"));
        }
        // a null is popped by the layout it breaks; one the heal finds is dropped before any layout gets it
        layout.reset();
        GlyphPoolGuard.plantNullForTest();
        GlyphPoolGuard.Report report = GlyphPoolGuard.heal("test");
        AssertJUnit.assertEquals(1, report.nulls);
        layOut(layout, font);
        AssertJUnit.assertEquals("[End x=86.0 y=0.0][Turn x=81.0 y=-20.0]", describe(layout));
    }

    @Test
    public void aCleanPoolHealsSilentlyAfterTheFirstLine() {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(captured, true);
        System.setOut(capture);
        System.setErr(capture);
        try {
            for (int i = 0; i < 5; i++)
                AssertJUnit.assertFalse(GlyphPoolGuard.heal("turn start").damaged());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        String log = captured.toString();
        AssertJUnit.assertEquals(log, 1, log.split("\\[TFR-GlyphPool\\]", -1).length - 1);
        AssertJUnit.assertTrue(log, log.contains("guard on: first heal at turn start"));
    }

    @Test
    public void offThreadLayoutIsLoggedOnceAndHealWaitsForTheGlThread() throws Exception {
        GlyphPoolGuard.setGlThreadForTest(Thread.currentThread());
        GlyphPoolGuard.checkThread("on the GL thread");
        AssertJUnit.assertEquals(0, GlyphPoolGuard.offThreadLayoutsForTest());

        Pool<GlyphLayout.GlyphRun> pool = GlyphPoolGuard.runPoolForTest();
        pool.free(new GlyphLayout.GlyphRun());
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        GlyphPoolGuard.Report[] offThreadHeal = new GlyphPoolGuard.Report[1];
        try {
            Thread worker = new Thread(() -> {
                GlyphPoolGuard.checkThread("FSkinFont.getBounds");
                GlyphPoolGuard.checkThread("FSkinFont.getBounds");
                offThreadHeal[0] = GlyphPoolGuard.heal("off thread");
            }, "game-thread-test");
            worker.start();
            worker.join();
        } finally {
            System.setErr(err);
        }
        AssertJUnit.assertEquals(2, GlyphPoolGuard.offThreadLayoutsForTest());
        String log = captured.toString();
        AssertJUnit.assertTrue(log, log.contains("[TFR-GlyphPool] text laid out off the GL thread: game-thread-test in FSkinFont.getBounds"));
        AssertJUnit.assertEquals("logged once", log.indexOf("text laid out off"), log.lastIndexOf("text laid out off"));
        AssertJUnit.assertNull("a heal off the GL thread does not touch the pool", offThreadHeal[0]);
        AssertJUnit.assertEquals(1, pool.getFree());
    }
}
