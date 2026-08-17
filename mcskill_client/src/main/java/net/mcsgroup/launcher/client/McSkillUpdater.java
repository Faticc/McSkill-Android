package net.mcsgroup.launcher.client;

import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import net.mcsgroup.launcher.proto.AssetDownloadRequest;
import net.mcsgroup.launcher.proto.AssetFileTreeRequest;
import net.mcsgroup.launcher.proto.DownloadRequest;
import net.mcsgroup.launcher.proto.FileChunk;
import net.mcsgroup.launcher.proto.FileTreeRequest;
import net.mcsgroup.launcher.proto.FileTreeResponse;
import net.mcsgroup.launcher.proto.UpdateServiceGrpc;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class McSkillUpdater {
    private static final Metadata.Key<String> SESSION_HEADER =
            Metadata.Key.of("session", Metadata.ASCII_STRING_MARSHALLER);

    private final UpdateServiceGrpc.UpdateServiceBlockingStub stub;

    public McSkillUpdater(UpdateServiceGrpc.UpdateServiceBlockingStub stub) {
        this.stub = stub;
    }

    public FileTreeResponse getFileTree(int clientId, String sessionId) {
        try {
            return withSession(sessionId, 30, TimeUnit.SECONDS)
                    .getFileTree(FileTreeRequest.newBuilder().setClientId(clientId).build());
        } catch (StatusRuntimeException e) {
            throw McSkillException.fromStatus(e.getStatus());
        }
    }

    public FileTreeResponse getAssetFileTree(String assetDir, String sessionId) {
        try {
            return withSession(sessionId, 30, TimeUnit.SECONDS)
                    .getAssetFileTree(AssetFileTreeRequest.newBuilder().setAssetDir(assetDir).build());
        } catch (StatusRuntimeException e) {
            throw McSkillException.fromStatus(e.getStatus());
        }
    }

    /** Streams every requested client file. The deadline covers the whole call, not per-chunk. */
    public Iterator<FileChunk> downloadFiles(int clientId, List<String> paths, String sessionId) {
        try {
            return withSession(sessionId, 30, TimeUnit.MINUTES).downloadFiles(
                    DownloadRequest.newBuilder().setClientId(clientId).addAllPaths(paths).build());
        } catch (StatusRuntimeException e) {
            throw McSkillException.fromStatus(e.getStatus());
        }
    }

    public Iterator<FileChunk> downloadAssetFiles(String assetDir, List<String> paths, String sessionId) {
        try {
            return withSession(sessionId, 30, TimeUnit.MINUTES).downloadAssetFiles(
                    AssetDownloadRequest.newBuilder().setAssetDir(assetDir).addAllPaths(paths).build());
        } catch (StatusRuntimeException e) {
            throw McSkillException.fromStatus(e.getStatus());
        }
    }

    private UpdateServiceGrpc.UpdateServiceBlockingStub withSession(String sessionId, long deadline, TimeUnit unit) {
        Metadata metadata = new Metadata();
        metadata.put(SESSION_HEADER, sessionId);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .withDeadlineAfter(deadline, unit);
    }
}
