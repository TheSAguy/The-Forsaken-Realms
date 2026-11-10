package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Adventure;
import forge.Forge;
import forge.adventure.data.AdventureQuestData;
import forge.adventure.data.AdventureQuestStage;
import forge.adventure.character.EnemySprite;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.AdventureQuestController;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.util.QuestExpiry;
import forge.adventure.util.RaidMiniMap;
import forge.adventure.util.TownPillage;

public class QuestLogScene extends UIScene {
    private Table scrollContainer, detailScrollContainer;
    Window scrollWindow;
    ScrollPane scroller,detailScroller;
    Table root, detailRoot;
    TextraButton trackButton, backToListButton, abandonQuestButton;
    Scene lastGameScene;

    private QuestLogScene() {
        super(Forge.isLandscapeMode() ? "ui/quests.json" : "ui/quests_portrait.json");

        scrollWindow = ui.findActor("scrollWindow");
        root = ui.findActor("questList");
        detailRoot = ui.findActor("questDetails");
        abandonQuestButton = Controls.newTextButton("Abandon Quest");
        trackButton = Controls.newTextButton("Track Quest");
        backToListButton = Controls.newTextButton("Quest List");
        ui.onButtonPress("return", QuestLogScene.this::back);
        ui.onButtonPress("status", QuestLogScene.this::status);
        forge.adventure.util.TfrScreenTips.questButtons(this, abandonQuestButton, trackButton); // TFR round 1000: hover tips
        backToListButton.addListener(new ClickListener() {
            public void clicked(InputEvent event, float x, float y) {
                buildList();
            }
        });

        // TODO - refactor below, replace buttons in landscape
        scrollContainer = new Table(Controls.getSkin());
        scrollContainer.row();

        detailScrollContainer = new Table(Controls.getSkin());
        detailScrollContainer.row();

        detailScroller = new ScrollPane(detailScrollContainer);
        detailScroller.setScrollingDisabled(true,false);
        if (Forge.isLandscapeMode()) {
            detailRoot.add(abandonQuestButton).fillX().top().padTop(5f);
            detailRoot.add(trackButton).fillX().top().padTop(5f);
            detailRoot.add(backToListButton).fillX().top().padTop(5f);
        } else {
            detailRoot.add(abandonQuestButton).fillX().top().padTop(5f).colspan(3);
            detailRoot.row();
            detailRoot.add(trackButton).fillX().top().padTop(5f).colspan(3);
            detailRoot.row();
            detailRoot.add(backToListButton).fillX().top().padTop(5f).colspan(3);
        }

        detailRoot.row();
        detailRoot.add(detailScroller).colspan(3).expandX().fillX().expandY();
        detailRoot.row();
        scrollWindow.setTouchable(Touchable.disabled);
        detailRoot.setVisible(false);
        scroller = new ScrollPane(scrollContainer);
        root.add(scroller).colspan(3);
        root.align(Align.right);
        root.row();
        Label column0Label = new Label(Forge.getLocalizer().getMessage("lblQuestName"), Controls.getSkin());
        column0Label.setColor(Color.BLACK);
        root.add(column0Label).align(Align.bottomLeft);
        root.row();
        ScrollPane scroller = new ScrollPane(scrollContainer);
        root.add(scroller).colspan(3).fill().expand();
    }

    private static QuestLogScene object;

    public static QuestLogScene instance(Scene lastGameScene) {
        //if (object == null)
            object = new QuestLogScene();
        if (lastGameScene != null)
            object.lastGameScene=lastGameScene;
        return object;
    }

    @Override
    public void dispose() { }

    @Override
    public void enter() {
        super.enter();
        buildList();
    }

