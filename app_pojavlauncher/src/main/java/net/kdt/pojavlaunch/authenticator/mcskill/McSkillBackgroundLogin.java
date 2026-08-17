package net.kdt.pojavlaunch.authenticator.mcskill;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.listener.DoneListener;
import net.kdt.pojavlaunch.authenticator.listener.ErrorListener;
import net.kdt.pojavlaunch.authenticator.microsoft.PresentedException;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.mcsgroup.launcher.client.McSkillAuth;
import net.mcsgroup.launcher.client.McSkillChannel;
import net.mcsgroup.launcher.client.McSkillException;
import net.mcsgroup.launcher.client.McSkillProfile;
import net.mcsgroup.launcher.client.McSkillSession;

/** Performs an mcskill login on a given username/password, off the UI thread. */
public class McSkillBackgroundLogin {
    private final Context mContext;
    private final String mUsername;
    private final String mPassword;

    public McSkillBackgroundLogin(Context context, String username, String password) {
        mContext = context.getApplicationContext();
        mUsername = username;
        mPassword = password;
    }

    public void performLogin(@Nullable final DoneListener doneListener,
                              @Nullable final ErrorListener errorListener) {
        sExecutorService.execute(() -> {
            McSkillChannel channel = McSkillChannel.createDefault();
            try {
                ProgressLayout.setProgress(ProgressLayout.AUTHENTICATE_MCSKILL, 0);
                McSkillAuth auth = new McSkillAuth(channel.authStub());
                McSkillSession session = auth.login(mUsername, mPassword);
                // The profile is attacker-controlled if the server is hostile or compromised, and
                // both fields end up as filesystem path components / in a URL. Reject anything odd.
                McSkillProfileValidator.requireValid(session.profile);

                MinecraftAccount acc = MinecraftAccount.load(session.profile.username);
                if (acc == null) acc = new MinecraftAccount();
                acc.isMcSkill = true;
                // A username collision with a pre-existing Microsoft account must not leave a hybrid
                // account behind - performLogin() checks isMicrosoft first and would win forever.
                acc.isMicrosoft = false;
                acc.username = session.profile.username;
                acc.profileId = session.profile.uuid;
                acc.accessToken = session.sessionId;
                // mc-heads.net is keyed by Mojang UUIDs, so it can never resolve an mcskill profile.
                // Use the skin URL the mcskill server handed us instead.
                acc.updateSkinFaceFromUrl(session.profile.skinUrl);
                acc.save();

                new McSkillCredentialStore(mContext).storePassword(session.profile.username, mPassword);

                MinecraftAccount finalAcc = acc;
                ProgressLayout.setProgress(ProgressLayout.AUTHENTICATE_MCSKILL, 100);
                if (doneListener != null) Tools.runOnUiThread(() -> doneListener.onLoginDone(finalAcc));
            } catch (McSkillException e) {
                Log.e("McSkillAuth", "mcskill login failed", e);
                ProgressLayout.setProgress(ProgressLayout.AUTHENTICATE_MCSKILL, 100);
                if (errorListener != null) {
                    PresentedException presented = new PresentedException(e, loginErrorString(e));
                    Tools.runOnUiThread(() -> errorListener.onLoginError(presented));
                }
            } catch (Exception e) {
                Log.e("McSkillAuth", "Unexpected exception during mcskill login", e);
                ProgressLayout.setProgress(ProgressLayout.AUTHENTICATE_MCSKILL, 100);
                if (errorListener != null) Tools.runOnUiThread(() -> errorListener.onLoginError(e));
            } finally {
                channel.shutdown();
                ProgressLayout.clearProgress(ProgressLayout.AUTHENTICATE_MCSKILL);
            }
        });
    }

    /** Maps an mcskill failure to a message that actually describes what went wrong. */
    static int loginErrorString(McSkillException e) {
        switch (e.getErrorCode()) {
            case MFA_REQUIRED:
                return R.string.mcskill_mfa_not_supported;
            case NETWORK_UNAVAILABLE:
                return R.string.mcskill_network_unavailable;
            case INVALID_CREDENTIALS:
                return R.string.mcskill_invalid_credentials;
            default:
                return R.string.mcskill_login_failed;
        }
    }

    /** Validates the untrusted {@link McSkillProfile} the server sends back. */
    static final class McSkillProfileValidator {
        /**
         * Minecraft-ish username: no separators, bounded length. The first character must be
         * alphanumeric/underscore, which is what rules out "." and ".." (both of which the plain
         * "[A-Za-z0-9_.\-]{1,32}" character class would happily accept as path components).
         */
        private static final String USERNAME_PATTERN = "[A-Za-z0-9_][A-Za-z0-9_.\\-]{0,31}";
        /** Standard UUID, dashed or bare hex-32 - mcskill has been observed to use both shapes. */
        private static final String UUID_PATTERN =
                "[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}";

        private McSkillProfileValidator() {}

        static void requireValid(McSkillProfile profile) {
            if (profile == null
                    || profile.username == null || !profile.username.matches(USERNAME_PATTERN)
                    || profile.uuid == null || !profile.uuid.matches(UUID_PATTERN)) {
                // Deliberately does not echo the offending values back into the UI.
                throw new McSkillException(McSkillException.ErrorCode.UNKNOWN,
                        "mcskill server returned an invalid profile");
            }
        }
    }
}
