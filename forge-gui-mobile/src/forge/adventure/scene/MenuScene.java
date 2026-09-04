package forge.adventure.scene;

import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Timer;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TypingAdapter;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.data.DialogData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.util.Reward;
import forge.card.ColorSet;
import forge.util.Localizer;

import java.util.ArrayList;

/**
 * MenuScene
 * Superclass for menu scenes which do not have HUD but need dialog functionality
 */
public class MenuScene extends UIScene {
    protected final Dialog dialog;
    private final Array<TextraButton> dialogButtonMap = new Array<>();
    protected java.util.List<ChangeListener> dialogCompleteList = new ArrayList<>();

    public MenuScene(String uiFilePath) {
        super(uiFilePath);
        dialog = Controls.newDialog("");
    }

    public Dialog getDialog() {
        return dialog;
    }

    public boolean activate(Array<DialogData> data) { //Method for actors to show their dialogues.
        boolean dialogShown = false;
        if (data != null) {
            for (DialogData dialog : data) {
                if (isConditionOk(dialog.condition)) {
                    loadDialog(dialog);
                    dialogShown = true;
                }
            }
        }
        return dialogShown;
    }

    void setEffects(DialogData.ActionData[] data) {
        if (data == null) return;
        for (DialogData.ActionData E : data) {
            if (E == null) {
                continue;
            }
            if (E.removeItem != null && (!E.removeItem.isEmpty())) { //Removes an item from the player's inventory.
                Current.player().removeItem(E.removeItem);
            }
            if (E.addItem != null && (!E.addItem.isEmpty())) { //Gives an item to the player.
                Current.player().addItem(E.addItem);
            }
            if (E.addLife != 0) { //Gives (positive or negative) life to the player. Cannot go over max health.
                Current.player().heal(E.addLife);
            }
            if (E.addGold != 0) { //Gives (positive or negative) gold to the player.
                if (E.addGold > 0) Current.player().giveGold(E.addGold);
                else Current.player().takeGold(-E.addGold);
            }
            if (E.grantRingGift != null && !E.grantRingGift.isEmpty()) // round 101: a Ring City hands over part of the starting kit
                Current.player().grantRingGift(E.grantRingGift);
            if (E.addShards != 0) { //Gives (positive or negative) mana shards to the player.
                if (E.addShards > 0) Current.player().addShards(E.addShards);
                else Current.player().takeShards(-E.addShards);
            }
            if (E.giveBlessing != null) { //Gives a blessing for your next battle.
                Current.player().addBlessing(E.giveBlessing);
            }
            if (E.setColorIdentity != null && !E.setColorIdentity.isEmpty()) { //Sets color identity (use sparingly)
                Current.player().setColorIdentity(E.setColorIdentity);
            }
            if (E.setCharacterFlag != null && !E.setCharacterFlag.key.isEmpty()) { //Set a quest to given value.
                Current.player().setCharacterFlag(E.setCharacterFlag.key, E.setCharacterFlag.val);
            }
            if (E.advanceCharacterFlag != null && !E.advanceCharacterFlag.isEmpty()) { //Increase a given quest flag by 1.
                Current.player().advanceCharacterFlag(E.advanceCharacterFlag);
            }
            if (E.setQuestFlag != null && !E.setQuestFlag.key.isEmpty()) { //Set a quest to given value.
                Current.player().setQuestFlag(E.setQuestFlag.key, E.setQuestFlag.val);
            }
            if (E.advanceQuestFlag != null && !E.advanceQuestFlag.isEmpty()) { //Increase a given quest flag by 1.
                Current.player().advanceQuestFlag(E.advanceQuestFlag);
            }
            if (E.grantRewards != null && E.grantRewards.length > 0) {
                Array<Reward> ret = new Array<Reward>();
                for (RewardData rdata : E.grantRewards) {
                    ret.addAll(rdata.generate(false, true));
                }
                RewardScene.instance().loadRewards(ret, RewardScene.Type.QuestReward, null);
                Forge.switchScene(RewardScene.instance());
            }
//            if (E.issueQuest != null && (!E.issueQuest.isEmpty())) {
//                emitQuestAccepted();
//            }
        }
    }

