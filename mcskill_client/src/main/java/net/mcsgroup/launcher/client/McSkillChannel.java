package net.mcsgroup.launcher.client;

import io.grpc.ManagedChannel;
import io.grpc.okhttp.OkHttpChannelBuilder;
import net.mcsgroup.launcher.proto.AuthServiceGrpc;

public class McSkillChannel {
    public static final String DEFAULT_HOST = "launchernew.mcskill.ru";
    public static final int DEFAULT_PORT = 443;

    private final ManagedChannel channel;
    private final AuthServiceGrpc.AuthServiceBlockingStub authStub;

    public McSkillChannel(String host, int port) {
        this.channel = OkHttpChannelBuilder.forAddress(host, port)
                .useTransportSecurity()
                .build();
        this.authStub = AuthServiceGrpc.newBlockingStub(channel);
    }

    public static McSkillChannel createDefault() {
        return new McSkillChannel(DEFAULT_HOST, DEFAULT_PORT);
    }

    public AuthServiceGrpc.AuthServiceBlockingStub authStub() {
        return authStub;
    }

    public void shutdown() {
        channel.shutdown();
    }
}
