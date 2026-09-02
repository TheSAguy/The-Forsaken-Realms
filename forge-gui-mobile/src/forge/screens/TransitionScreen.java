package forge.screens;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Timer;
import forge.Forge;
import forge.Graphics;
import forge.adventure.scene.ArenaScene;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.animation.ForgeAnimation;
import forge.assets.FSkin;
import forge.assets.FSkinImage;
import forge.assets.FSkinTexture;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.sound.SoundSystem;
import forge.toolbox.FContainer;
import forge.toolbox.FProgressBar;
import org.apache.commons.lang3.tuple.Pair;

public class TransitionScreen extends FContainer {
    private BGAnimation bgAnimation;
    private FProgressBar progressBar;
    Runnable runnable;
    TextureRegion textureRegion, screenUIBackground, playerAvatar;
    Texture vsTexture;
    String enemyAtlasPath, playerAvatarName, enemyAvatarName;
    // The key to look this enemy's win/loss record up under, kept SEPARATE from enemyAvatarName
    // (the on-screen label) because those two are not the same string once a plane enables tiered
    // display names. Defaults to enemyAvatarName, so every caller that doesn't set it behaves
    // exactly as before - see withEnemyStatKey().
    String enemyStatKey;
    private String message = "", playerRecord = "", enemyRecord = "";
    boolean matchTransition, isloading, isIntro, isFadeMusic, isArenaScene, isAlternate;
    GlyphLayout layout;

    public TransitionScreen(Runnable proc, TextureRegion screen, boolean enterMatch, boolean loading) {
        this(proc, screen, enterMatch, loading, false, false);
    }

    public TransitionScreen(Runnable proc, TextureRegion screen, String message) {//simple for custom transition
        this(proc, screen, true, false, false, false, false, message, null, "", "", "", "", "");
    }

    public TransitionScreen(Runnable proc, TextureRegion screen, boolean enterMatch, boolean loading, String loadingMessage) {
        this(proc, screen, enterMatch, loading, false, false, loadingMessage, null, "", "", "", "", "");
    }

    public TransitionScreen(Runnable proc, TextureRegion screen, boolean enterMatch, boolean loading, boolean intro, boolean fadeMusic) {
        this(proc, screen, enterMatch, loading, intro, fadeMusic, "", null, "", "", "", "", "");
    }

    public TransitionScreen(Runnable proc, TextureRegion screen, boolean enterMatch, boolean loading, boolean intro, boolean fadeMusic, String loadingMessage, TextureRegion player, String enemyAtlas, String playerName, String enemyName) {
        this(proc, screen, enterMatch, loading, intro, fadeMusic, loadingMessage, player, enemyAtlas, playerName, enemyName, "", "");
    }

    public TransitionScreen(Runnable proc, TextureRegion screen, boolean enterMatch, boolean loading, boolean intro, boolean fadeMusic, String loadingMessage, TextureRegion player, String enemyAtlas, String playerName, String enemyName, String playerRecord, String enemyRecord) {
        this(proc, screen, false, enterMatch, loading, intro, fadeMusic, loadingMessage, player, enemyAtlas, playerName, enemyName, playerRecord, enemyRecord);
    }
    public TransitionScreen(Runnable proc, TextureRegion screen, boolean alternate, boolean enterMatch, boolean loading, boolean intro, boolean fadeMusic, String loadingMessage, TextureRegion player, String enemyAtlas, String playerName, String enemyName, String playerRecord, String enemyRecord) {
        progressBar = new FProgressBar();
        progressBar.setMaximum(100);
        progressBar.setPercentMode(true);
        progressBar.setShowETA(false);
        bgAnimation = new BGAnimation();
        runnable = proc;
        textureRegion = screen;
        matchTransition = enterMatch;
        isloading = loading;
        isIntro = intro;
        isFadeMusic = fadeMusic;
        isAlternate = alternate;
        message = loadingMessage;
        this.playerRecord = playerRecord;
        this.enemyRecord = enemyRecord;
        Forge.advStartup = intro && Forge.selector.equals("Adventure");
        if (Forge.getCurrentScene() instanceof ArenaScene) {
            isArenaScene = true;
            screenUIBackground = ((ArenaScene) Forge.getCurrentScene()).getUIBackground();
        } else {
            isArenaScene = false;
            screenUIBackground = null;
        }
        playerAvatar = player;
        playerAvatarName = playerName;
        enemyAvatarName = enemyName;
        enemyStatKey = enemyName; // overridable via withEnemyStatKey() when label != identity
        enemyAtlasPath = enemyAtlas;
        vsTexture = Forge.getAssets().fallback_skins().get("vs");
        layout = new GlyphLayout();
    }

