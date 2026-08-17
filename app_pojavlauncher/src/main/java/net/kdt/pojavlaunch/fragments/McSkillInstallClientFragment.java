package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.MineEditText;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.mcskill.install.McSkillClientInstaller;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.mcsgroup.launcher.client.McSkillException;

/**
 * Minimal, developer-preview entry point: type an mcskill client id, install it as a normal
 * profile. Reached via a long-press on the main menu (see MainMenuFragment) until a proper
 * client-browsing screen exists.
 */
public class McSkillInstallClientFragment extends Fragment {
    public static final String TAG = "MCSKILL_INSTALL_CLIENT_FRAGMENT";

    private TextView mStatus;

    public McSkillInstallClientFragment() {
        super(R.layout.fragment_mcskill_install_client);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        MineEditText clientIdField = view.findViewById(R.id.mcskill_install_client_id);
        mStatus = view.findViewById(R.id.mcskill_install_status);

        view.findViewById(R.id.mcskill_install_button).setOnClickListener(v -> {
            String idText = clientIdField.getText().toString().trim();
            if (idText.isEmpty()) {
                appendStatus("Enter a client id first.");
                return;
            }
            int clientId;
            try {
                clientId = Integer.parseInt(idText);
            } catch (NumberFormatException e) {
                appendStatus("That's not a number.");
                return;
            }

            MinecraftAccount account = PojavProfile.getCurrentProfileContent(requireContext(), null);
            if (account == null || !account.isMcSkill) {
                appendStatus("Log into an mcskill account first (account switcher -> MCSkill Account).");
                return;
            }

            mStatus.setText("");
            startInstall(clientId, account.accessToken);
        });
    }

    private void startInstall(int clientId, String sessionId) {
        sExecutorService.execute(() -> {
            try {
                String profileKey = McSkillClientInstaller.install(clientId, sessionId, this::appendStatusFromWorker);
                Tools.runOnUiThread(() -> {
                    appendStatus("Installed. Selecting it in the version list...");
                    ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, profileKey);
                });
            } catch (McSkillException e) {
                Log.e("McSkillInstall", "Client install failed", e);
                Tools.runOnUiThread(() -> appendStatus("Failed: " + e.getMessage()));
            } catch (Exception e) {
                Log.e("McSkillInstall", "Unexpected exception during client install", e);
                Tools.runOnUiThread(() -> appendStatus("Failed: " + e));
            }
        });
    }

    private void appendStatusFromWorker(String message) {
        Tools.runOnUiThread(() -> appendStatus(message));
    }

    private void appendStatus(String message) {
        if (mStatus == null) return;
        mStatus.append(message + "\n");
    }
}