    public void buildList(){
        backToList();

        scrollContainer.clear();

        for (AdventureQuestData quest : Current.player().getQuests()) {
            String headerCode = quest.isTracked ? "{GRADIENT=CYAN;BLUE;1;1}•{ENDGRADIENT}[BLACK]" : "[BLACK]";
            // Side-quest timer countdown (QuestExpiry, opt-in) - empty suffix when no timer applies.
            TypingLabel nameLabel = Controls.newTypingLabel(headerCode + quest.getName() + QuestExpiry.questLogSuffix(quest));
            nameLabel.skipToTheEnd();
            nameLabel.setWrap(true);
            forge.adventure.util.TfrScreenTips.questName(this, nameLabel, quest); // TFR round 1000: its time limit
            scrollContainer.add(nameLabel).align(Align.left).expandX();
            Button details = Controls.newTextButton(Forge.getLocalizer().getMessage("lblDetails"));
            details.addListener(new ClickListener() {
                public void clicked(InputEvent event, float x, float y){
                    loadDetailsPane(quest);
                }
            });
            scrollContainer.add(details).align(Align.center).padRight(10);
            scrollContainer.row().padTop(5);
            addToSelectable(details);
        }
        // Round 350 (the user: "give legends a day-based lifetime"): every legend roaming right now, one row each - the
        // way to it from here and the days it has left. Derived from WorldStage's live list each time the log is built,
        // never a quest of its own: nothing to save, nothing to clean up when it is beaten or moves on. The map's gold
        // dots mark the same legends.
        for (EnemySprite legend : WorldStage.getInstance().getLegendSightings()) {
            int daysLeft = WorldStage.legendDaysLeft(legend);
            String when = daysLeft < 0 ? "" : daysLeft == 0 ? " [%75](moving on)"
                    : " [%75](" + daysLeft + " day" + (daysLeft == 1 ? "" : "s") + " left)";
            TypingLabel legendLabel = Controls.newTypingLabel("[BLACK]Legend: " + legend.getData().getTieredDisplayName()
                    + " - " + WorldStage.getInstance().directionFromPlayer(legend) + when);
            legendLabel.skipToTheEnd();
            legendLabel.setWrap(true);
            forge.adventure.util.TfrScreenTips.legend(this, legendLabel); // TFR round 1000
            scrollContainer.add(legendLabel).align(Align.left).expandX();
            scrollContainer.add(); // no Details - a sighting has no quest behind it
            scrollContainer.row().padTop(5);
        }
        // Round 484: every town being pillaged (TownPillage), a row like the legends' - no quest behind it either.
        // Round 865: with a Details button - the raid's own page (loadRaidDetails).
        for (String[] row : TownPillage.questLogRows()) {
            TypingLabel pillageLabel = Controls.newTypingLabel("[BLACK]" + row[1]);
            pillageLabel.skipToTheEnd();
            pillageLabel.setWrap(true);
            scrollContainer.add(pillageLabel).align(Align.left).expandX();
            Button details = Controls.newTextButton(Forge.getLocalizer().getMessage("lblDetails"));
            String townId = row[0];
            details.addListener(new ClickListener() {
                public void clicked(InputEvent event, float x, float y) {
                    loadRaidDetails(townId);
                }
            });
            scrollContainer.add(details).align(Align.center).padRight(10);
            scrollContainer.row().padTop(5);
            addToSelectable(details);
        }
        performTouch(scrollPaneOfActor(scrollContainer)); //can use mouse wheel if available to scroll
    }

    private void backToList() {
        disposeRaidMap();
        abandonQuestButton.setVisible(false);
        trackButton.setVisible(false);
        backToListButton.setVisible(false);

        root.setVisible(true);
        detailRoot.setVisible(false);
    }

