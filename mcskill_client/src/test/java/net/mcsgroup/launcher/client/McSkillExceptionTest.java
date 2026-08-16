package net.mcsgroup.launcher.client;

import io.grpc.Status;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class McSkillExceptionTest {
    @Test
    public void unavailableMapsToNetworkUnavailable() {
        McSkillException e = McSkillException.fromStatus(Status.UNAVAILABLE.withDescription("down"));
        assertEquals(McSkillException.ErrorCode.NETWORK_UNAVAILABLE, e.getErrorCode());
    }

    @Test
    public void deadlineExceededMapsToNetworkUnavailable() {
        McSkillException e = McSkillException.fromStatus(Status.DEADLINE_EXCEEDED);
        assertEquals(McSkillException.ErrorCode.NETWORK_UNAVAILABLE, e.getErrorCode());
    }

    @Test
    public void unauthenticatedMapsToUnauthenticated() {
        McSkillException e = McSkillException.fromStatus(Status.UNAUTHENTICATED.withDescription("bad session"));
        assertEquals(McSkillException.ErrorCode.UNAUTHENTICATED, e.getErrorCode());
    }

    @Test
    public void invalidArgumentMapsToInvalidCredentials() {
        McSkillException e = McSkillException.fromStatus(Status.INVALID_ARGUMENT);
        assertEquals(McSkillException.ErrorCode.INVALID_CREDENTIALS, e.getErrorCode());
    }

    @Test
    public void permissionDeniedMapsToInvalidCredentials() {
        McSkillException e = McSkillException.fromStatus(Status.PERMISSION_DENIED);
        assertEquals(McSkillException.ErrorCode.INVALID_CREDENTIALS, e.getErrorCode());
    }

    @Test
    public void internalMapsToUnknown() {
        McSkillException e = McSkillException.fromStatus(Status.INTERNAL);
        assertEquals(McSkillException.ErrorCode.UNKNOWN, e.getErrorCode());
    }
}
