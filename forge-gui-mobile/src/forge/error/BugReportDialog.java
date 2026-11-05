package forge.error;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinColor.Colors;
import forge.assets.FSkinFont;
import forge.gui.GuiBase;
import forge.gui.error.BugReporter;
import forge.screens.FScreen;
import forge.toolbox.FButton;
import forge.toolbox.FScrollPane;
import forge.toolbox.FTextArea;
import forge.util.Localizer;
import forge.util.TextBounds;
import forge.util.Utils;

import java.util.function.Consumer;

public class BugReportDialog extends FScreen { //use screen rather than dialog so screen with bug isn't rendered
    private static final float PADDING = Utils.scale(5);
    private static final float BUTTON_HEIGHT = Utils.AVG_FINGER_HEIGHT * 0.75f;
    private static boolean isOpen;
    private static int unshownLogged; // TFR round 934: at most 5 lines for reports no dialog could show

    public static void show(String title, String text, boolean showExitAppBtn) {
        // TFR round 974: a report from another thread (a duel's game thread) opens on the GL thread. Opened where it was
        // raised, the screen switch raced the frame being drawn; Gdx.app is null only before the app exists.
        if (Gdx.app != null && GuiBase.getInterface() != null && !GuiBase.getInterface().isGuiThread())
            Gdx.app.postRunnable(() -> showHere(title, text, showExitAppBtn));
        else
            showHere(title, text, showExitAppBtn);
    }

    private static void showHere(String title, String text, boolean showExitAppBtn) {
        if (isOpen) { return; }
        if (showExitAppBtn) // TFR round 816: a quit from here never counts; marked first, as opening may fail
            forge.adventure.util.DuelHeartbeat.onError("a crash report: " + text);
        String notShownWhy = null;
        if (Forge.getCurrentScreen() == null) {
            notShownWhy = "no dialog outside a duel"; //don't allow showing if Forge not finished initializing yet
        } else {
            isOpen = true;
            try {
                BugReportDialog dialog = new BugReportDialog(title, text, showExitAppBtn);
                Forge.openScreen(dialog);
                if (Forge.getCurrentScreen() != dialog)
                    notShownWhy = "the screen didn't switch to it, " + Forge.getCurrentScreen();
            } catch (RuntimeException e) {
                notShownWhy = "it failed to open: " + e;
            }
            if (notShownWhy != null)
                isOpen = false;
        }
        // TFR round 934 (Fable P3-59): a crash outside a duel (every adventure scene has no Forge screen; a world-load OOM)
        // still gets its [TFR-Error] line, errors= count and oom reading. Round 974: "shown" only once it is on screen.
        if (showExitAppBtn && (notShownWhy == null || unshownLogged++ < 5)) // TFR round 815: counts in errors=
            forge.adventure.util.SessionLog.onCrashReport(text, notShownWhy);
    }

    /**
     * TFR round 974 (Fable P2-20), Android's GL or UI thread died (ExceptionHandler.setFatalThreads, from the launcher):
     * the last lines before Android ends the process. No dialog of ours can draw without the GL thread, so the error is
     * counted, the duel marked (a quit from here never counts), the session footer written, and the trace printed here,
     * as the launcher's own handler logs it only to logcat.
     */
    public static void onFatalThreadError(Thread t, Throwable ex) {
        System.err.print(t.getName() + " > ");
        ex.printStackTrace();
        forge.adventure.util.DuelHeartbeat.onError("a crash: " + ex);
        forge.adventure.util.SessionLog.onCrashReport(String.valueOf(ex), "the app closes: " + t.getName()
                + " died, and no dialog draws without it; the next launch starts clean");
        forge.adventure.util.SessionLog.end("crash");
    }

    private final FTextArea lblHeader = add(new FTextArea(false, "Report Bug"));
    private final TemplateView tvDetails;
    private final FButton btnReport = add(new FButton(Localizer.getInstance().getMessage("lblReport")));
    private final FButton btnSave = add(new FButton(Localizer.getInstance().getMessage("lblSave")));
    private final FButton btnDiscard = add(new FButton(Localizer.getInstance().getMessage("lblDiscardError")));
    private final FButton btnExit = add(new FButton(Localizer.getInstance().getMessage("lblExit")));

