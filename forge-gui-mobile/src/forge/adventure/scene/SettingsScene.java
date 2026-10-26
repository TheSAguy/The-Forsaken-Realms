package forge.adventure.scene;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.Graphics;
import forge.adventure.data.RewardData;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.assets.ImageCache;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.screens.match.CardFlightOverlay;
import forge.screens.match.views.VPlayerPanel;
import forge.sound.SoundSystem;
import forge.util.Localizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Scene to handle settings of the base forge and adventure mode
 */
public class SettingsScene extends UIScene {
    private final Table settingGroup;
    TextraButton backButton;
    TextraButton newPlane;
    ScrollPane scrollPane;

    SelectBox<String> selectSourcePlane;
    TextField newPlaneName;
    Dialog createNewPlane, copyPlane, errorDialog, restartDialog;

    private void copyNewPlane() {
        Localizer localizer = Forge.getLocalizer();
        String plane = selectSourcePlane.getSelected();
        Path source = Paths.get(Config.instance().getPlanePath(plane));
        Path destination = Paths.get(Config.instance().getPlanePath("<user>" + newPlaneName.getText()));
        AtomicBoolean somethingWentWrong = new AtomicBoolean(false);
        try (Stream<Path> stream = Files.walk(source)) {
            Files.createDirectories(destination);
            stream.forEach(s -> {
                try {
                    Files.copy(s, destination.resolve(source.relativize(s)), REPLACE_EXISTING);
                } catch (IOException e) {
                    somethingWentWrong.set(true);
                }
            });
        } catch (IOException e) {
            somethingWentWrong.set(true);
        }
        if (somethingWentWrong.get()) {
            if (errorDialog == null) {
                errorDialog = createGenericDialog("Something went wrong", "Copy was not successful check your access right\n and if the folder is in use",
                        localizer.getMessage("lblOK"), localizer.getMessage("lblAbort"), this::removeDialog, this::removeDialog);
            }
            showDialog(errorDialog);
        } else {
            if (copyPlane == null) {
                copyPlane = createGenericDialog("Copied plane", "New plane " + newPlaneName.getText() +
                                " was created\nYou can now start the editor to change the plane\n" +
                                "or edit it manually from the folder\n" + Config.instance().getPlanePath("<user>" + newPlaneName.getText()),
                        localizer.getMessage("lblOK"), localizer.getMessage("lblAbort"), this::removeDialog, this::removeDialog);
            }
            Config.instance().getSettingData().plane = "<user>" + newPlaneName.getText();
            Config.instance().saveSettings();
            Forge.getLocalizer().loadAdventureBundle(Config.instance().getPlanePath(Config.instance().getSettingData().plane) + "languages/");
            showDialog(copyPlane);
        }
    }

    private void createNewPlane() {
        if (createNewPlane == null) {
            createNewPlane = createGenericDialog("Create your own Plane", "Select a plane to copy",
                    Forge.getLocalizer().getMessage("lblOK"),
                    Forge.getLocalizer().getMessage("lblAbort"), () -> {
                        this.copyNewPlane();
                        removeDialog();
                    }, this::removeDialog);
            createNewPlane.getContentTable().row();
            createNewPlane.getContentTable().add(selectSourcePlane);
            createNewPlane.getContentTable().row();
            createNewPlane.text("Set new plane name");
            createNewPlane.getContentTable().row();
            createNewPlane.getContentTable().add(newPlaneName);
            newPlaneName.setText(selectSourcePlane.getSelected() + "_copy");
        }
        showDialog(createNewPlane);
    }

