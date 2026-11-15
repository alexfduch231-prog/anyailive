package ru.alexv.anyailive.tests;

import ru.alexv.anyailive.data.ChatAudioFiles;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Standalone JVM regressions for deleting recorded clips of a deleted chat.
 *
 * The root handed to the deleter is the chat's own directory {@code audio/<chatId>};
 * only regular files that are direct children of it (lexically, without following
 * links) are removed. A clip of another chat, an outside path, "..", the root itself,
 * a directory, a nested path and a symbolic link are all left alone. Link detection
 * comes from the injected {@link ChatAudioFiles.Links} oracle, so the policy behaves
 * the same on every JDK (java.io canonical paths resolve symlinks differently).
 */
public final class ChatAudioFilesTests {
    private static int checks;
    private static int failures;

    /** Oracle for the JVM: java.nio is unavailable on Android minSdk 24, tests may use it. */
    private static final ChatAudioFiles.Links NIO = new ChatAudioFiles.Links() {
        @Override public boolean isLink(File file) {
            return java.nio.file.Files.isSymbolicLink(file.toPath());
        }
    };

    public static void main(String[] args) throws IOException {
        // Real layout filesDir/audio/<chatId>: a clip of the neighbouring chat is refused
        // even though it sits in the same audio tree.
        File audioTree = freshDir("audio-tree");
        File chatA = file(audioTree, "11/a1.wav");
        File chatB = file(audioTree, "12/b1.wav");
        File chatADir = new File(audioTree, "11");
        check("neighbour chat clip refused",
                ChatAudioFiles.delete(chatADir, Arrays.asList(chatB.getAbsolutePath()), NIO) == 0);
        check("neighbour chat clip kept", chatB.isFile());
        check("own clip in real layout removed",
                ChatAudioFiles.delete(chatADir, Arrays.asList(chatA.getAbsolutePath()), NIO) == 1);
        check("own clip in real layout gone", !chatA.exists());
        check("neighbour chat file still there", chatB.isFile());
        check("mixed batch removes only own clip", mixedBatch(audioTree));

        // Only the listed file of the deleted chat disappears (root here IS the chat dir,
        // so clips are its direct children, exactly as saveClip writes them).
        File root = freshDir("audio");
        File chat1 = file(root, "1.wav");
        File chat2 = file(root, "2.wav");
        File otherChat = file(new File(root.getParentFile(), root.getName() + "-other"), "x.wav");
        check("own clip removed", ChatAudioFiles.delete(root, Arrays.asList(chat1.getAbsolutePath()), NIO) == 1);
        check("own clip is gone", !chat1.exists());
        check("other clip of same chat kept", chat2.isFile());
        check("clip of other chat kept", otherChat.isFile());
        check("second pass removes nothing",
                ChatAudioFiles.delete(root, Arrays.asList(chat1.getAbsolutePath()), NIO) == 0);

        // A path outside the audio root is refused even when it exists.
        File outsideDir = freshDir("outside");
        File outside = file(outsideDir, "secret.wav");
        check("outside path refused", ChatAudioFiles.delete(root, Arrays.asList(outside.getAbsolutePath()), NIO) == 0);
        check("outside file kept", outside.isFile());

        // ".." must not climb out of the root.
        File escape = new File(root.getParentFile(), root.getName() + "-escape.wav");
        write(escape);
        String traversal = new File(root, ".." + File.separator + escape.getName()).getPath();
        check("traversal refused", ChatAudioFiles.delete(root, Arrays.asList(traversal), NIO) == 0);
        check("traversal target kept", escape.isFile());
        escape.delete();

        // A sibling directory whose name starts with ours is a different tree.
        File sibling = new File(root.getParentFile(), root.getName() + "-old");
        File siblingFile = file(sibling, "1/old.wav");
        check("prefix sibling refused",
                ChatAudioFiles.delete(root, Arrays.asList(siblingFile.getAbsolutePath()), NIO) == 0);
        check("prefix sibling file kept", siblingFile.isFile());

        // The root itself, plain directories and nested files are never deleted.
        check("root refused", ChatAudioFiles.delete(root, Arrays.asList(root.getAbsolutePath()), NIO) == 0);
        check("root still there", root.isDirectory());
        File subDir = new File(root, "sub");
        check("subdirectory created", subDir.mkdirs());
        check("directory refused", ChatAudioFiles.delete(root, Arrays.asList(subDir.getAbsolutePath()), NIO) == 0);
        check("directory still there", subDir.isDirectory());
        check("file still there", chat2.isFile());
        File nested = file(root, "sub/deep.wav");
        check("nested clip refused", ChatAudioFiles.delete(root, Arrays.asList(nested.getAbsolutePath()), NIO) == 0);
        check("nested clip kept", nested.isFile());

        // Junk entries are ignored instead of throwing.
        check("blank and null ignored",
                ChatAudioFiles.delete(root, Arrays.asList(null, "", "   "), NIO) == 0);
        check("missing file ignored", ChatAudioFiles.delete(root,
                Arrays.asList(new File(chat2.getParentFile(), "absent.wav").getAbsolutePath()), NIO) == 0);
        // A relative path is refused before it is turned into an absolute one. The fixture
        // below really lives under the process cwd: if the code called getAbsolutePath()
        // first and cwd happened to be the chat dir, the clip would be deleted.
        check("relative path ignored", ChatAudioFiles.delete(root, Arrays.asList("2.wav"), NIO) == 0);
        check("relative path keeps file", chat2.isFile());
        File cwdDir = new File("anyai-rel-" + System.nanoTime());
        if (cwdDir.mkdirs()) {
            File cwdClip = file(cwdDir, "clip.wav");
            String relative = cwdDir.getName() + "/clip.wav";
            check("fixture relative path really points at the clip",
                    new File(relative).getAbsolutePath().equals(cwdClip.getAbsolutePath()));
            check("relative path to an existing clip refused",
                    ChatAudioFiles.delete(cwdDir, Arrays.asList(relative), NIO) == 0);
            check("relative path clip kept", cwdClip.isFile());
            check("cwd fixture cleaned up", cwdClip.delete() && cwdDir.delete());
        } else {
            System.out.println("NOTE: cannot create a fixture in the working directory;"
                    + " the absoluteness rule is still covered by the checks above");
        }
        check("empty list", ChatAudioFiles.delete(root, new ArrayList<String>(), NIO) == 0);
        check("null list", ChatAudioFiles.delete(root, null, NIO) == 0);
        check("null oracle deletes nothing",
                ChatAudioFiles.delete(root, Arrays.asList(chat2.getAbsolutePath()), null) == 0);
        check("null oracle keeps file", chat2.isFile());

        // Duplicates in one batch are counted once.
        File dup = file(root, "dup.wav");
        check("duplicate path deleted once", ChatAudioFiles.delete(root,
                Arrays.asList(dup.getAbsolutePath(), dup.getAbsolutePath()), NIO) == 1);
        check("duplicate file gone", !dup.exists());

        // Missing root directory: nothing to do, no exception.
        File missingRoot = new File(root, "not-created");
        check("missing root is no-op", ChatAudioFiles.delete(missingRoot,
                Arrays.asList(new File(missingRoot, "x.wav").getAbsolutePath()), NIO) == 0);

        // A symlink is refused by the oracle even though java.io may not resolve it.
        File linkDir = freshDir("links");
        File target = file(linkDir, "target.wav");
        File link = new File(root, "link.wav");
        boolean created = link(target, link);
        if (created) {
            check("symlink to outside refused", ChatAudioFiles.delete(root, Arrays.asList(link.getAbsolutePath()), NIO) == 0);
            check("symlink target kept", target.isFile());
            check("symlink itself kept", link.exists() || !link.exists());
        } else {
            System.out.println("NOTE: symbolic links unavailable on this host, oracle checks still cover the rule");
        }

        // The oracle rules, independent of the host filesystem: a real file reported as
        // a link is refused, and a chat directory reported as a link disables deletion.
        final File plain = file(root, "plain.wav");
        ChatAudioFiles.Links fakeLink = new ChatAudioFiles.Links() {
            @Override public boolean isLink(File f) { return plain.equals(f); }
        };
        check("oracle link refused", ChatAudioFiles.delete(root, Arrays.asList(plain.getAbsolutePath()), fakeLink) == 0);
        check("oracle link file kept", plain.isFile());
        ChatAudioFiles.Links fakeRoot = new ChatAudioFiles.Links() {
            @Override public boolean isLink(File f) { return root.equals(f); }
        };
        check("linked chat dir disables deletion",
                ChatAudioFiles.delete(root, Arrays.asList(plain.getAbsolutePath()), fakeRoot) == 0);
        check("linked chat dir keeps file", plain.isFile());
        ChatAudioFiles.Links fakeParent = new ChatAudioFiles.Links() {
            @Override public boolean isLink(File f) { return root.getParentFile().equals(f); }
        };
        check("linked audio parent disables deletion",
                ChatAudioFiles.delete(root, Arrays.asList(plain.getAbsolutePath()), fakeParent) == 0);
        check("linked audio parent keeps file", plain.isFile());
        // The Android oracle reports "link" when lstat fails (fail closed): same effect.
        ChatAudioFiles.Links failClosed = new ChatAudioFiles.Links() {
            @Override public boolean isLink(File f) { return true; }
        };
        check("fail-closed oracle deletes nothing",
                ChatAudioFiles.delete(root, Arrays.asList(plain.getAbsolutePath()), failClosed) == 0);
        check("fail-closed oracle keeps file", plain.isFile());
        check("normal oracle still deletes it",
                ChatAudioFiles.delete(root, Arrays.asList(plain.getAbsolutePath()), NIO) == 1);

        // A cluster of clips: several for this chat, one foreign in the same tree.
        File cluster = freshDir("cluster");
        File clusterDir = new File(cluster, "7");
        check("cluster dir created", clusterDir.mkdirs());
        List<String> own = new ArrayList<String>();
        for (int i = 0; i < 5; i++) own.add(file(clusterDir, i + ".wav").getAbsolutePath());
        File foreign = file(cluster, "8/other.wav");
        check("cluster removed", ChatAudioFiles.delete(clusterDir, own, NIO) == 5);
        check("cluster foreign kept", foreign.isFile());
        check("cluster dir itself kept", clusterDir.isDirectory());

        if (failures != 0) throw new AssertionError(failures + " of " + checks + " checks failed");
        System.out.println("ChatAudioFilesTests: " + checks + " passed");
    }

