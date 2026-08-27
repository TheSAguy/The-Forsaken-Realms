package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TextraLabel;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.util.Controls;

import java.util.List;

/**
 * Reusable scrollable long-text info page (2026-08-14 user request), replacing an earlier
 * Dialog-based attempt at the same thing (EconomyBuildings.buildGuardInfoDialog()'s ScrollPane
 * wrap, same day). That approach was fundamentally broken, not just under-tuned: Dialog.show()
 * calls pack(), which sizes the WHOLE window from its content with no way to cap it below the
 * 480x270 (270x480 portrait) virtual screen every scene in this game is laid out on - a height
 * cap on the INNER ScrollPane's own cell does nothing to stop the OUTER Dialog from packing
 * taller than the screen itself. This class instead follows QuestLogScene's proven shape: a full
 * Scene with a JSON-authored, FIXED-size scroll region (no pack() anywhere in the loop) - the
 * same shape the Inn's own working "tournament info" scroll (EventScene's blessingInfo) already
 * uses. One singleton instance is reused for every caller (Guard Info, Mod Details, and any
 * future long-text page) via show().
 */
public class InfoTextScene extends UIScene {
    private static InfoTextScene object;
    // A JSON "type": "Label" element actually instantiates Controls.newTextraLabel() under the
    // hood (UIActor.java's "Label" case), i.e. a TextraLabel (Controls.LabelFix), never the raw
    // libGDX Label class - findActor("title") threw a real ClassCastException the first time this
    // scene was ever actually opened in-game (2026-08-15 crash report), since every prior round's
    // "not yet playtested" status meant this line had never actually executed before.
    private final TextraLabel titleLabel;
    private final Table content = new Table();

    private InfoTextScene() {
        super(Forge.isLandscapeMode() ? "ui/info_text.json" : "ui/info_text_portrait.json");
        titleLabel = ui.findActor("title");
        Table root = ui.findActor("textArea");
        ui.onButtonPress("return", InfoTextScene.this::back);
        // "nobg" (2026-08-24 user report: the Welcome popup's text was hard to read) - the
        // default ScrollPaneStyle's background is "windowMain10Patch", a dark window texture
        // that was rendering as a solid dark panel stacked on top of this scene's own parchment
        // "paper"-style Window behind it (ui/info_text.json's "scrollWindow"). The skin already
        // defines a transparent style for exactly this ("nobg": {"background": "transparent"}),
        // so the parchment shows through cleanly instead of double-layering two backgrounds.
        ScrollPane scroller = new ScrollPane(content, Controls.getSkin(), "nobg");
        scroller.setScrollingDisabled(true, false); // vertical-only, same as QuestLogScene's detailScroller
        root.add(scroller).expand().fill();
    }

    public static InfoTextScene instance() {
        if (object == null)
            object = new InfoTextScene();
        return object;
    }

    /** Rebuilds the scrollable body from a fresh paragraph list - each String is one wrapped,
     *  black TypingLabel row, matching the plain-text style every other info dialog in this mod
     *  already uses (addContentRow() in EconomyBuildings.java, WorldStandingsScene's wiki dialogs). */
    private void setContent(String title, List<String> paragraphs, String linkLabel, String linkUrl) {
        titleLabel.setText(title);
        content.clear();
        content.row();
        for (String paragraph : paragraphs) {
            TypingLabel label = Controls.newTypingLabel(paragraph);
            label.setWrap(true);
            label.setColor(Color.BLACK);
            label.skipToTheEnd();
            content.add(label).align(Align.left).expandX().fillX().padBottom(10).row();
        }
        // Optional real link button (2026-08-26 user request: "Can the Discord link be an actual
        // hyper link on the info page?") - inline clickable text is finicky in this UI stack, so
        // a proper button that opens the system browser is the reliable cross-platform answer
        // (Gdx.net.openURI works on both desktop and Android, which matters for the planned
        // Android release).
        if (linkUrl != null && !linkUrl.isEmpty()) {
            content.add(Controls.newTextButton(linkLabel != null ? linkLabel : linkUrl,
                    () -> com.badlogic.gdx.Gdx.net.openURI(linkUrl))).align(Align.left).padBottom(10).row();
        }
    }

    /** Entry point every caller uses - builds the content, then switches to this scene (pushing
     *  the caller onto Forge's scene stack, same as QuestLogScene.instance(Forge.getCurrentScene())
     *  elsewhere, so the "Back" button's inherited UIScene.back() -> Forge.switchToLast() returns
     *  to exactly whichever screen opened this one). */
    public static void show(String title, List<String> paragraphs) {
        show(title, paragraphs, null, null);
    }

    /** As above, plus an optional link button appended after the text (null linkUrl = no button). */
    public static void show(String title, List<String> paragraphs, String linkLabel, String linkUrl) {
        instance().setContent(title, paragraphs, linkLabel, linkUrl);
        Forge.switchScene(instance(), true);
    }

    @Override
    public void dispose() { }
}