    private SettingsScene() {
        super(Forge.isLandscapeMode() ? "ui/settings.json" : "ui/settings_portrait.json");
        Localizer localizer = Forge.getLocalizer();

        settingGroup = new Table();
        selectSourcePlane = Controls.newComboBox();
        newPlaneName = Controls.newTextField("");
        selectSourcePlane.setItems(Config.instance().getAllAdventures());
        // 2026-08-29 fix (user report: a crash traced back to the wrong plane's assets loading
        // for a whole session): the 3-arg newComboBox persists+saves via its own internal
        // ChangeListener on every selection, UNCONDITIONALLY - firing BEFORE this widget's own
        // second listener even shows the "restart required" dialog below. Aborting that dialog
        // never undid the write, so settings.json could end up pointing at whatever plane was
        // briefly selected even if the player declined to restart (or force-closed instead) -
        // silently wrong on the NEXT real launch, well after the moment looked harmless. Fixed
        // by making the combobox's own callback a no-op and doing the actual persist ONLY
        // inside a dedicated confirm dialog's OK branch (not the shared restartForge()/
        // restartDialog, which is also used by the unrelated Android landscape-mode setting and
        // caches its OK callback across calls - reusing it here would risk that callback getting
        // wired to whichever setting happened to trigger it first).
        final String originalPlane = Config.instance().getSettingData().plane;
        SelectBox<String> plane = Controls.newComboBox(Config.instance().getAllAdventures(), originalPlane, o -> null);
        plane.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent changeEvent, Actor actor) {
                String selected = plane.getSelected();
                if (selected == null || selected.equals(Config.instance().getSettingData().plane))
                    return;
                Localizer localizer = Forge.getLocalizer();
                // Round 755: Restart / Cancel, not OK / Abort (the exit dialog's style, round 754).
                showDialog(createGenericDialog("",
                        localizer.getMessage("lblAreYouSureYouWishRestartForge"),
                        localizer.getMessage("lblRestart"),
                        localizer.getMessage("lblCancel"), () -> {
                            Config.instance().getSettingData().plane = selected;
                            Config.instance().saveSettings();
                            // Round 222 (upstream da6148f71eb, adventure localization): the plane's
                            // language bundle follows the plane. Upstream reloads it from the
                            // combobox callback; ours is a no-op there (2026-08-29 fix) and persists
                            // only here, so the reload lives here too - the restart reloads it anyway.
                            Forge.getLocalizer().loadAdventureBundle(Config.instance().getPlanePath(selected) + "languages/");
                            Forge.restart(true);
                            removeDialog();
                        }, () -> {
                            plane.setSelected(originalPlane);
                            removeDialog();
                        }));
            }
        });
        /*newPlane = Controls.newTextButton("Create own plane");
        newPlane.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                createNewPlane();
            }
        });*/
        addLabel(localizer.getMessage("lblWorld") + " (" + localizer.getMessage("lblRestartRequired") + ")");
        settingGroup.add(plane).align(Align.right).pad(2);
        //addLabel(localizer.getMessage("lblCreate") + localizer.getMessage("lblWorld"));
        settingGroup.add(newPlane).align(Align.right).pad(2);
        // Round 696 (the user: "Does Android have a log they can share or is that PC only?", then "Let's implement this"):
        // the engine's Export Logs (classic Forge's Settings > Files) one row from the top here, so a player can send a
        // bug report without finding forge.log - on Android 13+ a file manager cannot even open Android/obb.
        addLabel(localizer.getMessage("lblLogsForBugReports"));
        settingGroup.add(Controls.newTextButton(localizer.getMessage("lblExportLogs"), this::exportLogs)).align(Align.right).pad(2);
        // Round 698: the log-sharing switch beside it (only in a build that ships a webhook - see util/LogShare).
        if (forge.adventure.util.LogShare.available()) {
            addSettingField(localizer.getMessage("lblShareLogs"), forge.adventure.util.LogShare.isEnabled(), new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    forge.adventure.util.LogShare.setEnabled(((CheckBox) actor).isChecked());
                }
            });
        }

        if (!GuiBase.isAndroid()) {
            SelectBox<String> videomode = Controls.newComboBox(ForgeConstants.VIDEO_MODES, Config.instance().getSettingData().videomode, o -> {
                String mode = (String) o;
                if (mode == null)
                    mode = "720p";
                Graphics.setVideoMode(mode);

                //update
                if (!FModel.getPreferences().getPref(ForgePreferences.FPref.UI_VIDEO_MODE).equalsIgnoreCase(mode)) {
                    FModel.getPreferences().setPref(ForgePreferences.FPref.UI_VIDEO_MODE, mode);
                    FModel.getPreferences().save();
                }
                return null;
            });
            addLabel(localizer.getMessage("lblVideoMode"));
            settingGroup.add(videomode).align(Align.right).pad(2);
        }
        if (Forge.isLandscapeMode()) {
            //different adjustment to landscape
            SelectBox<Float> rewardCardAdjLandscape = Controls.newComboBox(new Float[]{0.6f, 0.65f, 0.7f, 0.75f, 0.8f, 0.85f, 0.9f, 0.95f, 1f, 1.05f, 1.1f, 1.15f, 1.2f, 1.25f, 1.3f, 1.35f, 1.4f, 1.45f, 1.5f, 1.55f, 1.6f}, Config.instance().getSettingData().rewardCardAdjLandscape, o -> {
                Float val = (Float) o;
                if (val == null || val == 0f)
                    val = 1f;
                Config.instance().getSettingData().rewardCardAdjLandscape = val;
                Config.instance().saveSettings();
                return null;
            });
            addLabel(localizer.getMessage("advRewardShopCardDisplayRatio"));
            settingGroup.add(rewardCardAdjLandscape).align(Align.right).pad(2);
            SelectBox<Float> tooltipAdjLandscape = Controls.newComboBox(new Float[]{0.6f, 0.65f, 0.7f, 0.75f, 0.8f, 0.85f, 0.9f, 0.95f, 1f, 1.05f, 1.1f, 1.15f, 1.2f, 1.25f, 1.3f, 1.35f, 1.4f, 1.45f, 1.5f, 1.55f, 1.6f}, Config.instance().getSettingData().cardTooltipAdjLandscape, o -> {
                Float val = (Float) o;
                if (val == null || val == 0f)
                    val = 1f;
                Config.instance().getSettingData().cardTooltipAdjLandscape = val;
                Config.instance().saveSettings();
                return null;
            });
            addLabel(localizer.getMessage("advRewardShopCardTooltipRatio"));
            settingGroup.add(tooltipAdjLandscape).align(Align.right).pad(2);
        } else {
            //portrait adjustment
            SelectBox<Float> rewardCardAdj = Controls.newComboBox(new Float[]{0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.8f, 1.9f, 2f}, Config.instance().getSettingData().rewardCardAdj, o -> {
                Float val = (Float) o;
                if (val == null || val == 0f)
                    val = 1f;
                Config.instance().getSettingData().rewardCardAdj = val;
                Config.instance().saveSettings();
                return null;
            });
            addLabel(localizer.getMessage("advRewardShopCardDisplayRatio"));
            settingGroup.add(rewardCardAdj).align(Align.right).pad(2);
            SelectBox<Float> tooltipAdj = Controls.newComboBox(new Float[]{0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.8f, 1.9f, 2f}, Config.instance().getSettingData().cardTooltipAdj, o -> {
                Float val = (Float) o;
                if (val == null || val == 0f)
                    val = 1f;
                Config.instance().getSettingData().cardTooltipAdj = val;
                Config.instance().saveSettings();
                return null;
            });
            addLabel(localizer.getMessage("advRewardShopCardTooltipRatio"));
            settingGroup.add(tooltipAdj).align(Align.right).pad(2);
        }
        if (!GuiBase.isAndroid()) {
            addSettingField(localizer.getMessage("lblFullScreen"), Config.instance().getSettingData().fullScreen, new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    boolean value = ((CheckBox) actor).isChecked();
                    Config.instance().getSettingData().fullScreen = value;
                    Config.instance().saveSettings();
                    //update
                    FModel.getPreferences().setPref(ForgePreferences.FPref.UI_FULLSCREEN_MODE, Config.instance().getSettingData().fullScreen);
                    FModel.getPreferences().save();
                }
            });
        }
        addSettingField(localizer.getMessage("lblDay") + " | " + localizer.getMessage("lblNight") + " " + localizer.getMessage("lblBackgroundImage"), Config.instance().getSettingData().dayNightBG, new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                boolean value = ((CheckBox) actor).isChecked();
                Config.instance().getSettingData().dayNightBG = value;
                Config.instance().saveSettings();
                if (value) {
                    updateBG(true);
                }
            }
        });
        // Round 493: Ascendance (the player's level) on or off - see util/Ascendance.switchedOffInSettings().
        addSettingField(localizer.getMessage("lblAscendanceLeveling"), !Config.instance().getSettingData().ascendanceDisabled, new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Config.instance().getSettingData().ascendanceDisabled = !((CheckBox) actor).isChecked();
                Config.instance().saveSettings();
            }
        });
        addSettingField(localizer.getMessage("lblFogOfWar"), Config.instance().getSettingData().fogOfWarEnabled, new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Config.instance().getSettingData().fogOfWarEnabled = ((CheckBox) actor).isChecked();
                Config.instance().saveSettings();
            }
        });
        addSettingField(localizer.getMessage("lblDisableWinLose"), Config.instance().getSettingData().disableWinLose, new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Config.instance().getSettingData().disableWinLose = ((CheckBox) actor).isChecked();
                Config.instance().saveSettings();
            }
        });
        // Round 703: "Disable Not For Sale Overlay" left off the page - the overlay never draws in Adventure mode whatever it
        // says (ImageView: !(Forge.isMobileAdventureMode || disableNotForSale), upstream de89d557dd4), and this page only
        // exists there. The saved value stays untouched.
        addSettingField(localizer.getMessage("lblShowShopOverlay"), Config.instance().getSettingData().showShopOverlay, new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Config.instance().getSettingData().showShopOverlay = ((CheckBox) actor).isChecked();
                Config.instance().saveSettings();
            }
        });
        addSettingField(localizer.getMessage("lblUseAllCardVariants"), Config.instance().getSettingData().useAllCardVariants, new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Config.instance().getSettingData().useAllCardVariants = ((CheckBox) actor).isChecked();
                Config.instance().saveSettings();
                RewardData.invalidateCardPool();
            }
        });
        addSettingField(localizer.getMessage("lblPreferEraMatchedTokenArt"), Config.instance().getSettingData().preferEraMatchedTokenArt, new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                boolean enabled = ((CheckBox) actor).isChecked();
                Config.instance().getSettingData().preferEraMatchedTokenArt = enabled;
                forge.model.FModel.getMagicDb().getAllTokens().setPreferEraMatchedArt(enabled);
                Config.instance().saveSettings();
            }
        });
        addSettingField(localizer.getMessage("lblExcludeAlchemyVariants"), Config.instance().getSettingData().excludeAlchemyVariants, new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Config.instance().getSettingData().excludeAlchemyVariants = ((CheckBox) actor).isChecked();
                Config.instance().saveSettings();
                RewardData.invalidateCardPool();
            }
        });
        // Round 410: MTG Arena's digital-only sets (config.json digitalOnlyEditions) - the card pools read them once at
        // startup, so this offers the restart, like the unknown/non-legal card options below.
        addSettingField(localizer.getMessage("lblAllowDigitalOnlyCards") + " (" + localizer.getMessage("lblRestartRequired") + ")",
                Config.instance().getSettingData().allowDigitalOnlyCards, new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Config.instance().getSettingData().allowDigitalOnlyCards = ((CheckBox) actor).isChecked();
                Config.instance().saveSettings();
                restartForge();
            }
        });
        // Round 723: the Font (TfrFonts), Clean or Pixel; the labels hold their font, so it takes a restart
        if (forge.adventure.util.TfrFonts.offered()) {
            String font = forge.adventure.util.TfrFonts.PIXEL.equalsIgnoreCase(Config.instance().getSettingData().tfrFont)
                    ? forge.adventure.util.TfrFonts.PIXEL : forge.adventure.util.TfrFonts.CLEAN;
            SelectBox<String> fontBox = Controls.newComboBox(new String[]{forge.adventure.util.TfrFonts.CLEAN,
                    forge.adventure.util.TfrFonts.PIXEL}, font, o -> {
                String picked = o == null ? forge.adventure.util.TfrFonts.CLEAN : o.toString();
                if (picked.equals(Config.instance().getSettingData().tfrFont))
                    return null;
                Config.instance().getSettingData().tfrFont = picked;
                Config.instance().saveSettings();
                System.out.println("[TFR-Font] Settings: Font " + picked + " (after a restart)");
                restartForge();
                return null;
            });
            addLabel("Font (" + localizer.getMessage("lblRestartRequired") + ")");
            settingGroup.add(fontBox).align(Align.right).pad(2);
        }
        // Restricted-edition art (2026-08-30 user request) - purely cosmetic, so it gets a plain
        // toggle rather than a restart prompt. No invalidateCardPool() needed: this changes which
        // PRINTING a reward resolves to at generation time, not which cards are in the pool.
        addSettingField(localizer.getMessage("lblAvoidRestrictedEditionArt"),
                Config.instance().getSettingData().avoidRestrictedEditionArt, new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Config.instance().getSettingData().avoidRestrictedEditionArt = ((CheckBox) actor).isChecked();
                Config.instance().saveSettings();
            }
        });
        addCheckBox(localizer.getMessage("lblEnableUnknownCards") + " (" +
            localizer.getMessage("lblRestartRequired") + ")", ForgePreferences.FPref.UI_LOAD_UNKNOWN_CARDS, this::restartForge);
        addCheckBox(localizer.getMessage("lblEnableNonLegalCards") + " (" +
            localizer.getMessage("lblRestartRequired") + ")", ForgePreferences.FPref.UI_LOAD_NONLEGAL_CARDS, this::restartForge);
        // Round 703 (the user: "if this has no effect, let's remove it from the settings page"): a plane with
        // disableGeneticDeckOverrides never swaps an enemy's deck (EnemyData.generateDeck), so this choice does nothing there.
        if (!Config.instance().getConfigData().disableGeneticDeckOverrides)
            addSettingField(localizer.getMessage("lblGenerateLDADecks"), Config.instance().getSettingData().generateLDADecks, new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    Config.instance().getSettingData().generateLDADecks = ((CheckBox) actor).isChecked();
                    Config.instance().saveSettings();
                }
            });
        addSettingField(localizer.getMessage("lbldisableCrackedItems"), Config.instance().getSettingData().disableCrackedItems, new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Config.instance().getSettingData().disableCrackedItems = ((CheckBox) actor).isChecked();
                Config.instance().saveSettings();
            }
        });
        addSettingField(localizer.getMessage("lblBindEquipmentLoadoutsToDecks"), Config.instance().getSettingData().bindEquipmentLoadoutsToDecks, new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Config.instance().getSettingData().bindEquipmentLoadoutsToDecks = ((CheckBox) actor).isChecked();
                Config.instance().saveSettings();
            }
        });
        addSettingField(localizer.getMessage("lblDrawChevronsToHiddenEnemiesInClearQuest"), Config.instance().getSettingData().drawChevronsToHiddenEnemiesInClearQuest, new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Config.instance().getSettingData().drawChevronsToHiddenEnemiesInClearQuest = ((CheckBox) actor).isChecked();
                Config.instance().saveSettings();
            }
        });
        // Inn tournament AI-vs-AI simulation (2026-08-17 user spec, default off - see
        // SettingData's own field comment for why).
        addSettingField(localizer.getMessage("lblSimulateInnTournamentAIMatches"), Config.instance().getSettingData().simulateInnTournamentAIMatches, new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Config.instance().getSettingData().simulateInnTournamentAIMatches = ((CheckBox) actor).isChecked();
                Config.instance().saveSettings();
            }
        });
        CheckBox cbAnte = addCheckBox(localizer.getMessage("cbAnte"), ForgePreferences.FPref.UI_ANTE);
        CheckBox cbAnteMatchRarity = addCheckBox(localizer.getMessage("cbAnteMatchRarity"), ForgePreferences.FPref.UI_ANTE_MATCH_RARITY);
        CheckBox cbAnteIncludeBasicLands = addCheckBox(localizer.getMessage("cbAnteIncludeBasicLands"), ForgePreferences.FPref.UI_ANTE_INCLUDE_BASIC_LANDS);
        boolean anteEnabled = FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ANTE);
        cbAnteMatchRarity.setDisabled(!anteEnabled);
        cbAnteIncludeBasicLands.setDisabled(!anteEnabled);
        cbAnte.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                boolean enabled = ((CheckBox) actor).isChecked();
                cbAnteMatchRarity.setDisabled(!enabled);
                cbAnteIncludeBasicLands.setDisabled(!enabled);
                RewardData.invalidateCardPool();
            }
        });
        addCheckBox(localizer.getMessage("lblPromptAutoSell"), ForgePreferences.FPref.PROMPT_FOR_AUTOSELL);
        if (planeOffersCommander()) // round 703: read only in a Commander run (RewardActor), which TFR never offers
            addCheckBox(localizer.getMessage("lblAutoSellVariantsCommander"), ForgePreferences.FPref.ADV_COMMANDER_AUTOSELL_VARIANT);
        addCheckBox(localizer.getMessage("lblShowCardPriceRewardScreen"), ForgePreferences.FPref.ADV_DISPLAY_PRICE_IN_REWARD_SCREEN);
        addCheckBox(localizer.getMessage("lblCardName"), ForgePreferences.FPref.UI_OVERLAY_CARD_NAME);
        addSettingSlider(localizer.getMessage("cbAdjustMusicVolume"), ForgePreferences.FPref.UI_VOL_MUSIC, 0, 100);
        addSettingSlider(localizer.getMessage("cbAdjustSoundsVolume"), ForgePreferences.FPref.UI_VOL_SOUNDS, 0, 100);
        addCheckBox(localizer.getMessage("cbShowAutoTapPreview"), ForgePreferences.FPref.UI_SHOW_AUTOTAP_PREVIEW);
        addCheckBox(localizer.getMessage("lblManaCost"), ForgePreferences.FPref.UI_OVERLAY_CARD_MANA_COST);
        addCheckBox(localizer.getMessage("lblPerpetualManaCost"), ForgePreferences.FPref.UI_OVERLAY_CARD_PERPETUAL_MANA_COST);
        addCheckBox(localizer.getMessage("lblPowerOrToughness"), ForgePreferences.FPref.UI_OVERLAY_CARD_POWER);
        addCheckBox(localizer.getMessage("lblCardID"), ForgePreferences.FPref.UI_OVERLAY_CARD_ID);
        addCheckBox(localizer.getMessage("lblAbilityIcon"), ForgePreferences.FPref.UI_OVERLAY_ABILITY_ICONS);
        addCheckBox(localizer.getMessage("cbImageFetcher"), ForgePreferences.FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER);

        if (!GuiBase.isAndroid()) {
            addCheckBox(localizer.getMessage("lblBattlefieldTextureFiltering"), ForgePreferences.FPref.UI_LIBGDX_TEXTURE_FILTERING);
            //addCheckBox(localizer.getMessage("lblAltZoneTabs"), ForgePreferences.FPref.UI_ALT_PLAYERZONETABS);
        } else {
            addCheckBox(localizer.getMessage("lblLandscapeMode") + " (" +
                localizer.getMessage("lblRestartRequired") + ")",
                    ForgePreferences.FPref.UI_LANDSCAPE_MODE, () -> {
                        boolean landscapeMode = FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_LANDSCAPE_MODE);
                        //ensure device able to save off ini file so landscape change takes effect
                        Forge.getDeviceAdapter().setLandscapeMode(landscapeMode);
                        if (Forge.isLandscapeMode() != landscapeMode) {
                            restartForge();
                        }
                    });
        }

        VPlayerPanel.reloadCompactLayout(); //also converts the old on/off value of this setting
        SelectBox<String> compactLayout = Controls.newComboBox(new String[]{"Off", "Left", "Right", "Alternate"},
                FModel.getPreferences().getPref(ForgePreferences.FPref.UI_COMPACT_LANDSCAPE_LAYOUT), o -> {
            String mode = (String) o;
            if (mode != null && !mode.equals(FModel.getPreferences().getPref(ForgePreferences.FPref.UI_COMPACT_LANDSCAPE_LAYOUT))) {
                FModel.getPreferences().setPref(ForgePreferences.FPref.UI_COMPACT_LANDSCAPE_LAYOUT, mode);
                FModel.getPreferences().save();
                VPlayerPanel.reloadCompactLayout();
            }
            return null;
        });
        addLabel(localizer.getMessage("lblCompactLayout"));
        settingGroup.add(compactLayout).align(Align.right).pad(2);
        addCheckBox(localizer.getMessageorUseDefault("lblCompactHideMenu", "Hide Top Menu (Compact Layout)"), ForgePreferences.FPref.UI_COMPACT_HIDE_MENU, VPlayerPanel::reloadMenuHidden);
        addCheckBox(localizer.getMessageorUseDefault("lblCompactZoneTabs", "Compact Zone Tabs (Landscape Mode)"), ForgePreferences.FPref.UI_COMPACT_ZONE_TABS, VPlayerPanel::reloadZoneOverlay);
        addCheckBox(localizer.getMessageorUseDefault("lblFrostedStyle", "Frosted Style (Match Screen)"), ForgePreferences.FPref.UI_FROSTED_STYLE, VPlayerPanel::reloadFrosted);
        addCheckBox(localizer.getMessage("lblAnimatedCardTapUntap"), ForgePreferences.FPref.UI_ANIMATED_CARD_TAPUNTAP);
        String currentAnim;
        switch (CardFlightOverlay.style()) { // maps legacy "true"/"false" values too
            case OFF: currentAnim = "Off"; break;
            case SLIDE: currentAnim = "Slide"; break;
            case POPUP: currentAnim = "Popup"; break;
            default: currentAnim = "Rotate"; break;
        }
        SelectBox<String> cardPlayAnim = Controls.newComboBox(new String[]{"Rotate", "Slide", "Popup", "Off"}, currentAnim, o -> {
            String mode = (String) o;
            if (mode == null)
                mode = "Rotate";
            if (!mode.equalsIgnoreCase(FModel.getPreferences().getPref(ForgePreferences.FPref.UI_CARD_PLAY_ANIMATION_STYLE))) {
                FModel.getPreferences().setPref(ForgePreferences.FPref.UI_CARD_PLAY_ANIMATION_STYLE, mode);
                FModel.getPreferences().save();
            }
            return null;
        });
        addLabel(localizer.getMessageorUseDefault("lblCardPlayOption", "Card Play Animation Style"));
        settingGroup.add(cardPlayAnim).align(Align.right).pad(2);
        // Round 615 (the user: "Default Undo to Single step in TFR ... keep the Settings choice"): upstream's duel Undo
        // (the 10.14 merge) has its setting only in Classic's settings - here too. Single step is TFR's default.
        String[] undoModes = {ForgeConstants.UNDO_MODE_OFF, ForgeConstants.UNDO_MODE_SINGLE, ForgeConstants.UNDO_MODE_FREE};
        String currentUndo = FModel.getPreferences().getPref(ForgePreferences.FPref.UI_UNDO_MODE);
        SelectBox<String> undoMode = Controls.newComboBox(undoModes,
                java.util.Arrays.asList(undoModes).contains(currentUndo) ? currentUndo : ForgeConstants.UNDO_MODE_SINGLE, o -> {
            String mode = o == null ? ForgeConstants.UNDO_MODE_SINGLE : (String) o;
            if (!mode.equals(FModel.getPreferences().getPref(ForgePreferences.FPref.UI_UNDO_MODE))) {
                FModel.getPreferences().setPref(ForgePreferences.FPref.UI_UNDO_MODE, mode);
                FModel.getPreferences().save();
                System.out.println("[TFR-Undo] Settings: duel Undo " + mode);
            }
            return null;
        });
        addLabel(localizer.getMessageorUseDefault("lblUndoMode", "Undo in Duels"));
        settingGroup.add(undoMode).align(Align.right).pad(2);
        // Round 464 (the user: "Add both an on/off setting and have it start without a tap"): Forge's own Coin Flip
        // Animation switch - the opening toss and every card's coin flip - here too, not only in Classic's settings.
        // Round 472 (the 10.07 merge): upstream renamed it UI_COIN_DICE_ANIMATION and it now also covers the new dice
        // rolls (#12167) - same switch, Forge's new name and label.
        addCheckBox(localizer.getMessageorUseDefault("lblCoinDiceAnimation", "Coin/Dice Animation"),
                ForgePreferences.FPref.UI_COIN_DICE_ANIMATION);
        if (!GuiBase.isAndroid()) {
            final String[] item = {FModel.getPreferences().getPref(ForgePreferences.FPref.UI_ENABLE_BORDER_MASKING)};
            SelectBox<String> borderMask = Controls.newComboBox(new String[]{"Off", "Crop", "Full", "Art"}, item[0], o -> {
                String mode = (String) o;
                if (mode == null)
                    mode = "Crop";
                item[0] = mode;
                //update
                if (!FModel.getPreferences().getPref(ForgePreferences.FPref.UI_ENABLE_BORDER_MASKING).equalsIgnoreCase(mode)) {
                    FModel.getPreferences().setPref(ForgePreferences.FPref.UI_ENABLE_BORDER_MASKING, mode);
                    FModel.getPreferences().save();
                    Forge.enableUIMask = FModel.getPreferences().getPref(ForgePreferences.FPref.UI_ENABLE_BORDER_MASKING);
                }
                ImageCache.getInstance().disposeTextures();
                return null;
            });
            addLabel(localizer.getMessage("lblBorderMaskOption"));
            settingGroup.add(borderMask).align(Align.right).pad(2);

            addCheckBox(localizer.getMessage("lblAutoCacheSize"), ForgePreferences.FPref.UI_AUTO_CACHE_SIZE);
            addCheckBox(localizer.getMessage("lblDisposeTextures"), ForgePreferences.FPref.UI_ENABLE_DISPOSE_TEXTURES);
        }


        addSettingSlider(localizer.getMessage("lblVibrationIntensity"), ForgePreferences.FPref.UI_VIBRATE_INTENSITY, 0, 100);
        addCheckBox(localizer.getMessage("lblVibrateAfterLongPress"), ForgePreferences.FPref.UI_VIBRATE_ON_LONG_PRESS);
        addCheckBox(localizer.getMessage("lblVibrateWhenLosingLife"), ForgePreferences.FPref.UI_VIBRATE_ON_LIFE_LOSS);
        addCheckBox(localizer.getMessage("lblVibrateOnEnemyEncounter"), ForgePreferences.FPref.UI_VIBRATE_ON_ENEMY_ENCOUNTER);
        addCheckBox(localizer.getMessage("lblVibrateOnAdventureReward"), ForgePreferences.FPref.UI_VIBRATE_ON_ADVENTURE_REWARD);
        addCheckBox(localizer.getMessage("lblVibrateOnShopAction"), ForgePreferences.FPref.UI_VIBRATE_ON_SHOP_ACTION);

        settingGroup.row();
        backButton = ui.findActor("return");
        ui.onButtonPress("return", SettingsScene.this::back);

        scrollPane = ui.findActor("settings");
        scrollPane.setActor(settingGroup);
        addToSelectable(settingGroup);
    }


    public boolean back() {
        Forge.switchToLast();
        return true;
    }

    /** Round 703: can a run on this plane be a Commander run (AdventureModes.isCommanderLike) - a difficulty with
     *  commanderDecks, Commander precon decks, or Chaos with chaosDeckFormat "Commander" (as NewGameScene offers them)? */
    private static boolean planeOffersCommander() {
        forge.adventure.data.ConfigData config = Config.instance().getConfigData();
        if ("Commander".equalsIgnoreCase(config.chaosDeckFormat) || Config.instance().hasCommanderPreconDecks())
            return true;
        if (config.difficulties != null)
            for (forge.adventure.data.DifficultyData difficulty : config.difficulties)
                if (difficulty.commanderDecks != null)
                    return true;
        return false;
    }

    private void addInputField(String name, ForgePreferences.FPref pref) {
        TextField box = Controls.newTextField("");
        box.setText(FModel.getPreferences().getPref(pref));
        box.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                FModel.getPreferences().setPref(pref, ((TextField) actor).getText());
                FModel.getPreferences().save();
            }
        });

        addLabel(name);
        settingGroup.add(box).align(Align.right);
    }

    private CheckBox addCheckBox(String name, ForgePreferences.FPref pref) {
        return addCheckBox(name, pref, null);
    }

    private CheckBox addCheckBox(String name, ForgePreferences.FPref pref, Runnable runnable) {
        CheckBox box = Controls.newCheckBox("");
        box.setChecked(FModel.getPreferences().getPrefBoolean(pref));
        box.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                FModel.getPreferences().setPref(pref, ((CheckBox) actor).isChecked());
                FModel.getPreferences().save();
                if (runnable != null)
                    runnable.run();
            }
        });

        addLabel(name);
        settingGroup.add(box).align(Align.right);
        return box;
    }

    private void addSettingSlider(String name, ForgePreferences.FPref pref, int min, int max) {
        Slider slide = Controls.newSlider(min, max, 1, false);
        slide.setValue(FModel.getPreferences().getPrefInt(pref));
        slide.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                FModel.getPreferences().setPref(pref, String.valueOf((int) ((Slider) actor).getValue()));
                FModel.getPreferences().save();
                if (ForgePreferences.FPref.UI_VOL_MUSIC.equals(pref))
                    SoundSystem.instance.refreshVolume();
            }
        });
        addLabel(name);
        settingGroup.add(slide).align(Align.right);
    }

    private void addSettingField(String name, boolean value, ChangeListener change) {
        CheckBox box = Controls.newCheckBox("");
        box.setChecked(value);
        box.addListener(change);
        addLabel(name);
        settingGroup.add(box).align(Align.right);
    }

    private void addSettingField(String name, int value, ChangeListener change) {
        TextField text = Controls.newTextField(String.valueOf(value));
        text.setTextFieldFilter((textField, c) -> Character.isDigit(c));
        text.addListener(change);
        addLabel(name);
        settingGroup.add(text).align(Align.right);
    }

    /** Round 696: every forge*.log (the running one through its own channel) as one zip in Downloads, named for the game;
     *  where it went, or why not, in a dialog. Android first asks for file access, as classic Forge's Files page does. */
    private void exportLogs() {
        Localizer localizer = Forge.getLocalizer();
        if (Forge.getDeviceAdapter().needFileAccess()) {
            Forge.getDeviceAdapter().requestFileAcces();
            return;
        }
        String message;
        try {
            java.io.File downloads = new com.badlogic.gdx.files.FileHandle(Forge.getDeviceAdapter().getDownloadsDir()).file();
            downloads.mkdirs();
            java.io.File zip = forge.util.LogExporter.exportLogs(downloads);
            if (zip == null) {
                message = localizer.getMessage("lblNoLogFilesFound");
            } else {
                java.io.File named = new java.io.File(downloads, zip.getName().replace("forge-logs-", "forsaken-realms-logs-"));
                if (zip.renameTo(named))
                    zip = named;
                message = localizer.getMessage("lblExportLogsDone", zip.getAbsolutePath());
                System.out.println("[TFR-Logs] exported to " + zip.getAbsolutePath() + " (" + zip.length() / 1024 + " KB)");
                showInFolder(zip);
            }
        } catch (IOException e) {
            message = localizer.getMessage("lblError") + ": " + e;
            System.err.println("[TFR-Logs] export failed: " + e);
        }
        showDialog(createGenericDialog(localizer.getMessage("lblExportLogs"), message, localizer.getMessage("lblOK"), null,
                this::removeDialog, null, false, "", true));
    }

    /** Round 699 (the user: "can we open the Downloads folder also, so it's easy for them to grab"): on a PC the folder
     *  opens with the zip selected (Windows Explorer's /select; elsewhere the folder itself), off the render thread.
     *  Android has no folder view to open from here - the dialog's path stands. */
    private static void showInFolder(java.io.File zip) {
        if (GuiBase.isAndroid())
            return;
        new Thread(() -> {
            try {
                if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows"))
                    new ProcessBuilder("explorer.exe", "/select," + zip.getAbsolutePath()).start();
                else
                    java.awt.Desktop.getDesktop().open(zip.getParentFile());
                System.out.println("[TFR-Logs] opened " + zip.getParent());
            } catch (Exception e) {
                System.out.println("[TFR-Logs] could not open " + zip.getParent() + " (" + e + ")");
            }
        }, "TFR-ShowLogs").start();
    }

    void addLabel(String name) {
        TextraLabel label = Controls.newTextraLabel(name);
        label.setWrap(true);
        settingGroup.row().space(5);
        int w = Forge.isLandscapeMode() ? 160 : 80;
        settingGroup.add(label).align(Align.left).pad(2, 2, 2, 5).width(w).expand();
    }

    private void restartForge() {
        Localizer localizer = Forge.getLocalizer();
        if (restartDialog == null) {
            restartDialog = createGenericDialog("",
                    localizer.getMessage("lblAreYouSureYouWishRestartForge"),
                    localizer.getMessage("lblRestart"), // round 755: Restart / Cancel
                    localizer.getMessage("lblCancel"), () -> {
                        Forge.restart(true);
                        removeDialog();
                    }, this::removeDialog);
        }
        showDialog(restartDialog);
    }

    private static SettingsScene object;

    public static SettingsScene instance() {
        if (object == null)
            object = new SettingsScene();
        return object;
    }


    @Override
    public void dispose() {
        if (stage != null)
            stage.dispose();
    }

}
