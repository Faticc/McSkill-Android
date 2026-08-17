package net.kdt.pojavlaunch.mcskill.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;
import net.mcsgroup.launcher.proto.ClientInfo;

import java.util.List;

public class McSkillClientAdapter extends RecyclerView.Adapter<McSkillClientAdapter.ViewHolder> {

    public interface OnClientClickListener {
        void onClientClick(ClientInfo client);
    }

    private final List<ClientInfo> mClients;
    private final OnClientClickListener mListener;

    public McSkillClientAdapter(List<ClientInfo> clients, OnClientClickListener listener) {
        mClients = clients;
        mListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mcskill_client, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ClientInfo client = mClients.get(position);
        holder.title.setText(client.getTitle());
        holder.online.setText(client.getOnline() + " online");
        String fightMode = client.getFightMode().name().replace("FIGHT_MODE_", "").replace("_UNSPECIFIED", "");
        holder.subtitle.setText(client.getVersion() + " - " + fightMode + " - id " + client.getId());
        holder.itemView.setOnClickListener(v -> mListener.onClientClick(client));
    }

    @Override
    public int getItemCount() {
        return mClients.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView online;
        final TextView subtitle;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.mcskill_item_title);
            online = itemView.findViewById(R.id.mcskill_item_online);
            subtitle = itemView.findViewById(R.id.mcskill_item_subtitle);
        }
    }
}
