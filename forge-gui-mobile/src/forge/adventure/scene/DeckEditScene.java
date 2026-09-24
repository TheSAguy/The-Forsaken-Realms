package forge.adventure.scene;

import com.badlogic.gdx.graphics.g2d.TextureRegion;
import forge.Adventure;
import forge.adventure.data.AdventureEventData;
import forge.screens.FScreen;

/**
 * DeckEditScene
 * Scene class that contains the Deck editor layout
 */
public class DeckEditScene extends ForgeScene {

    AdventureDeckEditor screen;
    AdventureEventData currentEvent;

    private AdventureEventData lastLoadedEventContext = null;
    // Round 312: the deck the cached editor was built for - see enter().
    private forge.deck.Deck builtForDeck = null;

    private DeckEditScene() {
    }

    private static DeckEditScene object;
    TextureRegion backDrop;

    public static DeckEditScene getInstance(TextureRegion backdrop) {
        if(object == null)
            object = new DeckEditScene();
        object.backDrop = backdrop;
        return object;
    }

    public void loadEvent(AdventureEventData event){
        currentEvent = event;
    }

    @Override
    public boolean leave() {
        Adventure.getInstance().renderTransitionScreen = true;
        return super.leave();
    }

    @Override
    public void enter() {
        Adventure.getInstance().renderTransitionScreen = false;
        // Round 312 (a player's report on v1.13: "the edit deck feature doesn't work anymore. All decks show the exact
        // same deck as the one I had selected when loading the save. However if I select deck 2, it will use deck 2 in
        // combat"). Upstream's 09.21 refactor (#11945, in the 09.22 daily of round 289) keeps the editor between visits
        // unless the event changes, where it used to rebuild it every time - and the editor is built around ONE deck,
        // the one selected when it was made. After switching deck slots it went on showing, and editing, that one.
        // It is rebuilt now whenever the selected deck is not the one it was built for.
        forge.deck.Deck selected = currentEvent == null ? forge.adventure.util.Current.player().getSelectedDeck() : null;
        boolean otherDeck = currentEvent == null && screen != null && selected != builtForDeck;
        if (lastLoadedEventContext != currentEvent || otherDeck) {
            screen = null;
            lastLoadedEventContext = currentEvent;
        }
        getScreen();
        if (currentEvent == null)
            System.out.println("[TFR-DeckEditor] editing \"" + (selected == null ? "?" : selected.getName()) + "\" (slot "
                    + (forge.adventure.util.Current.player().getSelectedDeckIndex() + 1) + ")"
                    + (otherDeck ? " - rebuilt, the editor was open on another deck" : ""));
        screen.refresh();
        super.enter();
    }

    @Override
    public FScreen getScreen() {
        if (screen == null) {
            if (currentEvent == null) {
                screen = new AdventureDeckEditor(false, backDrop);
                screen.setEvent(null);
                builtForDeck = forge.adventure.util.Current.player().getSelectedDeck(); // round 312
            }
            else {
                screen = new AdventureDeckEditor(currentEvent, backDrop);
            }
        }
        return screen;
    }
}
