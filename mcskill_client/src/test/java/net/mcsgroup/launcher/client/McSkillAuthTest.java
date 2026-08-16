package net.mcsgroup.launcher.client;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import net.mcsgroup.launcher.proto.AuthServiceGrpc;
import net.mcsgroup.launcher.proto.GetProfileRequest;
import net.mcsgroup.launcher.proto.GetProfileResponse;
import net.mcsgroup.launcher.proto.LoginRequest;
import net.mcsgroup.launcher.proto.LoginResponse;
import net.mcsgroup.launcher.proto.MfaChallenge;
import net.mcsgroup.launcher.proto.PlayerProfile;
import net.mcsgroup.launcher.proto.Session;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class McSkillAuthTest {
    @Rule
    public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

    private McSkillAuth startServerAndCreateAuth(AuthServiceGrpc.AuthServiceImplBase servicer) throws IOException {
        String serverName = InProcessServerBuilder.generateName();
        grpcCleanup.register(InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(servicer)
                .build()
                .start());
        ManagedChannel channel = grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).directExecutor().build());
        return new McSkillAuth(AuthServiceGrpc.newBlockingStub(channel));
    }

    @Test
    public void loginReturnsSessionOnSuccess() throws IOException {
        McSkillAuth auth = startServerAndCreateAuth(new AuthServiceGrpc.AuthServiceImplBase() {
            @Override
            public void login(LoginRequest request, StreamObserver<LoginResponse> responseObserver) {
                PlayerProfile profile = PlayerProfile.newBuilder()
                        .setUuid("uuid-1").setUsername("Fatic").setSkinUrl("http://example/skin.png")
                        .build();
                Session session = Session.newBuilder().setId("session-1").setProfile(profile).build();
                responseObserver.onNext(LoginResponse.newBuilder().setSessionData(session).build());
                responseObserver.onCompleted();
            }
        });

        McSkillSession session = auth.login("Fatic", "password");

        assertEquals("session-1", session.sessionId);
        assertEquals("Fatic", session.profile.username);
        assertEquals("uuid-1", session.profile.uuid);
    }

    @Test
    public void loginThrowsMfaRequiredWhenServerRequestsMfa() throws IOException {
        McSkillAuth auth = startServerAndCreateAuth(new AuthServiceGrpc.AuthServiceImplBase() {
            @Override
            public void login(LoginRequest request, StreamObserver<LoginResponse> responseObserver) {
                responseObserver.onNext(LoginResponse.newBuilder()
                        .setMfaRequired(MfaChallenge.newBuilder().build())
                        .build());
                responseObserver.onCompleted();
            }
        });

        McSkillException e = assertThrows(McSkillException.class, () -> auth.login("Fatic", "password"));
        assertEquals(McSkillException.ErrorCode.MFA_REQUIRED, e.getErrorCode());
    }

    @Test
    public void getProfileSendsSessionIdAsMetadata() throws IOException {
        AtomicReference<String> capturedSessionId = new AtomicReference<>();
        Metadata.Key<String> sessionKey = Metadata.Key.of("session", Metadata.ASCII_STRING_MARSHALLER);

        ServerInterceptor captureInterceptor = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                capturedSessionId.set(headers.get(sessionKey));
                return next.startCall(call, headers);
            }
        };

        AuthServiceGrpc.AuthServiceImplBase servicer = new AuthServiceGrpc.AuthServiceImplBase() {
            @Override
            public void getProfile(GetProfileRequest request, StreamObserver<GetProfileResponse> responseObserver) {
                PlayerProfile profile = PlayerProfile.newBuilder()
                        .setUuid("uuid-2").setUsername("Fatic").setSkinUrl("").build();
                responseObserver.onNext(GetProfileResponse.newBuilder().setProfile(profile).build());
                responseObserver.onCompleted();
            }
        };

        String serverName = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(servicer, captureInterceptor))
                .build()
                .start();
        grpcCleanup.register(server);
        ManagedChannel channel = grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).directExecutor().build());
        McSkillAuth auth = new McSkillAuth(AuthServiceGrpc.newBlockingStub(channel));

        McSkillProfile profile = auth.getProfile("session-42");

        assertEquals("session-42", capturedSessionId.get());
        assertEquals("Fatic", profile.username);
    }
}
