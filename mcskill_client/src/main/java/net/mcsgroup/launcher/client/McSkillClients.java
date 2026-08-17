package net.mcsgroup.launcher.client;

import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import net.mcsgroup.launcher.proto.ClientInfo;
import net.mcsgroup.launcher.proto.ClientProfile;
import net.mcsgroup.launcher.proto.ClientServiceGrpc;
import net.mcsgroup.launcher.proto.GetClientRequest;
import net.mcsgroup.launcher.proto.GetClientsRequest;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class McSkillClients {
    private static final Metadata.Key<String> SESSION_HEADER =
            Metadata.Key.of("session", Metadata.ASCII_STRING_MARSHALLER);

    private final ClientServiceGrpc.ClientServiceBlockingStub stub;

    public McSkillClients(ClientServiceGrpc.ClientServiceBlockingStub stub) {
        this.stub = stub;
    }

    public List<ClientInfo> getClients(String sessionId) {
        try {
            return withSession(sessionId)
                    .getClients(GetClientsRequest.newBuilder().build())
                    .getClientsList();
        } catch (StatusRuntimeException e) {
            throw McSkillException.fromStatus(e.getStatus());
        }
    }

    public ClientProfile getClient(int clientId, String sessionId) {
        try {
            return withSession(sessionId)
                    .getClient(GetClientRequest.newBuilder().setClientId(clientId).build())
                    .getClient();
        } catch (StatusRuntimeException e) {
            throw McSkillException.fromStatus(e.getStatus());
        }
    }

    private ClientServiceGrpc.ClientServiceBlockingStub withSession(String sessionId) {
        Metadata metadata = new Metadata();
        metadata.put(SESSION_HEADER, sessionId);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .withDeadlineAfter(30, TimeUnit.SECONDS);
    }
}
