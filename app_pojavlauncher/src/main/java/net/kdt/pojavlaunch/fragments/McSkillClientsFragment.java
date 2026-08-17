package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.mcskill.ui.McSkillClientAdapter;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.mcsgroup.launcher.client.McSkillChannel;
import net.mcsgroup.launcher.client.McSkillClients;
import net.mcsgroup.launcher.client.McSkillException;
import net.mcsgroup.launcher.proto.ClientInfo;

import java.util.List;

/** Lists every client the mcskill account can see and hands off to McSkillInstallClientFragment. */
public class McSkillClientsFragment extends Fragment {
    public static final String TAG = "MCSKILL_CLIENTS_FRAGMENT";
    public static final String ARG_CLIENT_ID = "mcskill_client_id";
    public static final String ARG_AUTOSTART = "mcskill_autostart";

    private TextView mStatus;
    private RecyclerView mRecyclerView;

    public McSkillClientsFragment() {
        super(R.layout.fragment_mcskill_clients);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mStatus = view.findViewById(R.id.mcskill_clients_status);
        mRecyclerView = view.findViewById(R.id.mcskill_clients_recycler);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        MinecraftAccount account = PojavProfile.getCurrentProfileContent(requireContext(), null);
        if (account == null || !account.isMcSkill) {
            mStatus.setText("Log into an mcskill account first (account switcher -> MCSkill Account).");
            return;
        }

        mStatus.setText("Loading clients...");
        loadClients(account.accessToken);
    }

    private void loadClients(String sessionId) {
        sExecutorService.execute(() -> {
            McSkillChannel channel = McSkillChannel.createDefault();
            try {
                List<ClientInfo> clients = new McSkillClients(channel.clientsStub()).getClients(sessionId);
                Tools.runOnUiThread(() -> {
                    if (clients.isEmpty()) {
                        mStatus.setText("No clients returned by the server.");
                        return;
                    }
                    mStatus.setText("");
                    mRecyclerView.setAdapter(new McSkillClientAdapter(clients, this::onClientClicked));
                });
            } catch (McSkillException e) {
                Log.e("McSkillClients", "Failed to list clients", e);
                Tools.runOnUiThread(() -> mStatus.setText("Failed to load clients: " + e.getMessage()));
            } finally {
                channel.shutdown();
            }
        });
    }

    private void onClientClicked(ClientInfo client) {
        Bundle args = new Bundle();
        args.putInt(ARG_CLIENT_ID, client.getId());
        args.putBoolean(ARG_AUTOSTART, true);
        Tools.swapFragment(requireActivity(), McSkillInstallClientFragment.class, McSkillInstallClientFragment.TAG, args);
    }
}
