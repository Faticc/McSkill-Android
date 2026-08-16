package net.mcsgroup.launcher.proto;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class McSkillProtoSmokeTest {
    @Test
    public void loginRequestRoundTripsFields() {
        LoginRequest request = LoginRequest.newBuilder()
                .setUsername("Fatic")
                .setPassword("hunter2")
                .build();

        assertEquals("Fatic", request.getUsername());
        assertEquals("hunter2", request.getPassword());
    }

    @Test
    public void loginResponseOneofDistinguishesSessionFromMfa() {
        PlayerProfile profile = PlayerProfile.newBuilder()
                .setUuid("uuid-1")
                .setUsername("Fatic")
                .setSkinUrl("http://example/skin.png")
                .build();
        Session session = Session.newBuilder().setId("session-1").setProfile(profile).build();

        LoginResponse sessionResponse = LoginResponse.newBuilder().setSessionData(session).build();
        assertTrue(sessionResponse.hasSessionData());
        assertEquals("session-1", sessionResponse.getSessionData().getId());

        LoginResponse mfaResponse = LoginResponse.newBuilder()
                .setMfaRequired(MfaChallenge.newBuilder().build())
                .build();
        assertTrue(mfaResponse.hasMfaRequired());
    }
}
