package net.mcsgroup.launcher.client;

import io.grpc.ManagedChannel;
import io.grpc.okhttp.OkHttpChannelBuilder;
import net.mcsgroup.launcher.proto.AuthServiceGrpc;
import net.mcsgroup.launcher.proto.ClientServiceGrpc;
import net.mcsgroup.launcher.proto.UpdateServiceGrpc;

public class McSkillChannel {
    public static final String DEFAULT_HOST = "launchernew.mcskill.ru";
    public static final int DEFAULT_PORT = 443;

    private final ManagedChannel channel;
    private final AuthServiceGrpc.AuthServiceBlockingStub authStub;
    private final ClientServiceGrpc.ClientServiceBlockingStub clientsStub;
    private final UpdateServiceGrpc.UpdateServiceBlockingStub updateStub;

    // gRPC's default HTTP/2 flow-control window (64KB) caps a single stream's throughput to
    // roughly window/RTT - fine on a low-latency wired connection, but on mobile networks with
    // RTTs of 100-200ms that works out to only a few hundred KB/s per stream regardless of how
    // fast the link actually is. A larger window lets each download stream use much more of the
    // available bandwidth before it has to wait for a WINDOW_UPDATE round trip.
    private static final int FLOW_CONTROL_WINDOW_BYTES = 4 * 1024 * 1024;

    public McSkillChannel(String host, int port) {
        this.channel = OkHttpChannelBuilder.forAddress(host, port)
                .useTransportSecurity()
                .flowControlWindow(FLOW_CONTROL_WINDOW_BYTES)
                .build();
        this.authStub = AuthServiceGrpc.newBlockingStub(channel);
        this.clientsStub = ClientServiceGrpc.newBlockingStub(channel);
        this.updateStub = UpdateServiceGrpc.newBlockingStub(channel);
    }

    public static McSkillChannel createDefault() {
        return new McSkillChannel(DEFAULT_HOST, DEFAULT_PORT);
    }

    public AuthServiceGrpc.AuthServiceBlockingStub authStub() {
        return authStub;
    }

    public ClientServiceGrpc.ClientServiceBlockingStub clientsStub() {
        return clientsStub;
    }

    public UpdateServiceGrpc.UpdateServiceBlockingStub updateStub() {
        return updateStub;
    }

    public void shutdown() {
        channel.shutdown();
    }
}
