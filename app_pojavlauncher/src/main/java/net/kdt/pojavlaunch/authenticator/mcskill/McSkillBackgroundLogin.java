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
                McSkillAuth auth = new McSkillAuth(channel.authStub());
                McSkillSession session = auth.login(mUsername, mPassword);

                MinecraftAccount acc = MinecraftAccount.load(session.profile.username);
                if (acc == null) acc = new MinecraftAccount();
                acc.isMcSkill = true;
                acc.username = session.profile.username;
                acc.profileId = session.profile.uuid;
                acc.accessToken = session.sessionId;
                acc.updateSkinFace();
                acc.save();

                new McSkillCredentialStore(mContext).storePassword(session.profile.username, mPassword);

                MinecraftAccount finalAcc = acc;
                if (doneListener != null) Tools.runOnUiThread(() -> doneListener.onLoginDone(finalAcc));
            } catch (McSkillException e) {
                Log.e("McSkillAuth", "mcskill login failed", e);
                if (errorListener != null) {
                    int stringId = e.getErrorCode() == McSkillException.ErrorCode.MFA_REQUIRED
                            ? R.string.mcskill_mfa_not_supported
                            : R.string.mcskill_login_failed;
                    PresentedException presented = new PresentedException(e, stringId);
                    Tools.runOnUiThread(() -> errorListener.onLoginError(presented));
                }
            } catch (Exception e) {
                Log.e("McSkillAuth", "Unexpected exception during mcskill login", e);
                if (errorListener != null) Tools.runOnUiThread(() -> errorListener.onLoginError(e));
            } finally {
                channel.shutdown();
                ProgressLayout.clearProgress(ProgressLayout.AUTHENTICATE_MCSKILL);
            }
        });
    }
}
