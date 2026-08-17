package net.mcsgroup.launcher.client;

import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import net.mcsgroup.launcher.proto.AuthServiceGrpc;
import net.mcsgroup.launcher.proto.GetProfileRequest;
import net.mcsgroup.launcher.proto.GetProfileResponse;
import net.mcsgroup.launcher.proto.LoginRequest;
import net.mcsgroup.launcher.proto.LoginResponse;
import net.mcsgroup.launcher.proto.LogoutRequest;
import net.mcsgroup.launcher.proto.PlayerProfile;
import net.mcsgroup.launcher.proto.Session;

import java.util.concurrent.TimeUnit;

public class McSkillAuth {
    private static final Metadata.Key<String> SESSION_HEADER =
            Metadata.Key.of("session", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Upper bound on how long a single mcskill RPC may take. Without it a hung server would pin one
     * of the launcher's shared executor threads forever.
     */
    private static final long RPC_DEADLINE_SECONDS = 30;

    private final AuthServiceGrpc.AuthServiceBlockingStub stub;

    public McSkillAuth(AuthServiceGrpc.AuthServiceBlockingStub stub) {
        this.stub = stub;
    }

    public McSkillSession login(String username, String password) {
        LoginRequest request = LoginRequest.newBuilder()
                .setUsername(username)
                .setPassword(password)
                .build();
        LoginResponse response;
        try {
            response = withDeadline(stub).login(request);
        } catch (StatusRuntimeException e) {
            throw McSkillException.fromStatus(e.getStatus());
        }
        if (response.hasSessionData()) {
            Session session = response.getSessionData();
            return new McSkillSession(session.getId(), toProfile(session.getProfile()));
        }
        if (response.hasMfaRequired()) {
            throw new McSkillException(McSkillException.ErrorCode.MFA_REQUIRED,
                    "This mcskill account requires MFA, which isn't supported yet");
        }
        throw new McSkillException(McSkillException.ErrorCode.UNKNOWN, "Unrecognized login response");
    }

    public void logout(String sessionId) {
        try {
            withSession(sessionId).logout(LogoutRequest.newBuilder().build());
        } catch (StatusRuntimeException e) {
            throw McSkillException.fromStatus(e.getStatus());
        }
    }

    public McSkillProfile getProfile(String sessionId) {
        GetProfileResponse response;
        try {
            response = withSession(sessionId).getProfile(GetProfileRequest.newBuilder().build());
        } catch (StatusRuntimeException e) {
            throw McSkillException.fromStatus(e.getStatus());
        }
        return toProfile(response.getProfile());
    }

    private AuthServiceGrpc.AuthServiceBlockingStub withSession(String sessionId) {
        Metadata metadata = new Metadata();
        metadata.put(SESSION_HEADER, sessionId);
        return withDeadline(stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata)));
    }

    /**
     * Bounds a single RPC in time. A blown deadline surfaces as {@code DEADLINE_EXCEEDED}, which
     * {@link McSkillException#fromStatus} already maps to {@code NETWORK_UNAVAILABLE}.
     */
    private static AuthServiceGrpc.AuthServiceBlockingStub withDeadline(
            AuthServiceGrpc.AuthServiceBlockingStub stub) {
        return stub.withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS);
    }

    private static McSkillProfile toProfile(PlayerProfile profile) {
        return new McSkillProfile(profile.getUuid(), profile.getUsername(), profile.getSkinUrl());
    }
}