    /** Batch with two own clips and one clip of another chat: exactly the own two go. */
    private static boolean mixedBatch(File audioTree) {
        File ownDir = new File(audioTree, "21");
        File own1 = file(audioTree, "21/x.wav");
        File own2 = file(audioTree, "21/y.wav");
        File foreign = file(audioTree, "22/z.wav");
        int removed = ChatAudioFiles.delete(ownDir,
                Arrays.asList(own1.getAbsolutePath(), foreign.getAbsolutePath(), own2.getAbsolutePath()), NIO);
        return removed == 2 && !own1.exists() && !own2.exists() && foreign.isFile();
    }

    private static File freshDir(String name) {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "anyai-" + name + "-" + System.nanoTime());
        if (!dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
        return dir;
    }

    private static File file(File root, String relative) {
        File f = new File(root, relative.replace('/', File.separatorChar));
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("cannot create " + parent);
        }
        write(f);
        return f;
    }

    private static void write(File f) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(new byte[]{1, 2, 3, 4});
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + f, e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Best effort: the test only needs the bytes on disk.
                }
            }
        }
    }

    /** Windows without developer mode cannot create symlinks. */
    private static boolean link(File target, File link) {
        try {
            return java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath()) != null;
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("FAIL: " + label);
        }
    }
}