    private BugReportDialog(String title, String text0, boolean showExitAppBtn) {
        super(title);
        lblHeader.setFont(FSkinFont.get(12));
        tvDetails = add(new TemplateView(text0));
        btnReport.setCommand(e -> {
            BugReporter.sendSentry();
            Forge.back();
        });
        // TFR round 934 (Fable P3-58): Report only where Sentry runs. TFR never initializes it (Android's manifest turns
        // auto-init off), so the tap sent nothing; Save and the Settings' Export Logs are the ways to send a report.
        btnReport.setVisible(sentryRunning());
        btnSave.setCommand(e -> {
            BugReporter.saveToFile(tvDetails.text);
            Forge.back();
        });
        btnDiscard.setCommand(e -> Forge.back());
        if (showExitAppBtn) {
            btnExit.setCommand(e -> Forge.exit(true));
        }
        else {
            btnExit.setVisible(false);
        }
    }

    private static boolean sentryRunning() {
        try {
            return io.sentry.Sentry.isEnabled();
        } catch (Throwable t) { // TFR round 934: no Sentry on the classpath is "not running"
            return false;
        }
    }

    @Override
    public FScreen getLandscapeBackdropScreen() {
        return null;
    }

    @Override
    public void onClose(Consumer<Boolean> canCloseCallback) {
        super.onClose(canCloseCallback);
        isOpen = false;
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        float x = PADDING;
        float y = startY + PADDING;
        float w = width - 2 * PADDING;

        lblHeader.setBounds(x, y, w, lblHeader.getPreferredHeight(w));
        y += lblHeader.getHeight() + PADDING;

        float buttonWidth, totalButtonHeight;
        float buttonHeight = BUTTON_HEIGHT;
        boolean landscapeMode = Forge.isLandscapeMode();
        if (landscapeMode) {
            buttonWidth = (w - 3 * PADDING) / 4;
            totalButtonHeight = buttonHeight;
        }
        else {
            buttonWidth = (w - PADDING) / 2;
            totalButtonHeight = 2 * buttonHeight + PADDING;
        }

        tvDetails.setBounds(x, y, w, height - totalButtonHeight - 2 * PADDING - y);
        y += tvDetails.getHeight() + PADDING;

        if (btnReport.isVisible()) {
            btnReport.setBounds(x, y, buttonWidth, buttonHeight);
            btnSave.setBounds(x + buttonWidth + PADDING, y, buttonWidth, buttonHeight);
        } else { // TFR round 934: Save takes Report's place too
            btnSave.setBounds(x, y, 2 * buttonWidth + PADDING, buttonHeight);
        }
        if (landscapeMode) {
            x += 2 * (buttonWidth + PADDING);
        }
        else {
            y += buttonHeight + PADDING;
        }
        if (btnExit.isVisible()) {
            btnDiscard.setBounds(x, y, buttonWidth, buttonHeight);
            btnExit.setBounds(x + buttonWidth + PADDING, y, buttonWidth, buttonHeight);
        }
        else {
            btnDiscard.setBounds(x, y, 2 * buttonWidth + PADDING, buttonHeight);
        }
    }

    private static class TemplateView extends FScrollPane {
        private static final FSkinFont FONT = FSkinFont.get(11);
        private static final FSkinColor BACK_COLOR = FSkinColor.get(Colors.CLR_ZEBRA);
        private static final FSkinColor FORE_COLOR = FSkinColor.get(Colors.CLR_TEXT);
        private static final FSkinColor BORDER_COLOR = FSkinColor.get(Colors.CLR_BORDERS);
        private static final float PADDING = Utils.scale(3);

        private final String text;

        private TemplateView(String text0) {
            text = text0;
            setHeight(Forge.getScreenHeight() / 3);
        }

        @Override
        protected ScrollBounds layoutAndGetScrollBounds(float visibleWidth, float visibleHeight) {
            TextBounds bounds = FONT.getMultiLineBounds(text);
            return new ScrollBounds(bounds.width + 2 * PADDING, bounds.height + 2 * PADDING +
                    FONT.getLineHeight() - FONT.getCapHeight()); //account for height below baseline of final line);
        }

        @Override
        public void drawBackground(Graphics g) {
            g.fillRect(BACK_COLOR, 0, 0, getWidth(), getHeight());
            g.drawText(text, FONT, FORE_COLOR, PADDING - getScrollLeft(), PADDING - getScrollTop(), getScrollWidth() - 2 * PADDING, getScrollHeight() - 2 * PADDING, false, Align.left, false);
        }

        @Override
        public void drawOverlay(Graphics g) {
            super.drawOverlay(g);
            g.drawRect(1, BORDER_COLOR, 0, 0, getWidth(), getHeight());
        }
    }
}
