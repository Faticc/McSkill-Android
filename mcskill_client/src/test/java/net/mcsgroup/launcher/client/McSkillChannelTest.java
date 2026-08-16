package net.mcsgroup.launcher.client;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class McSkillChannelTest {
    @Test
    public void authStubIsAvailableWithoutConnecting() {
        // gRPC channel construction is lazy - no socket is opened until the first RPC,
        // so an unreachable host/port is safe to use here.
        McSkillChannel channel = new McSkillChannel("localhost", 1);
        assertNotNull(channel.authStub());
        channel.shutdown();
    }
}
