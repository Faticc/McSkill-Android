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
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *
 * Bulk transfer prefers plain HTTPS from {@code FileTreeResponse.base_url} when the server
 * provides one - the proto's own naming (base_url/node_id, plus a whole GetFallbackNode RPC)
 * strongly suggests the gRPC DownloadFiles/DownloadAssetFiles streaming RPCs are themselves the
 * *fallback* path, not the primary one; a plain HTTP GET per file scales with ordinary connection
 * pooling and isn't subject to a single shared stream getting reset. Whatever HTTP can't fetch
 * (no base_url, a 404, a transient error) falls back to the gRPC streaming path.
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
            int totalToCheck = countFiles(fileTree) + countFiles(assetTree);
            AtomicInteger checkedCounter = new AtomicInteger(0);
            List<FileNode> missingFiles = diff(fileTree, clientLibDir, checkedCounter, totalToCheck);
            List<FileNode> missingAssets = diff(assetTree, assetsDir, checkedCounter, totalToCheck);

            int total = missingFiles.size() + missingAssets.size();
            if (total == 0) {
                ProgressLayout.setProgress(ProgressLayout.INSTALL_MCSKILL_CLIENT, 100, "Already up to date");
            } else {
                progress.onProgress("Downloading " + total + " file(s)...");
                // Written immediately (not just on the first completed file) so the progress bar
                // visibly leaves "Checking files: N/N" right away - otherwise, if every file
                // happens to fail its first few attempts, the bar looks frozen even though work
                // is actually happening.
                ProgressLayout.setProgress(ProgressLayout.INSTALL_MCSKILL_CLIENT, 0, "0/" + total + " files");
                AtomicInteger doneCounter = new AtomicInteger(0);
                if (!missingFiles.isEmpty()) {
                    downloadAll(missingFiles, clientLibDir, fileTree.getBaseUrl(),
                            (paths) -> updater.downloadFiles(clientId, paths, sessionId), doneCounter, total);
                }
                if (!missingAssets.isEmpty()) {
                    downloadAll(missingAssets, assetsDir, assetTree.getBaseUrl(),
                            (paths) -> updater.downloadAssetFiles(client.getAssetsDir(), paths, sessionId), doneCounter, total);
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

    private interface GrpcChunkSource {
        Iterator<FileChunk> open(List<String> paths);
    }

    // --- HTTP fast path -----------------------------------------------------------------------

    // This app runs with a small default Dalvik heap (no largeHeap), alongside a lot of other
    // native/JNI machinery already resident - 10 concurrent HttpURLConnections (TLS session
    // state, internal buffering, etc.) was enough to OOM on a real device. Keep this modest.
    private static final int HTTP_PARALLEL_DOWNLOADS = 3;
    private static final int HTTP_MAX_RETRIES = 3;
    private static final int HTTP_CONNECT_TIMEOUT_MS = 10_000;
    private static final int HTTP_READ_TIMEOUT_MS = 30_000;
    private static final int HTTP_COPY_BUFFER_SIZE = 16_384;
    /**
     * If the HTTP fast path is systemically broken (bad base_url, CDN unreachable, etc.), every
     * one of hundreds/thousands of files would otherwise burn its full {@link #HTTP_MAX_RETRIES}
     * attempts one by one before falling back to gRPC - with zero successful downloads to report,
     * that looks exactly like a hang (progress bar frozen at the last "Checking files" text) even
     * though {@link #HTTP_PARALLEL_DOWNLOADS} threads are churning through timeouts in the
     * background. Once this many raw attempts have failed in a row with not a single success,
     * stop trying HTTP for whatever's left and let gRPC take it immediately.
     */
    private static final int HTTP_CIRCUIT_BREAKER_FAILURES = 6;

    // --- gRPC fallback path (see class doc) ----------------------------------------------------

    /** How many DownloadFiles/DownloadAssetFiles streams to run at once. */
    private static final int GRPC_PARALLEL_DOWNLOADS = 4;
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

    private static int countFiles(FileTreeResponse tree) {
        int count = 0;
        for (FileNode node : tree.getFilesList()) if (!node.getIsDirectory()) count++;
        return count;
    }

    /**
     * @return nodes (files, not directories) missing locally or not matching the server's
     * size/hash. Runs in parallel and reports "Checking files: X/Y" progress as it goes -
     * re-hashing hundreds of already-downloaded files (e.g. after a partial install) on a single
     * thread with no progress update is what used to look like a hang at "Checking files...".
     */
    private static List<FileNode> diff(FileTreeResponse tree, File destDir, AtomicInteger checkedCounter, int totalToCheck) {
        //noinspection ResultOfMethodCallIgnored
        destDir.mkdirs();
        List<FileNode> allFiles = new ArrayList<>();
        for (FileNode node : tree.getFilesList()) {
            if (!node.getIsDirectory()) allFiles.add(node);
        }
        if (allFiles.isEmpty()) return allFiles;

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
     * Downloads {@code nodes} into {@code destDir}: HTTP first (if {@code baseUrl} is non-empty),
     * anything HTTP couldn't fetch falls back to the gRPC streaming path. {@code doneCounter}/
     * {@code total} are shared across both the file and asset phases so the progress bar reads as
     * one continuous 0-100% run instead of resetting partway through.
     */
    private static void downloadAll(List<FileNode> nodes, File destDir, String baseUrl, GrpcChunkSource grpcSource,
                                     AtomicInteger doneCounter, int total) throws IOException {
        List<FileNode> remaining = nodes;
        if (baseUrl != null && !baseUrl.isEmpty()) {
            remaining = downloadAllViaHttp(nodes, destDir, baseUrl, doneCounter, total);
        }
        if (remaining.isEmpty()) return;

        List<String> paths = new ArrayList<>(remaining.size());
        for (FileNode node : remaining) paths.add(node.getPath());
        downloadAllViaGrpc(paths, destDir, grpcSource, doneCounter, total);
    }

    /** @return whichever nodes HTTP could not fetch after retrying (for the gRPC fallback). */
    private static List<FileNode> downloadAllViaHttp(List<FileNode> nodes, File destDir, String baseUrl,
                                                       AtomicInteger doneCounter, int total) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(HTTP_PARALLEL_DOWNLOADS, nodes.size()));
        List<Future<FileNode>> futures = new ArrayList<>();
        AtomicInteger httpSuccesses = new AtomicInteger(0);
        AtomicInteger httpAttemptFailures = new AtomicInteger(0);
        AtomicBoolean httpAbandoned = new AtomicBoolean(false);
        try {
            for (FileNode node : nodes) {
                futures.add(pool.submit(() ->
                        downloadOneViaHttp(node, destDir, baseUrl, doneCounter, total,
                                httpSuccesses, httpAttemptFailures, httpAbandoned) ? null : node));
            }
            List<FileNode> failed = new ArrayList<>();
            for (Future<FileNode> future : futures) {
                try {
                    FileNode failedNode = future.get();
                    if (failedNode != null) failed.add(failedNode);
                } catch (Exception e) {
                    Log.w("McSkillInstaller", "HTTP download task failed unexpectedly", e);
                }
            }
            if (!failed.isEmpty()) {
                Log.i("McSkillInstaller", failed.size() + " file(s) falling back to gRPC download"
                        + (httpAbandoned.get() ? " (HTTP fast path abandoned - base_url looked unreachable)" : ""));
            }
            return failed;
        } finally {
            pool.shutdown();
        }
    }

    private static boolean downloadOneViaHttp(FileNode node, File destDir, String baseUrl,
                                               AtomicInteger doneCounter, int total,
                                               AtomicInteger httpSuccesses, AtomicInteger httpAttemptFailures,
                                               AtomicBoolean httpAbandoned) {
        if (httpAbandoned.get()) return false; // Already given up on HTTP for this batch - go straight to gRPC.
        File target = new File(destDir, node.getPath());
        String url = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + encodePathSegments(node.getPath());
        for (int attempt = 1; attempt <= HTTP_MAX_RETRIES; attempt++) {
            if (httpAbandoned.get()) return false;
            HttpURLConnection conn = null;
            try {
                File parent = target.getParentFile();
                if (parent != null) //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();

                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);
                conn.setRequestMethod("GET");
                conn.setUseCaches(false); // Android's HTTP response cache has no reason to hold onto these.
                conn.connect();
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + conn.getResponseCode() + " for " + url);
                }
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[HTTP_COPY_BUFFER_SIZE];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                }

                if (target.length() == node.getSize() && matchesHash(target, node.getHash().toByteArray())) {
                    httpSuccesses.incrementAndGet();
                    int done = doneCounter.incrementAndGet();
                    ProgressLayout.setProgress(ProgressLayout.INSTALL_MCSKILL_CLIENT,
                            (int) ((done * 100L) / total), done + "/" + total + " files");
                    return true;
                }
                throw new IOException("Downloaded " + node.getPath() + " but it doesn't match the expected size/hash");
            } catch (Exception e) {
                Log.w("McSkillInstaller", "HTTP attempt " + attempt + "/" + HTTP_MAX_RETRIES
                        + " failed for " + node.getPath() + ": " + e);
                if (httpSuccesses.get() == 0
                        && httpAttemptFailures.incrementAndGet() >= HTTP_CIRCUIT_BREAKER_FAILURES
                        && httpAbandoned.compareAndSet(false, true)) {
                    Log.w("McSkillInstaller", "Abandoning HTTP fast path after " + HTTP_CIRCUIT_BREAKER_FAILURES
                            + " failed attempts with zero successes (base_url=" + baseUrl + ") - falling back to gRPC");
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return false;
    }

    /** URL-encodes each '/'-separated segment of a relative path without touching the separators. */
    private static String encodePathSegments(String path) {
        String[] segments = path.split("/");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) result.append('/');
            try {
                result.append(URLEncoder.encode(segments[i], "UTF-8").replace("+", "%20"));
            } catch (UnsupportedEncodingException e) {
                result.append(segments[i]); // UTF-8 is always available; this can't actually happen.
            }
        }
        return result.toString();
    }

    /**
     * Downloads {@code paths} into {@code destDir} in small sub-batches (see
     * {@link #GRPC_SUBBATCH_SIZE}), up to {@link #GRPC_PARALLEL_DOWNLOADS} of them in flight at
     * once via a bounded worker pool. gRPC multiplexes streams over the same HTTP/2 connection,
     * so this is real parallelism without opening extra sockets. Each sub-batch retries on its
     * own ({@link #GRPC_MAX_RETRIES} attempts, only re-requesting whatever didn't finish) rather
     * than one failed stream aborting everything else that was still downloading fine.
     */
    private static void downloadAllViaGrpc(List<String> paths, File destDir, GrpcChunkSource source,
                                            AtomicInteger doneCounter, int total) throws IOException {
        List<List<String>> subBatches = chunk(paths, GRPC_SUBBATCH_SIZE);
        int parallelism = Math.min(GRPC_PARALLEL_DOWNLOADS, subBatches.size());

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
                throw new IOException(stillFailed.size() + " file(s) failed after " + GRPC_MAX_RETRIES
                        + " attempts each, e.g. \"" + stillFailed.get(0) + "\"");
            }
        } finally {
            pool.shutdown();
        }
    }

    /** @return whichever of {@code paths} still didn't complete after retrying. */
    private static List<String> downloadSubBatchWithRetry(GrpcChunkSource source, List<String> paths, File destDir,
                                                            AtomicInteger doneCounter, int total) {
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
                Log.w("McSkillInstaller", "Attempt " + attempt + "/" + GRPC_MAX_RETRIES + " failed with "
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