    void loadDialog(DialogData dialog) { //Displays a dialog with dialogue and possible choices.
        setEffects(dialog.action);
        Dialog D = getDialog();
        Localizer L = Forge.getLocalizer();
        D.getTitleTable().clear();
        D.getContentTable().clear();
        D.getButtonTable().clear(); //Clear tables to start fresh.
        D.clearListeners();
//        Sprite sprite = null;

//        Actor actor = stage.getByID(parentID);
//        if (actor instanceof CharacterSprite)
//            sprite = ((CharacterSprite) actor).getAvatar();
        String text; //Check for localized string (locname), otherwise print text.
        if (dialog.loctext != null && !dialog.loctext.isEmpty()) text = L.getMessage(dialog.loctext);
        else text = dialog.text;

        TypingLabel A = Controls.newTypingLabel(text);
        A.setWrap(true);
        Array<TextraButton> buttons = new Array<>();
        A.setTypingListener(new TypingAdapter() {
            @Override
            public void end() {
                float delay = 0.09f;
                for (TextraButton button : buttons) {
                    Timer.schedule(new Timer.Task() {
                        @Override
                        public void run() {
                            button.setVisible(true);
                        }
                    }, delay);
                    delay += 0.10f;
                }
            }
        });
        float width = 250f;

        D.getContentTable().add(A).width(width); //Add() returns a Cell, which is what the width is being applied to.
        if (dialog.options != null) {
            int i = 0;
            for (DialogData option : dialog.options) {
                if (isConditionOk(option.condition)) {
                    String name; //Get localized label if present.
                    if (option.locname != null && !option.locname.isEmpty()) name = L.getMessage(option.locname);
                    else name = option.name;
                    TextraButton B = Controls.newTextButton(name, () -> {
                        loadDialog(option);

                        if (option.callback != null) {
                            option.callback.accept(true);
                        }
                    });
                    B.getTextraLabel().setWrap(true); //We want this to wrap in case it's a wordy choice.
                    buttons.add(B);
                    B.setVisible(false);
                    D.getButtonTable().add(B).width(width - 10); //The button table also returns a Cell when adding.
                    //TODO: Reducing the space a tiny bit could help. But should be fine as long as there aren't more than 4-5 options.
                    D.getButtonTable().row(); //Add a row. Tried to allow a few per row but it was a bit erratic.
                    i++;
                }
            }
            D.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    A.skipToTheEnd();
                    super.clicked(event, x, y);
                }
            });
            if (i == 0) {
                hideDialog();
                emitDialogFinished();
            } else
                showDialog(getDialog());
        } else {
            hideDialog();
        }
    }

    public void addDialogCompleteListener(ChangeListener listener) {
        dialogCompleteList.add(listener);
    }

    private void emitDialogFinished() {
        if (dialogCompleteList != null && dialogCompleteList.size() > 0) {
            ChangeListener.ChangeEvent evt = new ChangeListener.ChangeEvent();
            for (ChangeListener listener : dialogCompleteList) {
                listener.changed(evt, null);
            }
        }
    }

    public boolean isConditionOk(DialogData.ConditionData[] data) {
        if (data == null) return true;
        AdventurePlayer player = Current.player();
        for (DialogData.ConditionData condition : data) {
            //TODO:Check for card in inventory.
            if (condition.item != null && !condition.item.isEmpty()) { //Check for an item in player's inventory.
                if (!player.hasItem(condition.item)) {
                    if (!condition.not) return false; //Only return on a false.
                } else if (condition.not) return false;
            }
            if (condition.colorIdentity != null && !condition.colorIdentity.isEmpty()) { //Check for player's color ID.
                if (player.getColorIdentity().hasAllColors(ColorSet.fromNames(condition.colorIdentity.toCharArray()).getColor())) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.hasGold != 0) { //Check for at least X gold.
                if (player.getGold() < condition.hasGold) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.hasShards != 0) { //Check for at least X gold.
                if (player.getShards() < condition.hasShards) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.hasLife != 0) { //Check for at least X life..
                if (player.getLife() < condition.hasLife + 1) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.hasBlessing != null && !condition.hasBlessing.isEmpty()) { //Check for a named blessing.
                if (!player.hasBlessing(condition.hasBlessing)) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }

            if (condition.getQuestFlag != null) {
                String key = condition.getQuestFlag.key;
                String cond = condition.getQuestFlag.op;
                int val = condition.getQuestFlag.val;
                int QF = player.getQuestFlag(key);
                if (!player.checkQuestFlag(key)) return false; //If the quest is not ongoing, stop.
                if (!checkFlagCondition(QF, cond, val)) {
                    if (!condition.not) return false;
                } else {
                    if (condition.not) return false;
                }
            }
            if (condition.checkQuestFlag != null && !condition.checkQuestFlag.isEmpty()) {
                if (!player.checkQuestFlag(condition.checkQuestFlag)) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }

            if (condition.getCharacterFlag != null) {
                String key = condition.getCharacterFlag.key;
                String cond = condition.getCharacterFlag.op;
                int val = condition.getCharacterFlag.val;
                int QF = player.getCharacterFlag(key);
                if (!player.checkCharacterFlag(key)) return false; //If the quest is not ongoing, stop.
                if (!checkFlagCondition(QF, cond, val)) {
                    if (!condition.not) return false;
                } else {
                    if (condition.not) return false;
                }
            }
            if (condition.checkCharacterFlag != null && !condition.checkCharacterFlag.isEmpty()) {
                if (!player.checkCharacterFlag(condition.checkCharacterFlag)) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
        }
        return true;
    }

    private boolean checkFlagCondition(int flag, String condition, int value) {
        switch (condition.toUpperCase()) {
            default:
            case "EQUALS":
            case "EQUAL":
            case "=":
                if (flag == value) return true;
            case "LESSTHAN":
            case "<":
                if (flag < value) return true;
            case "MORETHAN":
            case ">":
                if (flag > value) return true;
            case "LE_THAN":
            case "<=":
                if (flag <= value) return true;
            case "ME_THAN":
            case ">=":
                if (flag >= value) return true;
        }
        return false;
    }

    public void showDialog(Array<DialogData> data) {
        if (!activate(data)) {
            return;
        }
        // The tree may have ended on a leaf, which calls hideDialog() and unwinds the stack.
        // Re-showing the shared Dialog below would put an emptied, buttonless, MODAL window back
        // on the stage that UIScene.removeDialog() can never reach (2026-09-01 soft-lock).
        // No caller exercises this path today - the guard is here so the next one cannot.
        if (!dialogs.contains(dialog, true)) {
            return;
        }

        dialogButtonMap.clear();
        for (int i = 0; i < dialog.getButtonTable().getCells().size; i++) {
            dialogButtonMap.add((TextraButton) dialog.getButtonTable().getCells().get(i).getActor());
        }
        dialog.show(stage, Actions.show());
        dialog.setPosition((stage.getWidth() - dialog.getWidth()) / 2, (stage.getHeight() - dialog.getHeight()) / 2);
        if (Forge.hasExternalInput() && !dialogButtonMap.isEmpty())
            stage.setKeyboardFocus(dialogButtonMap.first());
    }

    /**
     * Hides the shared dialog AND unwinds UIScene's dialog stack for it.
     * <p>
     * 2026-09-01 soft-lock, user-reported: "a second bar appears and I can't exit at all. Had to
     * Hard Quit the game." MenuScene reuses ONE Dialog instance for every node of a DialogData
     * tree - loadDialog() clears its three tables, refills them, and calls showDialog(getDialog())
     * for any node that HAS options, which pushes that instance onto UIScene.dialogs. The LEAF
     * node (no options) used to end the tree with a bare dialog.hide(), so the stack entry stayed
     * forever, pointing at a Window whose tables had since been cleared. The next
     * UIScene.removeDialog() then called show(stage) on it and re-raised an empty, titleless,
     * BUTTONLESS, MODAL, non-movable window: nothing on it could call removeDialog(), libGDX's
     * Window.hit() swallowed every click elsewhere on screen, and dialogShowing() disabled the
     * Back keybind. Hard quit was the only way out.
     * <p>
     * Surfaced by EventScene's Inn "Coin Returned" dialog (round 77), whose OK button sat on the
     * entry-fee dialog's corpse - but the leak is older than that code and every MenuScene
     * subclass had it. NewGameScene and EventScene.validateDeck() leak identically; they simply
     * never happened to be followed by a removeDialog().
     */
    public void hideDialog() {
        // A cyclic DialogData tree pushes the SAME instance once per visited node that had
        // options, so unwind EVERY entry - and the possibleSelectionStack frame each one pushed
        // alongside it. Index 0 of that stack is the scene's own ui.selectActors (UIScene ctor),
        // never a dialog's, so it must survive: showDialog() pushes both together, which makes
        // dialogs[i] the owner of possibleSelectionStack[i + 1].
        int index, removed = 0;
        while ((index = dialogs.indexOf(dialog, true)) != -1) {
            dialogs.removeIndex(index);
            if (index + 1 < possibleSelectionStack.size)
                possibleSelectionStack.removeIndex(index + 1);
            removed++;
        }
        if (removed > 0)
            System.out.println("[TFR-Dialog] hideDialog unwound " + removed + " stale stack entr"
                    + (removed == 1 ? "y" : "ies") + "; dialogs=" + dialogs.size
                    + " selStack=" + possibleSelectionStack.size);
        dialog.hide();
        dialog.clearListeners();
    }
}