    private void loadDetailsPane(AdventureQuestData quest) {
        if (quest == null) {
            return;
        }
        root.setVisible(false);
        detailRoot.setVisible(true);
        detailScrollContainer.clear();
        detailScrollContainer.row();
        trackButton.clearListeners();
        trackButton.addListener(new ClickListener() {
            public void clicked(InputEvent event, float x, float y) {
                toggleTracked(quest);
            }
        });

        abandonQuestButton.setColor(Color.RED);
        abandonQuestButton.clearListeners();
        abandonQuestButton.addListener(new ClickListener() {
            public void clicked(InputEvent event, float x, float y) {
                Dialog confirm = createGenericDialog("", forge.adventure.util.TfrLabels.abandonConfirm(quest, Forge.getLocalizer().getMessage("lblAbandonQuestConfirm")), // TFR round 992
                        Forge.getLocalizer().getMessage("lblYes"),Forge.getLocalizer().getMessage("lblNo"), () -> abandonQuest(quest), null);
                showDialog(confirm);
            }
        });

        TypingLabel dNameLabel = Controls.newTypingLabel(quest.getName() + QuestExpiry.questLogSuffix(quest));
        dNameLabel.skipToTheEnd();
        dNameLabel.setWrap(true);
        dNameLabel.setColor(Color.BLACK);
        forge.adventure.util.TfrScreenTips.questName(this, dNameLabel, quest); // TFR round 1000: its time limit

        detailScrollContainer.add(dNameLabel).align(Align.left).expandX().padLeft(10);

        abandonQuestButton.setVisible(!quest.storyQuest);
        collapseAbandonRow(false); // round 865 (follow-up): a raid's page folds it away in portrait
        trackButton.setVisible(true);
        backToListButton.setVisible(true);

        TypingLabel dDescriptionLabel = Controls.newTypingLabel(quest.getDescription());
        dDescriptionLabel.skipToTheEnd();
        dDescriptionLabel.setWrap(true);
        dDescriptionLabel.setColor(Color.DARK_GRAY);

        detailScrollContainer.row();
        detailScrollContainer.add(dDescriptionLabel).align(Align.left).padLeft(25).width(detailRoot.getWidth() -25);

        for (AdventureQuestStage stage : quest.getCompletedStages()) {
            // Completed Stages will have Checkbox unicode
            TypingLabel completeLabel = Controls.newTypingLabel("{GRADIENT=OLIVE;LIME;1;1}\u2611 " + stage.name + "{ENDGRADIENT}[BLACK]");
            completeLabel.skipToTheEnd();
            completeLabel.setWrap(true);
            detailScrollContainer.row();
            detailScrollContainer.add(completeLabel).align(Align.left).padLeft(25);
        }

        for (AdventureQuestStage stage : quest.getActiveStages()) {
            // Active Stages will have Blank box unicode
            TypingLabel activeLabel = Controls.newTypingLabel("\u2610  " + stage.name + stage.getProgressText()); // round 240: " (2/5)"
            activeLabel.skipToTheEnd();
            activeLabel.setColor(Color.BLACK);
            activeLabel.setWrap(true);
            detailScrollContainer.row();
            detailScrollContainer.add(activeLabel).align(Align.left).padLeft(25);

            quest.logUnfilledTokens("the Quest Log", stage.description); // round 749
            TypingLabel activeDescriptionLabel = Controls.newTypingLabel(stage.description);
            activeDescriptionLabel.skipToTheEnd();
            activeDescriptionLabel.setColor(Color.DARK_GRAY);
            activeDescriptionLabel.setWrap(true);

            detailScrollContainer.row();
            detailScrollContainer.add(activeDescriptionLabel).padLeft(35).width(detailRoot.getWidth() - 50);
            detailScrollContainer.row();

            // Round 893 (the user: "that info is not on this card"): the where-to-look hint, shown once as the stage's
            // prologue, stays here while the stage is active. The saved prologue already has its tokens filled.
            if (stage.prologue != null && stage.prologue.text != null && !stage.prologue.text.isEmpty()
                    && AdventureQuestController.instance().stageHintOnCard(quest.getID(), stage.id)) {
                quest.logUnfilledTokens("the Quest Log", stage.prologue.text);
                TypingLabel hintLabel = Controls.newTypingLabel(stage.prologue.text);
                hintLabel.skipToTheEnd();
                hintLabel.setColor(Color.DARK_GRAY);
                hintLabel.setWrap(true);

                detailScrollContainer.add(hintLabel).padLeft(35).padTop(3).width(detailRoot.getWidth() - 50);
                detailScrollContainer.row();
            }
        }
        updateTrackButton(quest.isTracked);
    }