    /**
     * Sets the key used to look up this enemy's win/loss record, when that differs from the name
     * shown on screen. Real, reported bug (2026-08-29): the pre-fight VS screen always showed
     * "0 - 0" even against enemies the player had beaten many times, while the Statistics screen
     * showed the record correctly. The record is written under the enemy's RAW name
     * (DuelScene.afterGameEnd), but every overworld/dungeon/Arena caller passes
     * getTieredDisplayName() for the label - and this screen used that same string as the map key.
     * With tiered names on, "Adept Red Wizard" is stored while "Red Wizard (Adept)" is looked up,
     * so the get() always missed and fell through to the "0 - 0" default.
     * EnemyData.getTieredDisplayName()'s own contract says it is "never used for identity" - this
     * was the one place that rule was broken, because one field served as both label and key.
     * Fluent so it can be chained onto the constructor call at the Forge.setTransitionScreen()
     * sites without restructuring them.
     */
    public TransitionScreen withEnemyStatKey(String statKey) {
        if (statKey != null && !statKey.isEmpty())
            enemyStatKey = statKey;
        return this;
    }

    public TransitionScreen() {
    }

    public FProgressBar getProgressBar() {
        return progressBar;
    }

    @Override
    protected void doLayout(float width, float height) {

    }

    public boolean isMatchTransition() {
        return matchTransition;
    }

    public void disableMatchTransition() {
        matchTransition = false;
    }

    private class BGAnimation extends ForgeAnimation {
        float DURATION = isArenaScene || isAlternate ? 1.2f : 0.6f;
        private float progress = 0;
        TextureRegion enemyAvatar;

