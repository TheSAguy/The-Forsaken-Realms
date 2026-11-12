package forge.adventure.util;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;

/**
 * TFR round 1013 (logs-1111b P3-26): the second-copy warning shows only when another live process holds
 * lastexit.lock, on desktop; a stale lock file a crash left, a lock this JVM already holds and a file system without
 * locks never warn. Also LastExit.takeLock's reading of a real lock file.
 */
public class SecondCopyTest {
    @Test
    public void warnsOnlyForAnotherLiveCopyOnDesktop() {
        AssertJUnit.assertTrue(SecondCopy.shouldWarn(SecondCopy.Lock.HELD_ELSEWHERE, false));
        AssertJUnit.assertFalse("a phone runs one process", SecondCopy.shouldWarn(SecondCopy.Lock.HELD_ELSEWHERE, true));
        AssertJUnit.assertFalse("a stale file is taken", SecondCopy.shouldWarn(SecondCopy.Lock.TAKEN, false));
        AssertJUnit.assertFalse("the same process", SecondCopy.shouldWarn(SecondCopy.Lock.SAME_PROCESS, false));
        AssertJUnit.assertFalse("no locking: unknown", SecondCopy.shouldWarn(SecondCopy.Lock.UNSUPPORTED, false));
        AssertJUnit.assertFalse("not checked", SecondCopy.shouldWarn(SecondCopy.Lock.NOT_CHECKED, false));
        AssertJUnit.assertFalse(SecondCopy.shouldWarn(null, false));
    }

    @Test
    public void aStaleLockFileIsTakenAndTheSameProcessIsNotASecondCopy() throws Exception {
        File dir = Files.createTempDirectory("tfr-secondcopy").toFile();
        File file = new File(dir, "lastexit.lock");
        // a crash leaves the file behind with no process holding it
        AssertJUnit.assertTrue(file.createNewFile());
        AssertJUnit.assertEquals(SecondCopy.Lock.TAKEN, LastExit.takeLock(file));
        // asking again from this JVM overlaps its own lock
        AssertJUnit.assertEquals(SecondCopy.Lock.SAME_PROCESS, LastExit.takeLock(file));
    }

    @Test
    public void aLockHeldByAnotherProcessIsHeldElsewhere() throws Exception {
        File dir = Files.createTempDirectory("tfr-secondcopy").toFile();
        File file = new File(dir, "lastexit.lock");
        // a child JVM holds the lock, as a second copy of the game would
        String java = new File(System.getProperty("java.home"), "bin" + File.separator + "java").getPath();
        File src = new File(dir, "Hold.java");
        Files.write(src.toPath(), ("public class Hold { public static void main(String[] a) throws Exception {"
                + " java.nio.channels.FileChannel c = new java.io.RandomAccessFile(a[0], \"rw\").getChannel();"
                + " if (c.tryLock() == null) System.exit(2); System.out.println(\"held\"); System.out.flush();"
                + " Thread.sleep(30000); } }").getBytes());
        Process p = new ProcessBuilder(java, src.getPath(), file.getPath()).redirectErrorStream(true).start();
        try {
            byte[] buf = new byte[4];
            int n = p.getInputStream().read(buf);
            AssertJUnit.assertEquals("the child took the lock", "held", new String(buf, 0, Math.max(n, 0)));
            AssertJUnit.assertEquals(SecondCopy.Lock.HELD_ELSEWHERE, LastExit.takeLock(file));
        } finally {
            p.destroyForcibly().waitFor();
        }
        // the child is gone, so the OS has let go: the next copy takes it, no warning
        try (FileChannel c = new RandomAccessFile(file, "rw").getChannel(); FileLock l = c.tryLock()) {
            AssertJUnit.assertNotNull("a dead process's lock is free", l);
        }
    }
}
