package forge.gamemodes.match;

import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import org.mockito.Mockito;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.Queue;
import java.util.Timer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TFR round 941 (notes/logs-1103b.md P2-1): {@link AbstractGuiGame#awaitNextInput()} is called by the game thread and,
 * when an input finishes, by the GUI thread, which also cancels the pending task when the next input shows. Raced from
 * those threads it threw "Task already scheduled or cancelled" and killed the game thread at a duel's first priority.
 */
public class AbstractGuiGameShould {

    private IGuiBase previousGuiBase;
    private IGuiBase guiBase;
    private AbstractGuiGame gui;

    @BeforeMethod
    public void setUp() throws Exception {
        previousGuiBase = GuiBase.getInterface();
        guiBase = Mockito.mock(IGuiBase.class); // the "waiting" prompt update is posted to the EDT; here it is only counted
        GuiBase.setInterface(guiBase);
        gui = Mockito.mock(AbstractGuiGame.class,
                Mockito.withSettings().useConstructor().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        // checkAwaitNextInputTimer names the timer after the game view, which a test has none of
        Field timer = AbstractGuiGame.class.getDeclaredField("awaitNextInputTimer");
        timer.setAccessible(true);
        timer.set(gui, new Timer("test awaitNextInputTimer", true));
    }

    @AfterMethod
    public void tearDown() {
        gui.afterGameEnd();
        GuiBase.setInterface(previousGuiBase);
    }

    @Test
    public void notThrowWhenTwoThreadsAwaitTheNextInputWhileItIsCancelled() throws Exception {
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        CyclicBarrier start = new CyclicBarrier(3);
        Thread game = race(start, until, gui::awaitNextInput, errors);
        Thread edt = race(start, until, gui::awaitNextInput, errors);
        Thread nextInputShown = race(start, until, gui::cancelAwaitNextInput, errors);
        game.join();
        edt.join();
        nextInputShown.join();
        assertThat(errors).isEmpty();
    }

    @Test
    public void stillUpdateTheWaitingPromptAfterARace() throws Exception {
        notThrowWhenTwoThreadsAwaitTheNextInputWhileItIsCancelled();
        Mockito.clearInvocations(guiBase);

        gui.awaitNextInput();

        Mockito.verify(guiBase, Mockito.timeout(2000).atLeastOnce()).invokeInEdtLater(Mockito.any());
    }

    /** Calls {@code call} in a tight loop until {@code until} or the first error, so the three threads overlap. */
    private static Thread race(CyclicBarrier start, long until, Runnable call, Queue<Throwable> errors) {
        Thread thread = new Thread(() -> {
            try {
                start.await();
                while (System.nanoTime() < until && errors.isEmpty())
                    call.run();
            } catch (Throwable t) {
                errors.add(t);
            }
        });
        thread.start();
        return thread;
    }
}
