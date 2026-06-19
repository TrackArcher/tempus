package com.cappielloantonio.tempo.ui.adapter;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.signature.ObjectKey;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.ItemHomeInternetRadioStationBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.subsonic.models.InternetRadioStation;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.util.RadioCoverArtDownloader;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@UnstableApi
public class InternetRadioStationAdapter extends RecyclerView.Adapter<InternetRadioStationAdapter.ViewHolder> {
    private static final Object PAYLOAD_NOW_PLAYING = new Object();

    private final ClickCallback click;

    private List<InternetRadioStation> internetRadioStations;
    private Map<String, String> nowPlayingByStation = Collections.emptyMap();
    private boolean showNowPlaying;

    public InternetRadioStationAdapter(ClickCallback click) {
        this.click = click;
        this.internetRadioStations = Collections.emptyList();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemHomeInternetRadioStationBinding view = ItemHomeInternetRadioStationBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        InternetRadioStation internetRadioStation = internetRadioStations.get(position);

        holder.item.internetRadioStationTitleTextView.setText(internetRadioStation.getName());
        holder.item.internetRadioStationTitleTextView.setSelected(true);

        bindSubtitle(holder, internetRadioStation);

        if (internetRadioStation.getId() != null && internetRadioStation.getId().startsWith("local_")) {
            holder.item.internetRadioStationLocalBadge.setVisibility(android.view.View.VISIBLE);
        } else {
            holder.item.internetRadioStationLocalBadge.setVisibility(android.view.View.GONE);
        }

        loadCoverArt(holder, internetRadioStation);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
            return;
        }

        bindSubtitle(holder, internetRadioStations.get(position));
    }

    private void bindSubtitle(ViewHolder holder, InternetRadioStation internetRadioStation) {
        CharSequence subtitle;
        if (showNowPlaying) {
            String nowPlaying = nowPlayingByStation.get(internetRadioStation.getName());
            if (nowPlaying != null && !nowPlaying.isEmpty()) {
                subtitle = holder.itemView.getContext().getString(R.string.radio_station_now_playing, nowPlaying);
            } else {
                subtitle = holder.itemView.getContext().getString(
                        R.string.radio_station_now_playing,
                        holder.itemView.getContext().getString(R.string.radio_station_now_playing_unknown)
                );
            }
        } else {
            subtitle = internetRadioStation.getStreamUrl();
        }

        holder.item.internetRadioStationSubtitleTextView.setText(subtitle);
        holder.item.internetRadioStationSubtitleTextView.setSelected(false);
        holder.item.internetRadioStationSubtitleTextView.setSelected(true);
    }

    private void loadCoverArt(ViewHolder holder, InternetRadioStation station) {
        if (station.getId() != null) {
            File localCover = RadioCoverArtDownloader.getLocalCoverFile(station.getId());
            if (localCover.exists()) {
                Glide.with(holder.itemView.getContext())
                        .load(localCover)
                        .apply(CustomGlideRequest.createRequestOptions(holder.itemView.getContext(), station.getId(), CustomGlideRequest.ResourceType.Radio))
                        // Cache-bust by file mtime so an edited cover (same path) refreshes.
                        .signature(new ObjectKey(localCover.lastModified()))
                        .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade())
                        .into(holder.item.internetRadioStationCoverImageView);
                return;
            }
        }

        if (station.getCoverArt() != null && !station.getCoverArt().isEmpty()) {
            CustomGlideRequest.Builder
                    .from(holder.itemView.getContext(), station.getCoverArt(), CustomGlideRequest.ResourceType.Radio)
                    .build()
                    .into(holder.item.internetRadioStationCoverImageView);
            return;
        }

        String homePageUrl = station.getHomePageUrl();
        if (homePageUrl != null && !homePageUrl.isEmpty() && MusicUtil.isImageUrl(homePageUrl)) {
            Glide.with(holder.itemView.getContext())
                    .load(homePageUrl)
                    .apply(CustomGlideRequest.createRequestOptions(holder.itemView.getContext(), homePageUrl, CustomGlideRequest.ResourceType.Radio))
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade())
                    .into(holder.item.internetRadioStationCoverImageView);
            return;
        }

        Glide.with(holder.itemView.getContext())
                .load((String) null)
                .apply(CustomGlideRequest.createRequestOptions(holder.itemView.getContext(), null, CustomGlideRequest.ResourceType.Radio))
                .into(holder.item.internetRadioStationCoverImageView);
    }

    @Override
    public int getItemCount() {
        return internetRadioStations.size();
    }

    public void setItems(List<InternetRadioStation> internetRadioStations) {
        this.internetRadioStations = internetRadioStations;
        notifyDataSetChanged();
    }

    public void setNowPlayingTracks(Map<String, String> nowPlayingByStation, boolean showNowPlaying) {
        this.nowPlayingByStation = nowPlayingByStation != null ? nowPlayingByStation : Collections.emptyMap();
        this.showNowPlaying = showNowPlaying;
        if (getItemCount() == 0) {
            return;
        }
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_NOW_PLAYING);
    }

    public InternetRadioStation getItem(int position) {
        return internetRadioStations.get(position);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        ItemHomeInternetRadioStationBinding item;

        ViewHolder(ItemHomeInternetRadioStationBinding item) {
            super(item.getRoot());

            this.item = item;

            item.internetRadioStationTitleTextView.setSelected(true);
            item.internetRadioStationSubtitleTextView.setSelected(true);

            itemView.setOnClickListener(v -> onClick());
            itemView.setOnLongClickListener(v -> onLongClick());

            item.internetRadioStationMoreButton.setOnClickListener(v -> onLongClick());
        }

        public void onClick() {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.INTERNET_RADIO_STATION_OBJECT, internetRadioStations.get(getBindingAdapterPosition()));

            click.onInternetRadioStationClick(bundle);
        }

        private boolean onLongClick() {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.INTERNET_RADIO_STATION_OBJECT, internetRadioStations.get(getBindingAdapterPosition()));

            click.onInternetRadioStationLongClick(bundle);

            return true;
        }
    }
}
