package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.MineEditText;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.mcskill.McSkillCredentialStore;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;

public class McSkillLoginFragment extends Fragment {
    public static final String TAG = "MCSKILL_LOGIN_FRAGMENT";

    private MineEditText mUsernameEditText;
    private MineEditText mPasswordEditText;

    public McSkillLoginFragment() {
        super(R.layout.fragment_mcskill_login);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mUsernameEditText = view.findViewById(R.id.mcskill_login_username);
        mPasswordEditText = view.findViewById(R.id.mcskill_login_password);

        view.findViewById(R.id.mcskill_login_button).setOnClickListener(v -> {
            // Below API 23 the credential store cannot encrypt the password for real, so the whole
            // mcskill flow is refused rather than silently storing it in the clear.
            if (!McSkillCredentialStore.isSupported()) {
                Tools.dialog(v.getContext(), getString(R.string.global_error),
                        getString(R.string.mcskill_unsupported_android_version));
                return;
            }

            String username = mUsernameEditText.getText().toString();
            String password = mPasswordEditText.getText().toString();
            if (username.isEmpty() || password.isEmpty()) {
                Tools.dialog(v.getContext(), getString(R.string.global_error), getString(R.string.global_error_field_empty));
                return;
            }

            ExtraCore.setValue(ExtraConstants.MCSKILL_LOGIN_TODO, new String[]{username, password});
            Tools.backToMainMenu(requireActivity());
        });
    }
}
