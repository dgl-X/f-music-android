package org.familymusic.client;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

final class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.Holder> {
    interface Listener {
        void play(Track track, int position);
        void toggleLike(Track track, int position);
        void toggleOffline(Track track, int position);
        void managePlaylists(Track track, int position);
        void selectionChanged(int count);
    }

    private final Listener listener;
    private final ImageLoader images;
    private final OfflineStore offline;
    private final List<Track> tracks = new ArrayList<>();
    private final Set<String> downloading = new HashSet<>();
    private final Set<String> selected = new HashSet<>();
    private boolean selectionMode;

    TrackAdapter(Listener listener, ImageLoader images, OfflineStore offline) { this.listener = listener; this.images = images; this.offline = offline; }
    void setTracks(List<Track> value) {
        tracks.clear();
        Set<String> ids = new HashSet<>();
        for (Track track : value) if (ids.add(track.id)) tracks.add(track);
        notifyDataSetChanged();
    }
    void appendTracks(List<Track> value) {
        int start = tracks.size();
        Set<String> ids = new HashSet<>();
        for (Track track : tracks) ids.add(track.id);
        for (Track track : value) if (ids.add(track.id)) tracks.add(track);
        int inserted = tracks.size() - start;
        if (inserted > 0) notifyItemRangeInserted(start, inserted);
    }
    List<Track> tracks() { return tracks; }
    void setDownloading(String id, boolean value) { if (value) downloading.add(id); else downloading.remove(id); notifyDataSetChanged(); }
    void setSelectionMode(boolean value) { selectionMode = value; if (!value) selected.clear(); notifyDataSetChanged(); listener.selectionChanged(selected.size()); }
    boolean selectionMode() { return selectionMode; }
    List<Track> selectedTracks() { List<Track> result = new ArrayList<>(); for (Track track : tracks) if (selected.contains(track.id)) result.add(track); return result; }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(parent, 12), dp(parent, 6), dp(parent, 4), dp(parent, 6));
        ImageView cover = new ImageView(parent.getContext());
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBackground = new GradientDrawable();
        coverBackground.setColor(Color.rgb(38, 42, 52)); coverBackground.setCornerRadius(dp(parent, 12));
        cover.setBackground(coverBackground); cover.setClipToOutline(true);
        row.addView(cover, new LinearLayout.LayoutParams(dp(parent, 54), dp(parent, 54)));
        LinearLayout labels = new LinearLayout(parent.getContext());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(parent, 12), 0, dp(parent, 4), 0);
        TextView title = text(parent, "", 16, Color.rgb(247, 247, 250));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView artist = text(parent, "", 12, Color.rgb(167, 171, 182));
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title);
        labels.addView(artist);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout actions = new LinearLayout(parent.getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        TextView download = text(parent, "↓", 20, Color.rgb(167, 171, 182));
        download.setGravity(Gravity.CENTER);
        download.setContentDescription("Скачать трек");
        actions.addView(download, new LinearLayout.LayoutParams(dp(parent, 36), dp(parent, 54)));
        TextView like = text(parent, "♡", 25, Color.rgb(255, 77, 115));
        like.setGravity(Gravity.CENTER);
        like.setContentDescription("Добавить в Мне нравится");
        LinearLayout.LayoutParams likeLayout = new LinearLayout.LayoutParams(dp(parent, 44), dp(parent, 54));
        actions.addView(like, likeLayout);
        TextView more = text(parent, "⋮", 23, Color.rgb(145, 149, 160)); more.setGravity(Gravity.CENTER); more.setContentDescription("Действия с треком"); actions.addView(more, new LinearLayout.LayoutParams(dp(parent, 36), dp(parent, 54)));
        row.addView(actions, new LinearLayout.LayoutParams(dp(parent, 116), dp(parent, 54)));
        return new Holder(row, cover, title, artist, download, like, more);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        Track track = tracks.get(position);
        holder.title.setText(track.title);
        String details = track.album.isEmpty() ? track.artist : track.artist + " · " + track.album;
        if (track.playCount > 0) details += " · " + track.playCount + " просл.";
        if (track.remote && !track.sourceLabel.isEmpty()) details += " · " + track.sourceLabel;
        if (!track.streamAvailable) details += " · недоступна";
        holder.artist.setText(details);
        holder.like.setText(track.liked ? "♥" : "♡");
        holder.like.setContentDescription(track.liked ? "Убрать из Мне нравится" : "Добавить в Мне нравится");
        holder.download.setText(downloading.contains(track.id) ? "…" : offline.contains(track.id) ? "✓" : "↓");
        String localCover = offline.cover(track.id);
        images.load(localCover.isEmpty() ? track.coverUrl : localCover, holder.cover);
        holder.itemView.setBackgroundColor(selected.contains(track.id) ? Color.rgb(72, 38, 54) : Color.TRANSPARENT);
        holder.itemView.setAlpha(track.streamAvailable ? 1f : .55f);
        holder.actions.setVisibility(selectionMode ? View.INVISIBLE : View.VISIBLE);
        holder.itemView.setOnClickListener(view -> { if (selectionMode) toggleSelected(track.id); else listener.play(track, holder.getBindingAdapterPosition()); });
        holder.itemView.setOnLongClickListener(view -> { if (!selectionMode) { selectionMode = true; toggleSelected(track.id); } else toggleSelected(track.id); return true; });
        holder.download.setEnabled(track.streamAvailable);
        holder.download.setOnClickListener(view -> { if(track.streamAvailable)listener.toggleOffline(track, holder.getBindingAdapterPosition()); });
        holder.like.setOnClickListener(view -> listener.toggleLike(track, holder.getBindingAdapterPosition()));
        holder.more.setOnClickListener(view -> listener.managePlaylists(track, holder.getBindingAdapterPosition()));
    }

    @Override public int getItemCount() { return tracks.size(); }

    private void toggleSelected(String id) { if (!selected.add(id)) selected.remove(id); notifyDataSetChanged(); listener.selectionChanged(selected.size()); }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title, artist, download, like, more;
        final View actions;
        Holder(View item, ImageView cover, TextView title, TextView artist, TextView download, TextView like, TextView more) {
            super(item); this.cover = cover; this.title = title; this.artist = artist; this.download = download; this.like = like; this.more = more; this.actions = (View) like.getParent();
        }
    }

    private static TextView text(ViewGroup parent, String value, int sp, int color) {
        TextView view = new TextView(parent.getContext());
        view.setText(value); view.setTextSize(sp); view.setTextColor(color);
        return view;
    }
    private static int dp(ViewGroup parent, int value) { return Math.round(value * parent.getResources().getDisplayMetrics().density); }
}
