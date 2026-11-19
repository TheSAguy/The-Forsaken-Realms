package forge.adventure.scene;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * TFR round 1053: UIScene's per-frame dialog re-assert (round 120) keeps the top dialog above whatever the scene adds,
 * but leaves the dialog's own drop-down list (a SelectBox popup on the stage root) above it. Before, the dialog was
 * pulled over the list every frame, so the New Game+ Difficulty list opened behind the dialog and a tap closed it.
 */
public class DialogZOrderTest {

    private static Actor named(String name) {
        Actor a = new Actor();
        a.setName(name);
        return a;
    }

    private static String order(Group root) {
        StringBuilder sb = new StringBuilder();
        for (Actor a : root.getChildren())
            sb.append(sb.length() == 0 ? "" : ",").append(a.getName());
        return sb.toString();
    }

    private static final Predicate<Actor> POPUP = a -> a.getName().startsWith("popup");

    @Test
    public void ownPopupStaysAboveTheDialog() {
        Group root = new Group();
        Actor scene = named("scene"), dialog = named("dialog"), popup = named("popup");
        root.addActor(scene);
        root.addActor(dialog);
        root.addActor(popup);
        UIScene.keepDialogInFront(root, dialog, POPUP);
        AssertJUnit.assertEquals("scene,dialog,popup", order(root));
    }

    @Test
    public void somethingElseAboveComesBelowTheDialog() {
        Group root = new Group();
        Actor dialog = named("dialog"), card = named("card");
        root.addActor(dialog);
        root.addActor(card); // round 120: RewardScene's rebuilt cards
        UIScene.keepDialogInFront(root, dialog, POPUP);
        AssertJUnit.assertEquals("card,dialog", order(root));
    }

    @Test
    public void popupKeptOnTopWhenACardLandsAboveBoth() {
        Group root = new Group();
        Actor dialog = named("dialog"), popup = named("popup"), card = named("card");
        root.addActor(dialog);
        root.addActor(popup);
        root.addActor(card);
        UIScene.keepDialogInFront(root, dialog, POPUP);
        AssertJUnit.assertEquals("card,dialog,popup", order(root));
    }

    @Test
    public void dialogAlreadyOnTopIsLeftAlone() {
        Group root = new Group();
        Actor scene = named("scene"), dialog = named("dialog");
        root.addActor(scene);
        root.addActor(dialog);
        UIScene.keepDialogInFront(root, dialog, POPUP);
        AssertJUnit.assertEquals("scene,dialog", order(root));
    }
}