    /**
     * Round 865 (the user: "I think it would be helpful it the "Pillaged" quest had a details section. Maybe inside that
     * a mini-map of the location"; mock-up notes/r865-866-mock.png approved): a pillaged town's page in the quest
     * details pane - the raiders beaten, the days left, the way there, the raiders out, the reward and the penalty
     * (TownPillage.raid), beside a RaidMiniMap of the town (above it in portrait). The Track button becomes Show on Map
     * (MapViewScene centered on the town); there is nothing to abandon. Logs [TFR-Pillage] with what the page showed.
     */
    private void loadRaidDetails(String townId) {
        TownPillage.Raid raid = TownPillage.raid(townId);
        if (raid == null) { // it ended while the log was open
            buildList();
            return;
        }
        disposeRaidMap();
        root.setVisible(false);
        detailRoot.setVisible(true);
        detailScrollContainer.clear();
        detailScrollContainer.row();
        abandonQuestButton.setVisible(false);
        collapseAbandonRow(true);
        trackButton.setVisible(true);
        backToListButton.setVisible(true);
        trackButton.clearListeners();
        trackButton.setText("Show on Map");
        trackButton.addListener(new ClickListener() {
            public void clicked(InputEvent event, float x, float y) {
                MapViewScene.instance().focusOn(raid.poi.getCenter());
                Forge.switchScene(MapViewScene.instance());
            }
        });

        boolean landscape = Forge.isLandscapeMode();
        float mapSize = landscape ? 130f : 150f; // round 865 (follow-up): 180 pushed the penalty line off the paper
        float textWidth = landscape ? detailRoot.getWidth() - mapSize - 30f : detailRoot.getWidth() - 30f;
        Table facts = new Table(Controls.getSkin());
        TypingLabel title = Controls.newTypingLabel("[BLACK]Pillaged: " + raid.town);
        title.skipToTheEnd();
        facts.add(title).align(Align.left).padBottom(3);
        String rest = "[#5a5048]Raiders from the dungeons nearby are pillaging " + raid.town + ", "
                + (raid.playersTown ? "one of your towns." : "a neutral town you know.");
        String daysWord = raid.daysLeft == 1 ? " day" : " days";
        String[] lines = {
                rest,
                "[BLACK]☐  Beat the raiders: " + raid.beaten + " of " + raid.kills,
                "[BLACK]Time: " + raid.daysLeft + daysWord + " left",
                "[BLACK]Where: " + (raid.distanceTiles <= 1 ? "here" : raid.direction + ", " + raid.distanceTiles
                        + " tiles from you"),
                raid.near ? "[BLACK]Raiders out now: " + raid.raidersOut
                        : "[#5a5048]Raiders come out when you are within " + raid.rangeTiles + " tiles of " + raid.town + ".",
                "[BLACK]Reward: [WHITE][+Reputation][BLACK] +" + raid.rewardReputation + ", [WHITE][+Wood][BLACK] +" + raid.rewardWood
                        + ", [WHITE][+Stone][BLACK] +" + raid.rewardStone,
                "[#963228]If time runs out: -" + raid.failReputation + " [WHITE][+Reputation][#963228] with " + raid.town};
        for (String text : lines) {
            TypingLabel label = Controls.newTypingLabel(text);
            label.skipToTheEnd();
            label.setWrap(true);
            facts.row();
            facts.add(label).align(Align.left).width(textWidth).padTop(2);
        }
        raidMap = new RaidMiniMap(raid, mapSize);
        if (landscape) {
            detailScrollContainer.add(facts).align(Align.topLeft).padLeft(10).expandX();
            detailScrollContainer.add(raidMap).size(mapSize).align(Align.top).padRight(10).padTop(4);
        } else {
            detailScrollContainer.add(raidMap).size(mapSize).padTop(6).padBottom(6);
            detailScrollContainer.row();
            detailScrollContainer.add(facts).align(Align.left).padLeft(10);
        }
        System.out.println("[TFR-Pillage] quest log Details: " + raid.town + " - " + raid.beaten + " of " + raid.kills
                + ", " + raid.daysLeft + daysWord + " left, " + raid.direction + " " + raid.distanceTiles + " tiles, "
                + (raid.near ? raid.raidersOut + " raider(s) out, " + raidMap.dots() + " on the mini-map, "
                + raidMap.hidden() + " in the fog" : "too far for raiders") + (landscape ? "" : " (portrait)"));
    }

    private RaidMiniMap raidMap;

    /**
     * Round 865 (follow-up): portrait stacks Abandon, Track and Quest List in rows of their own, and a hidden Abandon
     * still kept its row - an empty band over Show on Map that, with the mini-map, pushed the raid page's last lines
     * off the paper at 540x960. The raid page folds that row to nothing; a quest's page gives it back (stock's
     * padTop(5) and the cell's default heights). Landscape keeps its three buttons side by side, so nothing to fold.
     */
    private void collapseAbandonRow(boolean collapse) {
        if (Forge.isLandscapeMode())
            return;
        Cell<?> cell = detailRoot.getCell(abandonQuestButton);
        if (cell == null)
            return;
        if (collapse)
            cell.height(0).padTop(0);
        else
            cell.minHeight(Value.minHeight).prefHeight(Value.prefHeight).maxHeight(Value.zero).padTop(5f);
        detailRoot.invalidate();
    }

    private void disposeRaidMap() {
        if (raidMap != null) {
            raidMap.remove();
            raidMap.dispose();
            raidMap = null;
        }
    }

    private void toggleTracked(AdventureQuestData quest) {
        quest.isTracked = !quest.isTracked;
        if (quest.isTracked) {
            AdventureQuestController.trackQuest(quest);
        }
        updateTrackButton(quest.isTracked);
    }

    private void updateTrackButton(boolean isTracked) {
        if (isTracked) {
            trackButton.setText(Forge.getLocalizer().getMessage("lblUntrackQuest"));
        } else {
            trackButton.setText(Forge.getLocalizer().getMessage("lblTrackQuest"));
        }
    }

    private void status() {
        Forge.switchScene(PlayerStatisticScene.instance(lastGameScene),true);
    }

    @Override
    public boolean back(){
        disposeRaidMap(); // round 865
        Adventure.getInstance().renderTransitionScreen = true;
        //Needed so long as quest log and stats are separate scenes that link to each other
        Forge.switchScene(lastGameScene==null ? GameScene.instance() : lastGameScene);
        return true;
    }

    private void abandonQuest(AdventureQuestData quest) {
        AdventureQuestController.instance().abandon(quest);
        AdventureQuestController.instance().showQuestDialogs(MapStage.getInstance());
        buildList();
    }
}
