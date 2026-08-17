package net.kdt.pojavlaunch.mcskill.install;

import android.util.Log;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.DependentLibrary;
import net.kdt.pojavlaunch.value.MinecraftLibraryArtifact;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;
import net.mcsgroup.launcher.client.McSkillChannel;
import net.mcsgroup.launcher.client.McSkillClients;
import net.mcsgroup.launcher.client.McSkillException;
import net.mcsgroup.launcher.client.McSkillUpdater;
import net.mcsgroup.launcher.proto.ClientProfile;
import net.mcsgroup.launcher.proto.FileChunk;
import net.mcsgroup.launcher.proto.FileNode;
import net.mcsgroup.launcher.proto.FileTreeResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downloads an mcskill "client" bundle (files + assets) and registers it as a normal,
 * launchable {@link MinecraftProfile}, reusing Amethyst's existing version-JSON-driven
 * launch pipeline rather than inventing a second one.
 *
 * Progress is reported through the same {@code ProgressLayout}/{@code ProgressKeeper} bar the
 * vanilla downloader and modpack installer use ({@link ProgressLayout#INSTALL_MCSKILL_CLIENT}),
 * not an ad hoc log - callers only need coarse milestone text (see {@link ProgressCallback}).
 *
 * Library jars are placed under {@code Tools.DIR_HOME_LIBRARY/mcskill/<clientId>/...} because
 * {@code Tools.generateLibClasspath} always resolves a library's path relative to that single
 * shared root - there is no per-profile override. Assets have no such constraint (they're only
 * ever referenced via a literal {@code --assetsDir} argument), so they go under a dedicated
 * mcskill-only folder instead of Amethyst's shared vanilla assets root.
 */
public class McSkillClientInstaller {

    public interface ProgressCallback {
        /** Coarse milestones only (fetching profile, done, failed...) - not per-file spam. */
        void onProgress(String message);
    }

    /** @return the profile key it was registered under (for {@code ExtraConstants.REFRESH_VERSION_SPINNER}). */
    public static String install(int clientId, String sessionId, ProgressCallback progress) throws IOException {
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MCSKILL_CLIENT, 0, "Fetching client profile...");
        McSkillChannel channel = McSkillChannel.createDefault();
        try {
            progress.onProgress("Fetching client profile...");
            ClientProfile client = new McSkillClients(channel.clientsStub()).getClient(clientId, sessionId);
            McSkillUpdater updater = new McSkillUpdater(channel.updateStub());

            String safeVersion = sanitize(client.getVersion());
            String libRoot = "mcskill/" + clientId;
            File clientLibDir = new File(Tools.DIR_HOME_LIBRARY, libRoot);

            progress.onProgress("Checking files...");
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MCSKILL_CLIENT, 0, "Checking files...");
            FileTreeResponse fileTree = updater.getFileTree(clientId, sessionId);
            List<String> missingFiles = diff(fileTree, clientLibDir);

            String assetsDirName = sanitize(client.getAssetsDir());
            File assetsDir = new File(new File(Tools.DIR_GAME_HOME, "mcskill_assets"), assetsDirName);
            FileTreeResponse assetTree = updater.getAssetFileTree(client.getAssetsDir(), sessionId);
            List<String> missingAssets = diff(assetTree, assetsDir);

            int total = missingFiles.size() + missingAssets.size();
            if (total == 0) {
                ProgressLayout.setProgress(ProgressLayout.INSTALL_MCSKILL_CLIENT, 100, "Already up to date");
            } else {
                progress.onProgress("Downloading " + total + " file(s)...");
                AtomicInteger doneCounter = new AtomicInteger(0);
                if (!missingFiles.isEmpty()) {
                    downloadAll(missingFiles, clientLibDir, (paths) -> updater.downloadFiles(clientId, paths, sessionId), doneCounter, total);
                }
                if (!missingAssets.isEmpty()) {
                    downloadAll(missingAssets, assetsDir, (paths) -> updater.downloadAssetFiles(client.getAssetsDir(), paths, sessionId), doneCounter, total);
                }
            }

            progress.onProgress("Building launch profile...");
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MCSKILL_CLIENT, 100, "Building launch profile...");
            String versionId = "mcskill_" + clientId + "_" + safeVersion;
            writeVersionJson(client, versionId, clientLibDir, assetsDir, libRoot);

            File instanceDir = new File(Tools.DIR_GAME_HOME, "custom_instances/mcskill_" + clientId);
            //noinspection ResultOfMethodCallIgnored
            instanceDir.mkdirs();

            MinecraftProfile profileEntry = new MinecraftProfile();
            // ClientProfile (the per-client launch detail message) carries no display title -
            // that only exists on ClientInfo, the GetClients() list-summary message.
            profileEntry.name = "mcskill #" + clientId;
            profileEntry.gameDir = "./custom_instances/mcskill_" + clientId;
            profileEntry.lastVersionId = versionId;
            if (LauncherProfiles.mainProfileJson == null) LauncherProfiles.load();
            String profileKey = LauncherProfiles.getFreeProfileKey();
            LauncherProfiles.mainProfileJson.profiles.put(profileKey, profileEntry);
            LauncherProfiles.write();

            progress.onProgress("Done.");
            return profileKey;
        } finally {
            channel.shutdown();
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MCSKILL_CLIENT);
        }
    }

    private interface ChunkSource {
        Iterator<FileChunk> open(List<String> paths);
    }

    /** How many DownloadFiles/DownloadAssetFiles streams to run at once (see class doc). */
    private static final int PARALLEL_DOWNLOADS = 4;
    /**
     * Files per single DownloadFiles/DownloadAssetFiles request. A request carrying hundreds of
     * files in one long-lived stream is both slow to show progress and fragile - the server (or
     * a proxy in front of it) resets long/large streams with an "INTERNAL: Rst Stream" error,
     * which used to take the whole sub-batch's progress down with it. Small requests fail cheap
     * and retry cheap.
     */
    private static final int SUBBATCH_SIZE = 40;
    private static final int MAX_RETRIES = 4;

    /** @return paths (relative to destDir) that are missing or don't match the server's size/hash. */
    private static List<String> diff(FileTreeResponse tree, File destDir) {
        //noinspection ResultOfMethodCallIgnored
        destDir.mkdirs();
        List<String> missing = new ArrayList<>();
        for (FileNode node : tree.getFilesList()) {
            if (node.getIsDirectory()) continue;
            File local = new File(destDir, node.getPath());
            if (!local.exists() || local.length() != node.getSize() || !matchesHash(local, node.getHash().toByteArray())) {
                missing.add(node.getPath());
            }
        }
        return missing;
    }

    /**
     * Downloads {@code paths} into {@code destDir} in small sub-batches (see
     * {@link #SUBBATCH_SIZE}), up to {@link #PARALLEL_DOWNLOADS} of them in flight at once via a
     * bounded worker pool. gRPC multiplexes streams over the same HTTP/2 connection, so this is
     * real parallelism without opening extra sockets. Each sub-batch retries on its own
     * ({@link #MAX_RETRIES} attempts, only re-requesting whatever didn't finish) rather than one
     * failed stream aborting everything else that was still downloading fine.
     * {@code doneCounter}/{@code total} are shared across both the file and asset phases so the
     * progress bar reads as one continuous 0-100% run instead of resetting partway through.
     */
    private static void downloadAll(List<String> paths, File destDir, ChunkSource source,
                                     AtomicInteger doneCounter, int total) throws IOException {
        List<List<String>> subBatches = chunk(paths, SUBBATCH_SIZE);
        int parallelism = Math.min(PARALLEL_DOWNLOADS, subBatches.size());

        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        List<Future<List<String>>> futures = new ArrayList<>();
        try {
            for (List<String> subBatch : subBatches) {
                futures.add(pool.submit(() -> downloadSubBatchWithRetry(source, subBatch, destDir, doneCounter, total)));
            }
            List<String> stillFailed = new ArrayList<>();
            for (Future<List<String>> future : futures) {
                try {
                    stillFailed.addAll(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while downloading files", e);
                } catch (ExecutionException e) {
                    // downloadSubBatchWithRetry only throws on interruption (rethrown above) -
                    // any other failure is caught internally and reported via the returned list.
                    Log.e("McSkillInstaller", "Unexpected sub-batch failure", e.getCause());
                }
            }
            if (!stillFailed.isEmpty()) {
                throw new IOException(stillFailed.size() + " file(s) failed after " + MAX_RETRIES
                        + " attempts each, e.g. \"" + stillFailed.get(0) + "\"");
            }
        } finally {
            pool.shutdown();
        }
    }

    /** @return whichever of {@code paths} still didn't complete after retrying. */
    private static List<String> downloadSubBatchWithRetry(ChunkSource source, List<String> paths, File destDir,
                                                            AtomicInteger doneCounter, int total) {
        List<String> remaining = new ArrayList<>(paths);
        for (int attempt = 1; attempt <= MAX_RETRIES && !remaining.isEmpty(); attempt++) {
            if (attempt > 1) {
                try {
                    Thread.sleep(Math.min(500L << (attempt - 1), 5000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return remaining;
                }
            }

            List<String> justCompleted = new ArrayList<>();
            try {
                Iterator<FileChunk> chunks = source.open(remaining);
                while (chunks.hasNext()) {
                    FileChunk chunk = chunks.next();
                    File target = new File(destDir, chunk.getPath());
                    File parent = target.getParentFile();
                    if (parent != null) //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    try (FileOutputStream out = new FileOutputStream(target, target.exists())) {
                        chunk.getData().writeTo(out);
                    }
                    if (chunk.getIsLast()) {
                        justCompleted.add(chunk.getPath());
                        int done = doneCounter.incrementAndGet();
                        ProgressLayout.setProgress(ProgressLayout.INSTALL_MCSKILL_CLIENT,
                                (int) ((done * 100L) / total), done + "/" + total + " files");
                    }
                }
            } catch (Exception e) {
                // Stream reset, network hiccup, etc. - whatever completed before the failure is
                // still in justCompleted, so only what's left over actually gets retried below.
                Log.w("McSkillInstaller", "Attempt " + attempt + "/" + MAX_RETRIES + " failed with "
                        + remaining.size() + " file(s) left in this batch: " + e);
            }
            remaining.removeAll(justCompleted);
        }
        return remaining;
    }

    /** Splits items into consecutive groups of at most {@code size}. */
    private static <T> List<List<T>> chunk(List<T> items, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            result.add(new ArrayList<>(items.subList(i, Math.min(i + size, items.size()))));
        }
        return result;
    }

    private static boolean matchesHash(File file, byte[] expected) {
        if (expected == null || expected.length == 0) return true; // Nothing to check against.
        String algorithm;
        if (expected.length == 20) algorithm = "SHA-1";
        else if (expected.length == 32) algorithm = "SHA-256";
        else return true; // Unknown hash shape - fall back to the size check already done by the caller.

        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            byte[] actual = digest.digest();
            if (actual.length != expected.length) return false;
            for (int i = 0; i < actual.length; i++) if (actual[i] != expected[i]) return false;
            return true;
        } catch (IOException | NoSuchAlgorithmException e) {
            Log.w("McSkillInstaller", "Could not hash " + file + ", forcing re-download", e);
            return false;
        }
    }

    /** Replaces mcskill's own "${cp_separator}" token (not one Amethyst's arg substitution knows about). */
    private static String fixSeparator(String arg) {
        return arg.replace("${cp_separator}", File.pathSeparator);
    }

    private static void writeVersionJson(ClientProfile client, String versionId, File clientLibDir, File assetsDir, String libRootPrefix) throws IOException {
        JMinecraftVersionList.Version version = new JMinecraftVersionList.Version();
        version.id = versionId;
        version.type = "release";
        version.mainClass = client.getMainClass();
        version.javaVersion = new JMinecraftVersionList.JavaVersionInfo();
        version.javaVersion.majorVersion = parseJavaMajorVersion(client.getJavaVersion());

        // Every classpath entry mcskill lists is a real file already placed under clientLibDir by
        // downloadAll(); walk it exactly like the reference client does, since a class_path entry
        // can itself be a directory ("include every jar under this folder").
        List<DependentLibrary> libraries = new ArrayList<>();
        for (String entry : client.getClassPathList()) {
            File asFile = new File(clientLibDir, entry);
            if (asFile.isDirectory()) {
                collectJars(asFile, clientLibDir, libRootPrefix, libraries);
            } else {
                addLibrary(libraries, libRootPrefix + "/" + entry);
            }
        }
        version.libraries = libraries.toArray(new DependentLibrary[0]);

        List<String> jvmArgs = new ArrayList<>();
        for (String arg : client.getJvmArgsList()) jvmArgs.add(fixSeparator(arg));

        StringBuilder mcArgs = new StringBuilder();
        mcArgs.append("--username ${auth_player_name}")
                .append(" --uuid ${auth_uuid}")
                .append(" --accessToken ${auth_access_token}")
                .append(" --userType ${user_type}")
                .append(" --version ${version_name}")
                .append(" --gameDir ${game_directory}")
                .append(" --assetsDir ").append(assetsDir.getAbsolutePath())
                .append(" --assetIndex ").append(client.getAssetIndex())
                .append(" --resourcePackDir ${game_directory}/resourcepacks");
        for (String arg : client.getClientArgsList()) {
            mcArgs.append(' ').append(fixSeparator(arg));
        }
        version.minecraftArguments = mcArgs.toString();

        // getMinecraftJVMArgs only reads versionInfo.arguments.jvm (String entries), and already
        // strips anything classpath/library-path related since generateLaunchClasspath builds those
        // itself - see Tools.java's getMinecraftJVMArgs.
        version.arguments = new JMinecraftVersionList.Arguments();
        version.arguments.jvm = jvmArgs.toArray();
        version.arguments.game = new Object[0];

        File versionDir = new File(Tools.DIR_HOME_VERSION, versionId);
        //noinspection ResultOfMethodCallIgnored
        versionDir.mkdirs();
        Tools.write(new File(versionDir, versionId + ".json").getAbsolutePath(), Tools.GLOBAL_GSON.toJson(version));
    }

    private static void collectJars(File dir, File libRoot, String libRootPrefix, List<DependentLibrary> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectJars(child, libRoot, libRootPrefix, out);
            } else if (child.getName().endsWith(".jar")) {
                String relative = libRoot.toPath().relativize(child.toPath()).toString().replace(File.separatorChar, '/');
                addLibrary(out, libRootPrefix + "/" + relative);
            }
        }
    }

    /**
     * @param pathUnderLibraryRoot path relative to {@code Tools.DIR_HOME_LIBRARY} (already
     *                             prefixed with the client's own "mcskill/&lt;clientId&gt;" root).
     */
    private static void addLibrary(List<DependentLibrary> out, String pathUnderLibraryRoot) {
        DependentLibrary library = new DependentLibrary();
        // Never actually resolved: downloads.artifact.path is set below, so Tools.artifactToPath()
        // never falls back to parsing this - it just has to be non-null and roughly maven-shaped.
        library.name = "mcskill:" + pathUnderLibraryRoot.replace('/', '_').replace(':', '_') + ":1.0";
        MinecraftLibraryArtifact artifact = new MinecraftLibraryArtifact();
        artifact.path = pathUnderLibraryRoot;
        library.downloads = new DependentLibrary.LibraryDownloads(artifact);
        out.add(library);
    }

    private static int parseJavaMajorVersion(String javaVersion) {
        if (javaVersion == null || javaVersion.isEmpty()) return 17;
        String digits = javaVersion.replaceAll("[^0-9].*", "");
        if (digits.isEmpty()) return 17;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 17;
        }
    }

    private static String sanitize(String raw) {
        if (raw == null) return "unknown";
        return raw.trim().replaceAll("[\\\\/:*?\"<>| \\t\\n]", "_");
    }
}
