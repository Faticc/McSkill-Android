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
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
 *
 * Transfer is pure gRPC streaming (DownloadFiles/DownloadAssetFiles), matching how the real
 * client actually downloads - an earlier version of this class also tried a guessed HTTP fast
 * path off {@code FileTreeResponse.base_url}, but the reference implementation reverse-engineered
 * from mcskill's own repos never uses base_url at all; it only ever calls DownloadFiles. That
 * HTTP path was routinely unreachable in practice and just added multi-second timeouts per file
 * before falling back, which looked like (and partly was) the installer being slow. The requested
 * file list is still split into small sub-batches with per-batch retry (see
 * {@link #GRPC_SUBBATCH_SIZE}) because asking the server for hundreds of files in a single stream
 * is what caused real "INTERNAL: Rst Stream" resets during testing - the reference script's
 * one-giant-request approach doesn't survive that in practice.
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
            File instanceDir = new File(Tools.DIR_GAME_HOME, "custom_instances/mcskill_" + clientId);
            //noinspection ResultOfMethodCallIgnored
            instanceDir.mkdirs();

            progress.onProgress("Checking files...");
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MCSKILL_CLIENT, 0, "Checking client files...");
            String assetsDirName = sanitize(client.getAssetsDir());
            File assetsDir = new File(new File(Tools.DIR_GAME_HOME, "mcskill_assets"), assetsDirName);

            // Run both tree fetches concurrently - each has its own 30s deadline (McSkillUpdater),
            // so doing them one after another could take up to a minute before anything happens.
            ExecutorService treeFetchPool = Executors.newFixedThreadPool(2);
            Future<FileTreeResponse> fileTreeFuture = treeFetchPool.submit(() -> updater.getFileTree(clientId, sessionId));
            Future<FileTreeResponse> assetTreeFuture = treeFetchPool.submit(() -> updater.getAssetFileTree(client.getAssetsDir(), sessionId));
            FileTreeResponse fileTree;
            FileTreeResponse assetTree;
            try {
                fileTree = fileTreeFuture.get();
                assetTree = assetTreeFuture.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new IOException("Failed to fetch file trees", cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while fetching file trees", e);
            } finally {
                treeFetchPool.shutdown();
            }

            // The file tree is everything the client owns - classpath jars (forge.jar,
            // minecraft.jar, libraries/**) *and* runtime content FML discovers on its own by
            // scanning --gameDir (mods/**, config/**, etc.). The reference PC client keeps both
            // under one shared folder that doubles as gameDir, so this distinction doesn't exist
            // for it - but generateLibClasspath() always resolves library paths relative to the
            // single shared Tools.DIR_HOME_LIBRARY root, so classpath jars have to live there
            // instead. Route by client.getClassPathList(): anything it names (or anything under
            // a directory it names, e.g. "libraries") is classpath content and goes to
            // clientLibDir; everything else (crucially mods/**, which is how FML loads
            // lwjgl3ify's own LWJGL2-compat classes - without it, missing classes like
            // org.lwjgl.opengl.OpenGLException crash the launch) goes to instanceDir so it's
            // where --gameDir actually points.
            List<String> classPathEntries = client.getClassPathList();
            List<FileNode> classpathNodes = new ArrayList<>();
            List<FileNode> instanceNodes = new ArrayList<>();
            for (FileNode node : fileTree.getFilesList()) {
                if (node.getIsDirectory()) continue;
                if (isUnderClassPath(node.getPath(), classPathEntries)) classpathNodes.add(node);
                else instanceNodes.add(node);
            }
            List<FileNode> assetNodes = nonDirectoryNodes(assetTree);

            int totalToCheck = classpathNodes.size() + instanceNodes.size() + assetNodes.size();
            AtomicInteger checkedCounter = new AtomicInteger(0);
            List<FileNode> missingClasspathFiles = diff(classpathNodes, clientLibDir, checkedCounter, totalToCheck);
            List<FileNode> missingInstanceFiles = diff(instanceNodes, instanceDir, checkedCounter, totalToCheck);
            List<FileNode> missingAssets = diff(assetNodes, assetsDir, checkedCounter, totalToCheck);

            int total = missingClasspathFiles.size() + missingInstanceFiles.size() + missingAssets.size();
            if (total == 0) {
                ProgressLayout.setProgress(ProgressLayout.INSTALL_MCSKILL_CLIENT, 100, "Already up to date");
            } else {
                long totalBytes = 0;
                for (FileNode n : missingClasspathFiles) totalBytes += n.getSize();
                for (FileNode n : missingInstanceFiles) totalBytes += n.getSize();
                for (FileNode n : missingAssets) totalBytes += n.getSize();

                progress.onProgress("Downloading " + total + " file(s)...");
                // Written immediately (not just on the first completed file) so the progress bar
                // visibly leaves "Checking files: N/N" right away.
                ProgressLayout.setProgress(ProgressLayout.INSTALL_MCSKILL_CLIENT, 0, "0/" + total + " files");
                AtomicInteger doneCounter = new AtomicInteger(0);
                AtomicLong downloadedBytes = new AtomicLong(0);
                long startTime = System.currentTimeMillis();
                List<String> failedPaths = new ArrayList<>();
                if (!missingClasspathFiles.isEmpty()) {
                    failedPaths.addAll(downloadAll(missingClasspathFiles, clientLibDir,
                            (paths) -> updater.downloadFiles(clientId, paths, sessionId),
                            doneCounter, total, downloadedBytes, totalBytes, startTime));
                }
                if (!missingInstanceFiles.isEmpty()) {
                    failedPaths.addAll(downloadAll(missingInstanceFiles, instanceDir,
                            (paths) -> updater.downloadFiles(clientId, paths, sessionId),
                            doneCounter, total, downloadedBytes, totalBytes, startTime));
                }
                if (!missingAssets.isEmpty()) {
                    failedPaths.addAll(downloadAll(missingAssets, assetsDir,
                            (paths) -> updater.downloadAssetFiles(client.getAssetsDir(), paths, sessionId),
                            doneCounter, total, downloadedBytes, totalBytes, startTime));
                }
                // A handful of stubborn files (an empty per-player config, a stale server-side
                // listing, etc.) shouldn't block installing the other 99%+ that did download
                // fine - report them and keep going rather than aborting the whole install.
                if (!failedPaths.isEmpty()) {
                    Log.w("McSkillInstaller", failedPaths.size() + " file(s) could not be downloaded after "
                            + GRPC_MAX_RETRIES + " attempts each: " + failedPaths);
                    progress.onProgress("Warning: " + failedPaths.size() + " file(s) could not be downloaded "
                            + "(e.g. \"" + failedPaths.get(0) + "\") - continuing anyway.");
                }
            }

            // See stripLegacyBinPatches()/ForgeBinPatchApplier/disableLinuxDesktopEntry() - all
            // always run, not just after a fresh download, since diff() skips re-downloading a
            // file that's already present from an earlier install.
            stripLegacyBinPatches(clientLibDir);
            ForgeBinPatchApplier.apply(clientLibDir);
            disableLinuxDesktopEntry(instanceDir);

            progress.onProgress("Building launch profile...");
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MCSKILL_CLIENT, 100, "Building launch profile...");
            String versionId = "mcskill_" + clientId + "_" + safeVersion;
            writeVersionJson(client, versionId, clientLibDir, assetsDir, libRoot);

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

    private interface GrpcChunkSource {
        Iterator<FileChunk> open(List<String> paths);
    }

    /** How many DownloadFiles/DownloadAssetFiles streams to run at once. */
    private static final int GRPC_PARALLEL_DOWNLOADS = 6;
    /**
     * Files per single DownloadFiles/DownloadAssetFiles request. A request carrying hundreds of
     * files in one long-lived stream is both slow to show progress and fragile - the server (or
     * a proxy in front of it) resets long/large streams with an "INTERNAL: Rst Stream" error,
     * which used to take the whole sub-batch's progress down with it. Small requests fail cheap
     * and retry cheap.
     */
    private static final int GRPC_SUBBATCH_SIZE = 40;
    private static final int GRPC_MAX_RETRIES = 4;

    /**
     * How many files to hash-check at once. Hashing is CPU-bound (unlike the network-bound
     * download pools), so this doesn't need to be as conservative - but a previous partial
     * download can leave hundreds of already-good files on disk, each needing a full SHA read,
     * so this still runs on a worker pool instead of the caller's thread.
     */
    private static final int DIFF_PARALLELISM = 4;

    private static List<FileNode> nonDirectoryNodes(FileTreeResponse tree) {
        List<FileNode> nodes = new ArrayList<>();
        for (FileNode node : tree.getFilesList()) {
            if (!node.getIsDirectory()) nodes.add(node);
        }
        return nodes;
    }

    /** @return true if {@code path} is (or is inside) one of the classpath entries mcskill listed. */
    private static boolean isUnderClassPath(String path, List<String> classPathEntries) {
        for (String entry : classPathEntries) {
            if (path.equals(entry) || path.startsWith(entry + "/")) return true;
        }
        return false;
    }

    /**
     * @return nodes missing locally or not matching the server's size/hash. Runs in parallel and
     * reports "Checking files: X/Y" progress as it goes - re-hashing hundreds of already-downloaded
     * files (e.g. after a partial install) on a single thread with no progress update is what used
     * to look like a hang at "Checking files...".
     */
    private static List<FileNode> diff(List<FileNode> allFiles, File destDir, AtomicInteger checkedCounter, int totalToCheck) {
        //noinspection ResultOfMethodCallIgnored
        destDir.mkdirs();
        if (allFiles.isEmpty()) return new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(DIFF_PARALLELISM, allFiles.size()));
        List<Future<FileNode>> futures = new ArrayList<>();
        try {
            for (FileNode node : allFiles) {
                futures.add(pool.submit(() -> {
                    File local = new File(destDir, node.getPath());
                    boolean isMissing = !local.exists() || local.length() != node.getSize()
                            || !matchesHash(local, node.getHash().toByteArray());
                    int checked = checkedCounter.incrementAndGet();
                    ProgressLayout.setProgress(ProgressLayout.INSTALL_MCSKILL_CLIENT,
                            totalToCheck > 0 ? (int) ((checked * 100L) / totalToCheck) : 0,
                            "Checking files: " + checked + "/" + totalToCheck);
                    return isMissing ? node : null;
                }));
            }
            List<FileNode> missing = new ArrayList<>();
            for (Future<FileNode> future : futures) {
                try {
                    FileNode node = future.get();
                    if (node != null) missing.add(node);
                } catch (Exception e) {
                    Log.w("McSkillInstaller", "Diff check task failed unexpectedly", e);
                }
            }
            return missing;
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Downloads {@code nodes} into {@code destDir} over gRPC, in small retryable sub-batches (see
     * {@link #GRPC_SUBBATCH_SIZE}). {@code doneCounter}/{@code total} and {@code downloadedBytes}/
     * {@code totalBytes} are shared across both the file and asset phases so the progress bar
     * reads as one continuous run - including one continuous speed/ETA estimate - instead of
     * resetting partway through.
     *
     * @return paths that still failed after all retries (the caller decides whether that's fatal).
     */
    private static List<String> downloadAll(List<FileNode> nodes, File destDir, GrpcChunkSource grpcSource,
                                             AtomicInteger doneCounter, int total,
                                             AtomicLong downloadedBytes, long totalBytes, long startTime) throws IOException {
        List<String> paths = new ArrayList<>(nodes.size());
        for (FileNode node : nodes) paths.add(node.getPath());
        return downloadAllViaGrpc(paths, destDir, grpcSource, doneCounter, total, downloadedBytes, totalBytes, startTime);
    }

    /**
     * Downloads {@code paths} into {@code destDir} in small sub-batches (see
     * {@link #GRPC_SUBBATCH_SIZE}), up to {@link #GRPC_PARALLEL_DOWNLOADS} of them in flight at
     * once via a bounded worker pool. gRPC multiplexes streams over the same HTTP/2 connection,
     * so this is real parallelism without opening extra sockets. Each sub-batch retries on its
     * own ({@link #GRPC_MAX_RETRIES} attempts, only re-requesting whatever didn't finish) rather
     * than one failed stream aborting everything else that was still downloading fine.
     *
     * @return paths that still failed after all retries.
     */
    private static List<String> downloadAllViaGrpc(List<String> paths, File destDir, GrpcChunkSource source,
                                                     AtomicInteger doneCounter, int total,
                                                     AtomicLong downloadedBytes, long totalBytes, long startTime) throws IOException {
        List<List<String>> subBatches = chunk(paths, GRPC_SUBBATCH_SIZE);
        int parallelism = Math.min(GRPC_PARALLEL_DOWNLOADS, subBatches.size());

        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        List<Future<List<String>>> futures = new ArrayList<>();
        try {
            for (List<String> subBatch : subBatches) {
                futures.add(pool.submit(() -> downloadSubBatchWithRetry(source, subBatch, destDir,
                        doneCounter, total, downloadedBytes, totalBytes, startTime)));
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
            return stillFailed;
        } finally {
            pool.shutdown();
        }
    }

    /** @return whichever of {@code paths} still didn't complete after retrying. */
    private static List<String> downloadSubBatchWithRetry(GrpcChunkSource source, List<String> paths, File destDir,
                                                            AtomicInteger doneCounter, int total,
                                                            AtomicLong downloadedBytes, long totalBytes, long startTime) {
        List<String> remaining = new ArrayList<>(paths);
        for (int attempt = 1; attempt <= GRPC_MAX_RETRIES && !remaining.isEmpty(); attempt++) {
            if (attempt > 1) {
                try {
                    Thread.sleep(Math.min(500L << (attempt - 1), 5000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return remaining;
                }
            }

            List<String> justCompleted = new ArrayList<>();
            // Chunks for the same file arrive consecutively - keep one FileOutputStream open
            // across them instead of reopening per chunk. Re-opening (and closing, which flushes
            // to storage) for every single chunk was cheap on the reference PC implementation
            // this was ported from, but on Android's storage that per-chunk open/close overhead
            // was enough by itself to bottleneck throughput to a few hundred KB/s.
            String openPath = null;
            FileOutputStream openStream = null;
            try {
                Iterator<FileChunk> chunks = source.open(remaining);
                while (chunks.hasNext()) {
                    FileChunk chunk = chunks.next();
                    if (!chunk.getPath().equals(openPath)) {
                        if (openStream != null) openStream.close();
                        File target = new File(destDir, chunk.getPath());
                        File parent = target.getParentFile();
                        if (parent != null) //noinspection ResultOfMethodCallIgnored
                            parent.mkdirs();
                        // Always start fresh: a file only ends up here because diff() found it
                        // missing or mismatched, so any bytes already on disk are stale/partial -
                        // the server resends the whole file from its first chunk either way.
                        openStream = new FileOutputStream(target, false);
                        openPath = chunk.getPath();
                    }
                    chunk.getData().writeTo(openStream);
                    downloadedBytes.addAndGet(chunk.getData().size());
                    if (chunk.getIsLast()) {
                        openStream.close();
                        openStream = null;
                        openPath = null;
                        justCompleted.add(chunk.getPath());
                        int done = doneCounter.incrementAndGet();
                        reportDownloadProgress(done, total, downloadedBytes.get(), totalBytes, startTime);
                    }
                }
            } catch (Exception e) {
                // Stream reset, network hiccup, etc. - whatever completed before the failure is
                // still in justCompleted, so only what's left over actually gets retried below.
                Log.w("McSkillInstaller", "Attempt " + attempt + "/" + GRPC_MAX_RETRIES + " failed with "
                        + remaining.size() + " file(s) left in this batch: " + e);
            } finally {
                if (openStream != null) {
                    try {
                        openStream.close();
                    } catch (IOException ignored) {
                        // Best-effort - the file is incomplete either way and will be retried.
                    }
                }
            }
            remaining.removeAll(justCompleted);
        }
        return remaining;
    }

    private static void reportDownloadProgress(int done, int total, long downloadedBytes, long totalBytes, long startTime) {
        double elapsedSeconds = Math.max(1, System.currentTimeMillis() - startTime) / 1000.0;
        double bytesPerSecond = downloadedBytes / elapsedSeconds;
        StringBuilder message = new StringBuilder();
        message.append(done).append('/').append(total).append(" files, ").append(formatSpeed(bytesPerSecond));
        if (bytesPerSecond > 0 && totalBytes > downloadedBytes) {
            long etaSeconds = (long) ((totalBytes - downloadedBytes) / bytesPerSecond);
            message.append(", ETA ").append(formatDuration(etaSeconds));
        }
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MCSKILL_CLIENT,
                (int) ((done * 100L) / total), message.toString());
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 1024) return String.format(Locale.US, "%.0f B/s", bytesPerSecond);
        if (bytesPerSecond < 1024 * 1024) return String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024);
        return String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024 * 1024));
    }

    private static String formatDuration(long totalSeconds) {
        if (totalSeconds < 60) return totalSeconds + "s";
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes < 60) return minutes + "m " + seconds + "s";
        long hours = minutes / 60;
        minutes %= 60;
        return hours + "h " + minutes + "m";
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

    private static final String FORGE_JAR_NAME = "forge.jar";
    private static final String LEGACY_BINPATCHES_ENTRY = "binpatches.pack.lzma";

    /**
     * Legacy 1.7.10 FML's {@code ClassPatchManager.setup()} unconditionally calls
     * {@code java.util.jar.Pack200.newUnpacker()} to decode this exact zip entry if it's present
     * in forge.jar - and {@code Pack200} was removed from the JDK entirely in Java 14 (JEP 367),
     * so on any modern JRE that throws {@code NoClassDefFoundError} and takes down the whole
     * launch. Stripping the entry here forces {@code ClassPatchManager.setup()} down its safe
     * "binary patch set is missing" no-op path on every JVM, avoiding Pack200 entirely.
     *
     * This entry is NOT redundant with RFB/lwjgl3ify's own ASM transformers, despite what an
     * earlier version of this comment assumed: it carries ~1060 official Forge patches to vanilla
     * classes (GuiScreen, Entity, etc. all had methods missing that mods expect to already exist),
     * and stripping it drops every one of them. See {@link ForgeBinPatchApplier}, which restores
     * them from a pre-extracted bundle right after this method runs.
     */
    private static void stripLegacyBinPatches(File clientLibDir) {
        File forgeJar = new File(clientLibDir, FORGE_JAR_NAME);
        if (!forgeJar.isFile()) return;
        File tmp = new File(clientLibDir, FORGE_JAR_NAME + ".tmp");
        boolean found = false;
        try (ZipInputStream zin = new ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(forgeJar)));
             ZipOutputStream zout = new ZipOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(tmp)))) {
            byte[] buffer = new byte[65536];
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.getName().equals(LEGACY_BINPATCHES_ENTRY)) {
                    found = true;
                    continue;
                }
                zout.putNextEntry(new ZipEntry(entry.getName()));
                int read;
                while ((read = zin.read(buffer)) != -1) zout.write(buffer, 0, read);
                zout.closeEntry();
            }
        } catch (IOException e) {
            Log.w("McSkillInstaller", "Could not inspect/strip " + FORGE_JAR_NAME + " for legacy binpatches", e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!found) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(forgeJar)) {
            Log.w("McSkillInstaller", "Could not replace " + FORGE_JAR_NAME + " after stripping legacy binpatches");
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /**
     * On first run lwjgl3ify's {@code org.lwjglx.Sys.createLinuxDesktopEntry()} tries to register a
     * {@code .desktop} file under {@code $XDG_DATA_HOME}/{@code $HOME/.local/share} - a real Linux
     * desktop environment integration feature that mcskill's own {@code config/lwjgl3ify.cfg} ships
     * enabled ({@code B:linuxCreateAppDesktopEntry=true}) since the reference client only ever runs
     * on Windows/a real Linux desktop, where it's either a no-op or actually useful. On Android,
     * {@code os.name} still reports "Linux" (same kernel), so the check runs anyway, finds neither
     * path, and throws - taking the whole launch down before the game even starts. Force the option
     * off in the downloaded config instead of trying to fake a valid XDG directory.
     */
    private static void disableLinuxDesktopEntry(File instanceDir) {
        File cfg = new File(instanceDir, "config/lwjgl3ify.cfg");
        if (!cfg.isFile()) return;
        try {
            String content = Tools.read(cfg);
            String patched = content.replace(
                    "B:linuxCreateAppDesktopEntry=true", "B:linuxCreateAppDesktopEntry=false");
            if (!patched.equals(content)) Tools.write(cfg.getAbsolutePath(), patched);
        } catch (IOException e) {
            Log.w("McSkillInstaller", "Could not patch lwjgl3ify.cfg to disable linuxCreateAppDesktopEntry", e);
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
        // NewJREUtil.installNewJreIfNeeded() calls .equalsIgnoreCase() on this with no null check -
        // it only ever compares against the literal "jre-legacy", so any other non-null value here
        // is fine; it doesn't need to match vanilla's real component naming.
        version.javaVersion.component = "jre-" + version.javaVersion.majorVersion;

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
                // net.minecraft.client.main.Main's jopt-simple option spec marks this required (a
                // legacy leftover from the old Mojang launcher's user-properties feature) even
                // though nothing downstream reads it - omitting it throws
                // MissingRequiredOptionException before the game even starts.
                .append(" --userProperties {}")
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
    // Matches the LWJGL *core* jar only ("lwjgl-3.3.1.jar", "lwjgl-2.9.4-nightly-20150209.jar") -
    // not lwjgl_util, lwjgl-platform, lwjgl-glfw, lwjgl-opengl, natives, etc. (those all have a
    // non-digit right after "lwjgl-"/"lwjgl_"). The captured group is passed straight through as
    // the maven version suffix, same as a real version.json would have it.
    private static final Pattern LWJGL_CORE_JAR = Pattern.compile("(?i)^lwjgl-(\\d[\\d.]*\\S*)\\.jar$");

    private static void addLibrary(List<DependentLibrary> out, String pathUnderLibraryRoot) {
        DependentLibrary library = new DependentLibrary();
        String fileName = pathUnderLibraryRoot.substring(pathUnderLibraryRoot.lastIndexOf('/') + 1);
        Matcher lwjglMatch = LWJGL_CORE_JAR.matcher(fileName);
        if (lwjglMatch.matches()) {
            // Tools.generateLibClasspath scans every library's maven `name` for "org.lwjgl.lwjgl:
            // lwjgl:"/"org.lwjgl:lwjgl:" to figure out which LWJGL major version the game actually
            // needs - it picks the bundled native LWJGL 3.3.3/3.4.1 build accordingly and decides
            // whether the lwjglx compat shim is needed for a real LWJGL2 game. Without a library
            // name matching that pattern it throws "Unable to determine LWJGL version". This is
            // the one library whose name has to be a truthful maven coordinate; every other jar's
            // name below is never resolved (downloads.artifact.path handles that), so it just has
            // to be non-null and roughly maven-shaped.
            library.name = "org.lwjgl.lwjgl:lwjgl:" + lwjglMatch.group(1);
        } else {
            library.name = "mcskill:" + pathUnderLibraryRoot.replace('/', '_').replace(':', '_') + ":1.0";
        }
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
