package net.mcsgroup.launcher.client;

public class McSkillSession {
    public final String sessionId;
    public final McSkillProfile profile;

    public McSkillSession(String sessionId, McSkillProfile profile) {
        this.sessionId = sessionId;
        this.profile = profile;
    }
}
