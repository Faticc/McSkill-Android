package net.kdt.pojavlaunch.authenticator.mcskill;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.listener.DoneListener;
import net.kdt.pojavlaunch.authenticator.listener.ErrorListener;
import net.kdt.pojavlaunch.authenticator.microsoft.PresentedException;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.mcsgroup.launcher.client.McSkillAuth;
import net.mcsgroup.launcher.client.McSkillChannel;
import net.mcsgroup.launcher.client.McSkillException;
import net.mcsgroup.launcher.client.McSkillSession;

import java.io.IOException;
import java.security.GeneralSecurityException;

/** Validates a stored mcskill session, and silently re-logs in with the stored password if it has expired. */
public class McSkillSessionRefresh {
    private final Context mContext;
    private final MinecraftAccount mAccount;

    public McSkillSessionRefresh(Context context, MinecraftAccount account) {
        mContext = context.getApplicationContext();
        mAccount = account;
    }

    public void performRefresh(@Nullable DoneListener doneListener, @Nullable ErrorListener errorListener) {
        sExecutorService.execute(() -> {
            McSkillChannel channel = McSkillChannel.createDefault();
            try {
                if (!McSkillCredentialStore.isSupported()) {
                    // Can't have stored a password securely here, so a refresh can never succeed.
                    // Say why, instead of letting the constructor throw a raw security exception.
                    if (errorListener != null) {
                        PresentedException presented = new PresentedException(
                                new IllegalStateException("mcskill requires API "
                                        + McSkillCredentialStore.MIN_SUPPORTED_SDK),
                                R.string.mcskill_unsupported_android_version);
                        Tools.runOnUiThread(() -> errorListener.onLoginError(presented));
                    }
                    return;
                }

                McSkillAuth auth = new McSkillAuth(channel.authStub());
                try {
                    auth.getProfile(mAccount.accessToken);
                    return; // Session is still valid, nothing else to do.
                } catch (McSkillException e) {
                    if (e.getErrorCode() != McSkillException.ErrorCode.UNAUTHENTICATED) throw e;
                }

                String storedPassword = new McSkillCredentialStore(mContext).getPassword(mAccount.username);
                if (storedPassword == null) {
                    throw new McSkillException(McSkillException.ErrorCode.UNAUTHENTICATED,
                            "No stored password to refresh the mcskill session with");
                }

                McSkillSession session = auth.login(mAccount.username, storedPassword);
                mAccount.accessToken = session.sessionId;
                mAccount.save();

                if (doneListener != null) {
                    Tools.runOnUiThread(() -> doneListener.onLoginDone(mAccount));
                }
            } catch (McSkillException e) {
                Log.w("McSkillAuth", "Could not refresh mcskill session, user must log in again", e);
                if (errorListener != null) {
                    PresentedException presented = new PresentedException(e, refreshErrorString(e));
                    Tools.runOnUiThread(() -> errorListener.onLoginError(presented));
                }
            } catch (GeneralSecurityException | IOException e) {
                Log.e("McSkillAuth", "Failed to read the stored mcskill credential", e);
                if (errorListener != null) Tools.runOnUiThread(() -> errorListener.onLoginError(e));
            } finally {
                channel.shutdown();
            }
        });
    }

    /**
     * Maps a refresh failure to a message describing the actual cause. "Session expired, log in
     * again" stays the default, but it is plainly wrong for e.g. a dropped connection.
     */
    private static int refreshErrorString(McSkillException e) {
        switch (e.getErrorCode()) {
            case MFA_REQUIRED:
                return R.string.mcskill_mfa_not_supported;
            case NETWORK_UNAVAILABLE:
                return R.string.mcskill_network_unavailable;
            case INVALID_CREDENTIALS:
                // The stored password no longer works (changed on the website, most likely).
                return R.string.mcskill_invalid_credentials;
            default:
                return R.string.mcskill_session_expired;
        }
    }
}
