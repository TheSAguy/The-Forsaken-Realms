package forge.assets;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.util.List;

/**
 * TFR round 1008 (logs-1110b P3-21): after the APK goes to Android's installer, the game no longer exits; a dialog waits
 * for the player. These pin the dialog's pure decision logic: the buttons, what each answer means, and the text.
 */
public class AssetsDownloaderInstallerReturnTest {

    @Test
    public void keepPlayingIsTheLastButtonSoBackKeepsPlaying() {
        // FOptionPane maps Back/Escape to the LAST button: Back must never reopen the installer.
        List<String> withApk = AssetsDownloader.installerReturnOptions(true);
        AssertJUnit.assertEquals(2, withApk.size());
        AssertJUnit.assertEquals(AssetsDownloader.OPEN_INSTALLER_AGAIN, withApk.get(0));
        AssertJUnit.assertEquals(AssetsDownloader.KEEP_PLAYING, withApk.get(withApk.size() - 1));
    }

    @Test
    public void noApkMeansNothingToReopen() {
        List<String> noApk = AssetsDownloader.installerReturnOptions(false);
        AssertJUnit.assertEquals(1, noApk.size());
        AssertJUnit.assertEquals(AssetsDownloader.KEEP_PLAYING, noApk.get(0));
        AssertJUnit.assertEquals(AssetsDownloader.InstallerReturn.KEEP_PLAYING,
                AssetsDownloader.installerReturnAction(0, noApk));
    }

    @Test
    public void answersMapToActions() {
        List<String> o = AssetsDownloader.installerReturnOptions(true);
        AssertJUnit.assertEquals(AssetsDownloader.InstallerReturn.REOPEN, AssetsDownloader.installerReturnAction(0, o));
        AssertJUnit.assertEquals(AssetsDownloader.InstallerReturn.KEEP_PLAYING, AssetsDownloader.installerReturnAction(1, o));
        // a dialog closed without a button, or anything out of range, keeps playing rather than looping the installer
        AssertJUnit.assertEquals(AssetsDownloader.InstallerReturn.KEEP_PLAYING, AssetsDownloader.installerReturnAction(-1, o));
        AssertJUnit.assertEquals(AssetsDownloader.InstallerReturn.KEEP_PLAYING, AssetsDownloader.installerReturnAction(2, o));
    }

    @Test
    public void messageNamesBothVersionsAndTheButtons() {
        String m = AssetsDownloader.installerReturnMessage("1.29", "1.30", true, true);
        AssertJUnit.assertTrue(m, m.contains("Tap Update there to install v1.30"));
        AssertJUnit.assertTrue(m, m.contains(AssetsDownloader.OPEN_INSTALLER_AGAIN));
        AssertJUnit.assertTrue(m, m.contains("no second download"));
        AssertJUnit.assertTrue(m, m.contains(AssetsDownloader.KEEP_PLAYING + " to play v1.29"));

        String notOpened = AssetsDownloader.installerReturnMessage("1.29", "1.30", false, true);
        AssertJUnit.assertTrue(notOpened, notOpened.startsWith("Android's installer did not open."));

        String gone = AssetsDownloader.installerReturnMessage("1.29", "1.30", true, false);
        AssertJUnit.assertFalse(gone, gone.contains(AssetsDownloader.OPEN_INSTALLER_AGAIN));
        AssertJUnit.assertTrue(gone, gone.contains("offered again at the next launch"));
    }

    @Test
    public void keepOnScreenAdviceIsThere() {
        AssertJUnit.assertTrue(AssetsDownloader.KEEP_ON_SCREEN.contains("Keep the game on screen"));
        AssertJUnit.assertTrue(AssetsDownloader.KEEP_ON_SCREEN_FAILED.contains("keep the game on screen"));
    }
}