        public void drawBackground(Graphics g) {
            float percentage = progress / DURATION;
            float oldAlpha = g.getfloatAlphaComposite();
            if (percentage < 0) {
                percentage = 0;
            } else if (percentage > 1) {
                percentage = 1;
            }
            if (isFadeMusic) {
                try {
                    //fade out volume
                    SoundSystem.instance.fadeModifier(1 - percentage);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (isAlternate) {
                g.fillRect(Color.BLACK, 0, 0, Forge.getScreenWidth(), Forge.getScreenHeight());
                if (textureRegion != null) {
                    g.drawPortalFade(textureRegion, 0, 0, Forge.getScreenWidth(), Forge.getScreenHeight(), Math.min(percentage, 1f), true);
                }
            } else if (isloading) {
                // World Generation screen gets its own full-bleed background (user-supplied
                // "Main_Image.png", 2026-08-17) instead of the tiled ADV_BG_TEXTURE pattern -
                // scoped to exactly the two call sites that pass the "Generating World..."
                // message (NewGameScene's initial world-gen, SaveLoadScene's New Game Plus),
                // NOT "Loading World..." (Continue/Load) or the captionless mode-switch fade.
                boolean isWorldGen = Forge.isMobileAdventureMode && message != null
                        && message.equals(Forge.getLocalizer().getMessage("lblGeneratingWorld"));
                g.fillRect(Color.BLACK, 0, 0, Forge.getScreenWidth(), Forge.getScreenHeight());
                FSkinTexture bgTexture = isWorldGen ? FSkinTexture.ADV_WORLDGEN_BG
                        : (Forge.isMobileAdventureMode ? FSkinTexture.ADV_BG_TEXTURE : FSkinTexture.BG_TEXTURE);
                if (bgTexture != null) {
                    g.setAlphaComposite(percentage);
                    g.drawImage(bgTexture, 0, 0, Forge.getScreenWidth(), Forge.getScreenHeight());
                    g.setAlphaComposite(oldAlpha);
                }
                float xmod = Forge.getScreenHeight() > 2000 ? 1.5f : 1f;
                // Loading-icon size (user request 2026-08-17: "make it like 4x bigger" - the
                // small circular icon on non-worldgen loading screens, e.g. "Loading World...").
                // Upstream 08.26 draws its new full-art logo at 1x with landscape recentering;
                // this fork keeps its own 300x300 TFR icon in adv_logo.png, so the 4x sizing and
                // worldgen skip stay.
                xmod *= 4f;//static logo only
                float ymod = 0f;
                if (isWorldGen) {
                    // Main_Image.png is already a complete painted scene - skip the small
                    // circular loading icon on top of it, it would just look cluttered.
                } else if (FSkin.getLogo() != null) {
                    ymod = Forge.getScreenHeight() / 2f + (FSkin.getLogo().getHeight() * xmod) / 2;
                    g.drawImage(FSkin.getLogo(), Forge.getScreenWidth() / 2f - (FSkin.getLogo().getWidth() * xmod) / 2, Forge.getScreenHeight() / 2f - (FSkin.getLogo().getHeight() * xmod) / 2, FSkin.getLogo().getWidth() * xmod, FSkin.getLogo().getHeight() * xmod);
                } else {
                    ymod = Forge.getScreenHeight() / 2f + (FSkinImage.LOGO.getHeight() * xmod) / 1.5f;
                    g.drawImage(FSkinImage.LOGO, Forge.getScreenWidth() / 2f - (FSkinImage.LOGO.getWidth() * xmod) / 2, Forge.getScreenHeight() / 2f - (FSkinImage.LOGO.getHeight() * xmod) / 1.5f, FSkinImage.LOGO.getWidth() * xmod, FSkinImage.LOGO.getHeight() * xmod);
                }
                //loading progressbar - todo make this accurate when generating world
                if (Forge.isMobileAdventureMode) {
                    float w = Forge.isLandscapeMode() ? Forge.getScreenWidth() / 2f : Forge.getScreenHeight() / 2f;
                    float h = 57f / 450f * (w / 2);
                    float x = (Forge.getScreenWidth() - w) / 2;
                    float y = ymod + 10;
                    int multi = ((int) (percentage * 100)) < 97 ? (int) (percentage * 100) : 100;
                    progressBar.setBounds(x, Forge.getScreenHeight() - h * 2f, w, h);
                    progressBar.setValue(multi);
                    if (multi == 100 && !message.isEmpty()) {
                        progressBar.setDescription(message);
                    }
                    g.draw(progressBar);
                }
            } else if (matchTransition) {
                float screenW = Forge.isLandscapeMode() ? Forge.getScreenWidth() : Forge.getScreenHeight();
                float screenH = Forge.isLandscapeMode() ? Forge.getScreenHeight() : Forge.getScreenWidth();
                float scale = screenW / 4;
                float centerX = screenW / 2;
                float centerY = screenH / 2;
                enemyAvatar = Config.instance().getAtlas(enemyAtlasPath).createSprite("Avatar");
                if (enemyAvatar != null)
                    enemyAvatar.flip(true, false);
                float fontScale = GuiBase.isAndroid() ? 12f : 10f;
                BitmapFont font = Controls.getBitmapFont("default", fontScale / (screenW / screenH));
                if (textureRegion != null) {
                    if (isArenaScene)
                        g.drawImage(screenUIBackground, 0, 0, Forge.getScreenWidth(), Forge.getScreenHeight());
                    else
                        g.drawImage(FSkinTexture.ADV_BG_TEXTURE, 0, 0, Forge.getScreenWidth(), Forge.getScreenHeight());
                    g.setAlphaComposite(1 - percentage);
                    g.drawImage(textureRegion, 0, 0, Forge.getScreenWidth(), Forge.getScreenHeight());
                    g.setAlphaComposite(oldAlpha);
                }
                String p1Record = "0 - 0";
                String p2Record = "0 - 0";
                //stats
                if (playerRecord.length() > 0 && enemyRecord.length() > 0) {
                    // Explicit records supplied by the caller (Inn tournaments pass the BRACKET
                    // standings) now win outright. This used to fall through to the global
                    // adventure record below, which overwrote them whenever the player had ever
                    // fought an enemy of that name - showing a lifetime record earned with the
                    // player's own deck on the VS screen of a tournament played with the event's
                    // generated sealed/draft deck. Same principle as excluding tournaments from
                    // the win/loss totals in DuelScene.afterGameEnd (user decision 2026-08-29):
                    // a different deck's results don't belong in this matchup's record.
                    p1Record = playerRecord;
                    p2Record = enemyRecord;
                } else {
                    Pair<Integer, Integer> winloss = Current.player().getStatistic().getWinLossRecord().get(enemyStatKey);
                    if (winloss != null) {
                        p1Record = winloss.getKey() + " - " + winloss.getValue();
                        p2Record = winloss.getValue() + " - " + winloss.getKey();
                    }
                }
                if (Forge.isLandscapeMode()) {
                    //player
                    float playerAvatarX = (screenW / 4 - scale / 2) * percentage;
                    float playerAvatarY = centerY - scale / 2;
                    g.drawImage(playerAvatar, playerAvatarX, playerAvatarY, scale, scale);
                    layout.setText(font, playerAvatarName);
                    g.drawText(playerAvatarName, font, screenW / 4 - layout.width / 2, playerAvatarY - layout.height, Color.WHITE, percentage);
                    layout.setText(font, p1Record);
                    g.drawText(p1Record, font, screenW / 4 - layout.width / 2, playerAvatarY - layout.height * 2.5f, Color.WHITE, percentage);
                    //enemy
                    float enemyAvatarX = screenW - screenW / 4 - (scale / 2 * percentage);
                    float enemyAvatarY = centerY - scale / 2;
                    g.drawImage(enemyAvatar, enemyAvatarX, enemyAvatarY, scale, scale);
                    layout.setText(font, enemyAvatarName);
                    g.drawText(enemyAvatarName, font, screenW - screenW / 4 - layout.width / 2, enemyAvatarY - layout.height, Color.WHITE, percentage);
                    layout.setText(font, p2Record);
                    g.drawText(p2Record, font, screenW - screenW / 4 - layout.width / 2, enemyAvatarY - layout.height * 2.5f, Color.WHITE, percentage);
                    //vs
                    float vsScale = (screenW / 3.2f);
                    g.drawHueShift(vsTexture, centerX - vsScale / 2, centerY - vsScale / 2, vsScale, vsScale, percentage * 4);
                } else {
                    //enemy
                    float enemyAvatarX = centerY - scale / 2;
                    float enemyAvatarY = scale / 3 * percentage;
                    g.drawImage(enemyAvatar, enemyAvatarX, enemyAvatarY, scale, scale);
                    //player
                    float playerAvatarX = centerY - scale / 2;
                    float playerAvatarY = screenW - scale - (percentage * scale / 3);
                    g.drawImage(playerAvatar, playerAvatarX, playerAvatarY, scale, scale);
                    //vs
                    float vsScale = (screenW / 3.2f);
                    g.drawHueShift(vsTexture, centerY - vsScale / 2, centerX - vsScale / 2, vsScale, vsScale, percentage * 4);
                    //names
                    layout.setText(font, enemyAvatarName);
                    g.drawText(enemyAvatarName, font, centerY - layout.width / 2, screenW - scale / 4, Color.WHITE, percentage);
                    layout.setText(font, playerAvatarName);
                    g.drawText(playerAvatarName, font, centerY - layout.width / 2, 0 + scale / 4, Color.WHITE, percentage);
                }
                //reset bitmapfont
                Controls.getBitmapFont("default");
            } else if (isIntro) {
                if (textureRegion != null) {
                    if (Forge.advStartup) {
                        g.drawGrayTransitionImage(Forge.getAssets().fallback_skins().get("title"), 0, 0, Forge.getScreenWidth(), Forge.getScreenHeight(), false, percentage);
                        g.setAlphaComposite(1 - percentage);
                        g.drawImage(textureRegion, 0, 0, Forge.getScreenWidth(), Forge.getScreenHeight());
                        g.setAlphaComposite(oldAlpha);
                    } else {
                        g.drawImage(Forge.isMobileAdventureMode ? FSkinTexture.ADV_BG_TEXTURE : FSkinTexture.BG_TEXTURE, 0, 0, Forge.getScreenWidth(), Forge.getScreenHeight());
                        g.setAlphaComposite(1 - percentage);
                        g.drawImage(textureRegion, 0, 0, Forge.getScreenWidth(), Forge.getScreenHeight());
                        g.setAlphaComposite(oldAlpha);
                    }
                }
            } else {
                if (textureRegion != null)
                    g.drawGrayTransitionImage(textureRegion, 0, 0, Forge.getScreenWidth(), Forge.getScreenHeight(), false, percentage);
            }
        }

        @Override
        protected boolean advance(float dt) {
            progress += dt;
            return progress < DURATION;
        }

        final boolean[] run = {false};//clears transition via runnable so this will reset anyway

        @Override
        protected void onEnd(boolean endingAll) {
            if (runnable != null) {
                if (isMatchTransition()) {
                    Timer.schedule(new Timer.Task() {
                        @Override
                        public void run() {
                            if (run[0])
                                return;
                            run[0] = true;
                            FThreads.invokeInEdtNowOrLater(runnable);
                        }
                    }, 2f);
                } else {
                    if (run[0])
                        return;
                    run[0] = true;
                    FThreads.invokeInEdtNowOrLater(runnable);
                }
            }
        }
    }

    @Override
    protected void drawBackground(Graphics g) {
        bgAnimation.start();
        bgAnimation.drawBackground(g);
    }
}
