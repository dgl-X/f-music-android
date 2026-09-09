package org.familymusic.client;

import android.Manifest;
import android.app.Dialog;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

@UnstableApi
public final class MainActivity extends AppCompatActivity implements TrackAdapter.Listener {
    private ApiClient api;
    private ImageLoader images;
    private OfflineStore offline;
    private AppSettings settings;
    private TrackAdapter adapter;
    private TextView status;
    private TextView nowPlaying;
    private TextView nowPlayingArtist;
    private TextView pageTitle;
    private TextView selectionTitle;
    private Button sectionBack;
    private Button selectButton;
    private LinearLayout selectionBar;
    private ImageButton playPause;
    private View likedButton;
    private View allButton;
    private View downloadedButton;
    private View historyButton;
    private View playlistsButton;
    private ImageView nowCover;
    private Dialog playerDialog;
    private Dialog settingsDialog;
    private Dialog queueDialog;
    private Dialog playlistDialog;
    private ImageView fullCover;
    private TextView fullTitle;
    private TextView fullArtist;
    private TextView fullQuality;
    private TextView fullTime;
    private ImageButton fullPlayPause;
    private ImageButton fullShuffle;
    private ImageButton fullRepeat;
    private ImageButton fullLike;
    private SeekBar fullSeek;
    private boolean likedOnly = true;
    private boolean downloadedOnly = false;
    private boolean playlistMode = false;
    private boolean historyMode = false;
    private boolean initialTabApplied;
    private String activePlaylistId = "";
    private String activePlaylistTitle = "";
    private String queueSource = "Очередь";
    private String searchQuery = "";
    private RecyclerView trackList;
    private int trackOffset;
    private int trackTotal;
    private boolean trackHasMore;
    private boolean trackLoading;
    private int trackLoadGeneration;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Handler stateHandler = new Handler(Looper.getMainLooper());
    private boolean stateRestored;
    private final Runnable periodicStateSave = new Runnable() {
        @Override public void run() { savePlaybackState(); stateHandler.postDelayed(this, 10000); }
    };
    private final Runnable progressUpdate = new Runnable() {
        @Override public void run() {
            if (playerDialog != null && playerDialog.isShowing() && controller != null && fullSeek != null) {
                long rawDuration = effectiveDuration();
                long duration = rawDuration == androidx.media3.common.C.TIME_UNSET ? 0 : Math.max(0, rawDuration);
                if (duration <= 0) {
                    Track track = currentTrack();
                    if (track != null && track.durationSeconds > 0) duration = Math.round(track.durationSeconds * 1000);
                }
                long position = Math.max(0, controller.getCurrentPosition());
                long buffered = Math.max(position, controller.getBufferedPosition());
                if (duration > 0) buffered = Math.min(duration, buffered);
                fullSeek.setMax((int) Math.min(Integer.MAX_VALUE, duration));
                fullSeek.setProgress((int) Math.min(Integer.MAX_VALUE, position));
                fullSeek.setSecondaryProgress((int) Math.min(Integer.MAX_VALUE, buffered));
                fullSeek.setEnabled(duration > 0);
                fullTime.setText(formatTime(position) + "   ·   " + (duration > 0 ? formatTime(duration) : "—:—"));
                progressHandler.postDelayed(this, 500);
            }
        }
    };
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private ActivityResultLauncher<String[]> audioPicker;
    private ActivityResultLauncher<String> coverPicker;
    private Track pendingCoverTrack;
    private boolean uploadReceiverRegistered;
    private final BroadcastReceiver uploadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(UploadService.EXTRA_MESSAGE);
            if (message != null) { if (status != null) status.setText(message); else toast(message); }
            if (intent.getBooleanExtra(UploadService.EXTRA_DONE, false) && adapter != null && !downloadedOnly) loadTracks();
        }
    };
    private final Map<String, Track> playbackTracks = new HashMap<>();
    private final Set<String> downloadsInProgress = new HashSet<>();
    private String lastPlayingId = "";
    private String lastPlaybackErrorMediaId = "";
    private long lastPlaybackErrorAt;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        api = new ApiClient(this);
        images = new ImageLoader(this);
        offline = new OfflineStore(this);
        settings = new AppSettings(this);
        audioPicker = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
            if (uris.isEmpty()) return;
            for (Uri uri : uris) {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                UploadService.enqueue(this, uri);
            }
            toast(uris.size() == 1 ? "Файл добавлен в загрузку" : "Файлов в очереди: " + uris.size());
        });
        coverPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null && pendingCoverTrack != null) uploadCover(pendingCoverTrack, uri);
        });
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 50);
        }
        if (api.hasSession()) {
            UploadService.resume(this);
            if (offline.hasTracks()) {
                downloadedOnly = true; likedOnly = false; showLibrary();
                api.get("/me", new UiCallback() {
                    @Override void ok(JSONObject json) { downloadedOnly = false; likedOnly = true; showLibrary(); }
                    @Override void fail(String message) { /* Offline library is already usable. */ }
                });
            }
            else verifySession();
        } else showLogin();
    }

    private void verifySession() {
        api.get("/me", new UiCallback() {
            @Override void ok(JSONObject json) { showLibrary(); }
            @Override void fail(String message) {
                if (offline.hasTracks()) { downloadedOnly = true; likedOnly = false; showLibrary(); }
                else { api.clearSession(); showLogin(); }
            }
        });
    }

    private void showLogin() {
        disconnectController();
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        GradientDrawable backdrop = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(37, 19, 31), Color.rgb(14, 16, 20), Color.rgb(14, 16, 20)});
        root.setBackground(backdrop);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout screen = new LinearLayout(this); screen.setOrientation(LinearLayout.VERTICAL); screen.setGravity(Gravity.CENTER);
        screen.setPadding(dp(24), dp(36), dp(24), dp(36)); scroll.addView(screen, new ScrollView.LayoutParams(-1, -1)); root.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        TextView logo = label("♫", 37, Color.WHITE); logo.setGravity(Gravity.CENTER); logo.setBackground(round(Color.rgb(255, 77, 115), 25)); logo.setElevation(dp(10));
        TextView title = label("Family Music", 32, Color.WHITE); title.setTypeface(null, android.graphics.Typeface.BOLD); title.setGravity(Gravity.CENTER);
        TextView subtitle = label("Ваша музыка. Ваши правила.", 15, Color.rgb(180, 183, 193)); subtitle.setGravity(Gravity.CENTER);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(20), dp(22), dp(20), dp(20)); card.setBackground(round(Color.rgb(25, 28, 35), 22)); card.setElevation(dp(8));
        TextView welcome = label("С возвращением", 21, Color.WHITE); welcome.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView prompt = label("Войдите, чтобы продолжить слушать", 13, Color.rgb(153, 157, 169));
        TextView serverLabel = label("Сервер", 12, Color.rgb(204, 206, 214)); serverLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        EditText server = input("music.example.com", false); server.setTextSize(15); server.setSingleLine(true);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(api.origin()); server.setSelection(server.length());
        server.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        TextView usernameLabel = label("Логин", 12, Color.rgb(204, 206, 214)); usernameLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        EditText username = input("Ваш логин", false); username.setTextSize(15); username.setSingleLine(true); username.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        TextView passwordLabel = label("Пароль", 12, Color.rgb(204, 206, 214)); passwordLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        EditText password = input("Ваш пароль", true); password.setTextSize(15); password.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        Button login = button("Войти  →"); login.setTextSize(16); login.setTypeface(null, android.graphics.Typeface.BOLD); login.setElevation(dp(5));
        TextView error = label("", 14, Color.rgb(255, 100, 120));
        error.setGravity(Gravity.CENTER);
        TextView secure = label("●  защищённое соединение", 11, Color.rgb(115, 190, 148)); secure.setGravity(Gravity.CENTER);
        screen.addView(logo, new LinearLayout.LayoutParams(dp(78), dp(78)));
        screen.addView(title, margin(-1, dp(44), 0, 16, 0, 0));
        screen.addView(subtitle, margin(-1, -2, 0, 0, 0, 30));
        card.addView(welcome);
        card.addView(prompt, margin(-1, -2, 0, 4, 0, 22));
        card.addView(serverLabel, margin(-1, -2, 2, 0, 0, 7));
        card.addView(server, new LinearLayout.LayoutParams(-1, dp(56)));
        card.addView(usernameLabel, margin(-1, -2, 2, 0, 0, 7));
        card.addView(username, margin(-1, dp(56), 0, 16, 0, 0));
        card.addView(passwordLabel, margin(-1, -2, 2, 16, 0, 7));
        card.addView(password, new LinearLayout.LayoutParams(-1, dp(56)));
        card.addView(login, margin(-1, dp(54), 0, 22, 0, 0));
        card.addView(error, margin(-1, -2, 0, 10, 0, 0));
        screen.addView(card, new LinearLayout.LayoutParams(-1, -2));
        screen.addView(secure, margin(-1, dp(40), 0, 18, 0, 0));
        login.setOnClickListener(view -> {
            String loginValue = username.getText().toString().trim();
            if (loginValue.isEmpty() || password.getText().length() == 0) { error.setText("Введите логин и пароль"); return; }
            try {
                api.setOrigin(server.getText().toString());
                server.setText(api.origin());
                images = new ImageLoader(this);
                offline = new OfflineStore(this);
            } catch (Exception invalid) { error.setText(invalid.getMessage()); return; }
            login.setEnabled(false); login.setText("Входим…"); error.setText("");
            JSONObject body = new JSONObject();
            try {
                body.put("username", loginValue);
                body.put("password", password.getText().toString());
                body.put("device_name", Build.MANUFACTURER + " " + Build.MODEL);
                body.put("client_name", "Family Music Android " + appVersion());
            } catch (Exception ignored) {}
            api.post("/login", body, new UiCallback() {
                @Override void ok(JSONObject json) { showLibrary(); }
                @Override void fail(String message) { login.setEnabled(true); login.setText("Войти  →"); error.setText(message); }
            });
        });
        server.setOnEditorActionListener((view, action, event) -> { if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { username.requestFocus(); return true; } return false; });
        password.setOnEditorActionListener((view, action, event) -> { if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { login.performClick(); return true; } return false; });
        setContentView(root);
        applySystemInsets(root);
    }

    private void showLibrary() {
        disconnectController();
        if (!initialTabApplied && !downloadedOnly) {
            String tab = settings.startTab(); if (tab.equals("last")) tab = settings.lastTab();
            likedOnly = tab.equals("liked"); downloadedOnly = tab.equals("downloaded"); historyMode = tab.equals("history");
            initialTabApplied = true;
        }
        LinearLayout screen = column();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(8), dp(8), dp(8));
        sectionBack = smallButton("‹"); sectionBack.setTextSize(26); sectionBack.setVisibility(View.GONE);
        header.addView(sectionBack, new LinearLayout.LayoutParams(dp(44), dp(46)));
        pageTitle = label("Мне нравится", 20, Color.WHITE);
        pageTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        pageTitle.setSingleLine(true);
        pageTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(pageTitle, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button explore = smallButton("♫");explore.setTextSize(20);explore.setContentDescription("Для вас, исполнители и альбомы");header.addView(explore,margin(dp(48),dp(46),6,0,0,0));
        selectButton = smallButton("☑");
        selectButton.setTextSize(18);
        header.addView(selectButton, margin(dp(48), dp(46), 6, 0, 0, 0));
        Button upload = smallButton("＋");
        upload.setTextSize(20);
        header.addView(upload, margin(dp(48), dp(46), 6, 0, 0, 0));
        Button settingsButton = smallButton("⚙");
        settingsButton.setTextSize(20);
        header.addView(settingsButton, margin(dp(48), dp(46), 6, 0, 0, 0));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(5), dp(3), dp(5), dp(3)); tabs.setBackgroundColor(Color.rgb(20, 23, 29));
        likedButton = navItem(R.drawable.ic_player_heart_filled, "Нравится");
        allButton = navItem(R.drawable.ic_nav_tracks, "Треки");
        playlistsButton = navItem(R.drawable.ic_nav_playlist, "Плейлисты");
        downloadedButton = navItem(R.drawable.ic_nav_download, "Скачано");
        historyButton = navItem(R.drawable.ic_nav_history, "История");
        tabs.addView(likedButton, new LinearLayout.LayoutParams(0, dp(58), 1));
        tabs.addView(allButton, new LinearLayout.LayoutParams(0, dp(58), 1));
        tabs.addView(playlistsButton, new LinearLayout.LayoutParams(0, dp(58), 1));
        tabs.addView(downloadedButton, new LinearLayout.LayoutParams(0, dp(58), 1));
        tabs.addView(historyButton, new LinearLayout.LayoutParams(0, dp(58), 1));
        EditText search = input("Поиск по трекам, артистам и альбомам", false);
        search.setSingleLine(true);
        search.setTextSize(14);
        status = label("Загрузка…", 14, Color.rgb(167, 171, 182));
        status.setPadding(dp(16), dp(4), dp(16), dp(8));
        selectionBar = new LinearLayout(this);
        selectionBar.setGravity(Gravity.CENTER_VERTICAL);
        selectionBar.setPadding(dp(10), dp(5), dp(8), dp(5));
        selectionBar.setBackground(round(Color.rgb(25, 28, 35), 14));
        selectionTitle = label("Выбрано: 0", 14, Color.WHITE);
        selectionTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        selectionBar.addView(selectionTitle, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button bulkPlaylist = smallButton("☷"); bulkPlaylist.setContentDescription("Добавить в плейлист");
        Button bulkDownload = smallButton("⇩"); bulkDownload.setContentDescription("Скачать");
        Button bulkDelete = smallButton("⌫"); bulkDelete.setContentDescription("Удалить"); bulkDelete.setTextColor(Color.rgb(255, 100, 120));
        Button bulkCancel = smallButton("✕"); bulkCancel.setContentDescription("Отменить выбор");
        selectionBar.addView(bulkPlaylist, new LinearLayout.LayoutParams(dp(45), dp(46)));
        selectionBar.addView(bulkDownload, new LinearLayout.LayoutParams(dp(45), dp(46)));
        selectionBar.addView(bulkDelete, new LinearLayout.LayoutParams(dp(45), dp(46)));
        selectionBar.addView(bulkCancel, new LinearLayout.LayoutParams(dp(45), dp(46)));
        selectionBar.setVisibility(View.GONE);
        RecyclerView list = new RecyclerView(this); trackList = list;
        LinearLayoutManager listLayout = new LinearLayoutManager(this); list.setLayoutManager(listLayout);
        adapter = new TrackAdapter(this, images, offline);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && trackHasMore && !trackLoading && listLayout.findLastVisibleItemPosition() >= adapter.getItemCount() - 18) loadTrackPage(false);
            }
        });
        screen.addView(header);
        screen.addView(search, margin(-1, dp(46), 12, 0, 12, 6));
        screen.addView(status);
        screen.addView(selectionBar, margin(-1, dp(56), 10, 0, 10, 5));
        screen.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        screen.addView(playerBar(), new LinearLayout.LayoutParams(-1, dp(80)));
        screen.addView(tabs, new LinearLayout.LayoutParams(-1, dp(64)));
        setContentView(screen);
        applySystemInsets(screen);
        settingsButton.setOnClickListener(view -> showSettings());
        explore.setOnClickListener(view -> showCatalogBrowser());
        selectButton.setOnClickListener(view -> adapter.setSelectionMode(!adapter.selectionMode()));
        bulkCancel.setOnClickListener(view -> adapter.setSelectionMode(false));
        bulkPlaylist.setOnClickListener(view -> bulkAddToPlaylist(adapter.selectedTracks()));
        bulkDownload.setOnClickListener(view -> bulkDownload(adapter.selectedTracks()));
        bulkDelete.setOnClickListener(view -> confirmBulkDelete(adapter.selectedTracks()));
        sectionBack.setOnClickListener(view -> showPlaylistChooser());
        likedButton.setOnClickListener(view -> { settings.putString("last_tab", "liked"); historyMode = false; playlistMode = false; downloadedOnly = false; likedOnly = true; updateTabs(); loadTracks(); });
        allButton.setOnClickListener(view -> { settings.putString("last_tab", "all"); historyMode = false; playlistMode = false; downloadedOnly = false; likedOnly = false; updateTabs(); loadTracks(); });
        downloadedButton.setOnClickListener(view -> { settings.putString("last_tab", "downloaded"); historyMode = false; playlistMode = false; downloadedOnly = true; likedOnly = false; updateTabs(); loadTracks(); });
        historyButton.setOnClickListener(view -> { settings.putString("last_tab", "history"); historyMode = true; playlistMode = false; downloadedOnly = false; likedOnly = false; updateTabs(); loadTracks(); });
        playlistsButton.setOnClickListener(view -> showPlaylistChooser());
        playlistsButton.setOnLongClickListener(view -> { if (playlistMode) manageActivePlaylist(); else showPlaylistChooser(); return true; });
        upload.setOnClickListener(view -> audioPicker.launch(new String[]{"audio/*"}));
        upload.setOnLongClickListener(view -> { showUploadHistory(); return true; });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                searchQuery = value.toString().trim();
                searchHandler.removeCallbacksAndMessages(null);
                searchHandler.postDelayed(() -> loadTracks(), 350);
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        updateTabs(); connectController(); loadTracks();
    }

    private void showRecommendations() {
        api.get("/recommendations", new UiCallback() {
            @Override void ok(JSONObject json) {
                Dialog dialog = new Dialog(MainActivity.this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                LinearLayout screen = column(); screen.setPadding(dp(14), dp(8), dp(14), dp(18));
                LinearLayout top = new LinearLayout(MainActivity.this); top.setGravity(Gravity.CENTER_VERTICAL);
                Button close = smallButton("‹"); close.setTextSize(28);
                TextView heading = label("Для вас", 22, Color.WHITE); heading.setTypeface(null, android.graphics.Typeface.BOLD); heading.setGravity(Gravity.CENTER);
                top.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52))); top.addView(heading, new LinearLayout.LayoutParams(0, dp(52), 1)); top.addView(new View(MainActivity.this), new LinearLayout.LayoutParams(dp(52), dp(52))); screen.addView(top);
                ScrollView scroll = new ScrollView(MainActivity.this); LinearLayout content = column(); content.setPadding(0, dp(8), 0, dp(20)); scroll.addView(content);
                JSONArray sections = json.optJSONArray("sections");
                if (sections == null || sections.length() == 0) { TextView empty = label("Послушайте несколько треков и поставьте сердечки — здесь появятся персональные подборки.", 16, Color.rgb(167,171,182)); empty.setGravity(Gravity.CENTER); content.addView(empty, new LinearLayout.LayoutParams(-1, dp(220))); }
                else for (int sectionIndex = 0; sectionIndex < sections.length(); sectionIndex++) {
                    JSONObject section = sections.optJSONObject(sectionIndex); JSONArray source = section.optJSONArray("items"); List<Track> tracks = new ArrayList<>();
                    if (source != null) for (int i = 0; i < source.length(); i++) tracks.add(new Track(source.optJSONObject(i)));
                    LinearLayout sectionHead = new LinearLayout(MainActivity.this); sectionHead.setGravity(Gravity.CENTER_VERTICAL); LinearLayout sectionText = column();
                    TextView title = label(section.optString("title"), 19, Color.WHITE); title.setTypeface(null, android.graphics.Typeface.BOLD); TextView subtitle = label(section.optString("subtitle"), 12, Color.rgb(167,171,182));
                    sectionText.addView(title); sectionText.addView(subtitle, margin(-1,-2,0,3,0,0)); sectionHead.addView(sectionText,new LinearLayout.LayoutParams(0,-2,1)); Button playAll=smallButton("▶  Слушать"); sectionHead.addView(playAll,new LinearLayout.LayoutParams(dp(112),dp(44))); content.addView(sectionHead,margin(-1,-2,2,sectionIndex==0?4:14,2,8));
                    HorizontalScrollView horizontal = new HorizontalScrollView(MainActivity.this); horizontal.setHorizontalScrollBarEnabled(false); LinearLayout row = new LinearLayout(MainActivity.this); row.setOrientation(LinearLayout.HORIZONTAL); horizontal.addView(row);
                    String sourceName = "Для вас · " + section.optString("title");
                    for (int trackIndex=0;trackIndex<tracks.size();trackIndex++) { int selectedIndex=trackIndex; Track track=tracks.get(trackIndex); LinearLayout card=column(); card.setPadding(dp(5),dp(5),dp(5),dp(8)); card.setBackground(round(Color.rgb(25,28,35),15)); ImageView cover=new ImageView(MainActivity.this); cover.setScaleType(ImageView.ScaleType.CENTER_CROP); cover.setImageResource(R.drawable.ic_music_note); cover.setBackground(round(Color.rgb(38,42,52),13)); cover.setClipToOutline(true); if(!track.coverUrl.isEmpty())images.load(track.coverUrl,cover); TextView name=label(track.title,14,Color.WHITE); name.setTypeface(null,android.graphics.Typeface.BOLD); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); TextView artist=label(track.artist,12,Color.rgb(167,171,182)); artist.setSingleLine(true); artist.setEllipsize(android.text.TextUtils.TruncateAt.END); card.addView(cover,new LinearLayout.LayoutParams(dp(128),dp(128))); card.addView(name,margin(dp(128),dp(24),2,8,2,0)); card.addView(artist,margin(dp(128),dp(22),2,0,2,0)); card.setOnClickListener(v->{if(controller==null){toast("Плеер ещё подключается");return;}dialog.dismiss();startQueue(tracks,selectedIndex,track,true,sourceName);}); row.addView(card,margin(dp(138),dp(192),0,0,10,0)); }
                    playAll.setOnClickListener(v->{if(tracks.isEmpty())return;if(controller==null){toast("Плеер ещё подключается");return;}dialog.dismiss();startQueue(tracks,0,tracks.get(0),true,sourceName);}); content.addView(horizontal,new LinearLayout.LayoutParams(-1,dp(198)));
                }
                screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); close.setOnClickListener(v->dialog.dismiss()); dialog.setContentView(screen); dialog.show(); if(dialog.getWindow()!=null){dialog.getWindow().setLayout(-1,-1);dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);} applySystemInsets(screen);
            }
            @Override void fail(String message) { toast(message); }
        });
    }

    private void showCatalogBrowser(){
        api.get("/catalog",new UiCallback(){@Override void ok(JSONObject json){
            Dialog dialog=new Dialog(MainActivity.this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout screen=column();screen.setPadding(dp(14),dp(8),dp(14),dp(18));LinearLayout top=new LinearLayout(MainActivity.this);top.setGravity(Gravity.CENTER_VERTICAL);Button close=smallButton("‹");close.setTextSize(28);TextView heading=label("Медиатека",22,Color.WHITE);heading.setTypeface(null,android.graphics.Typeface.BOLD);heading.setGravity(Gravity.CENTER);Button forYou=smallButton("✦");forYou.setContentDescription("Для вас");top.addView(close,new LinearLayout.LayoutParams(dp(52),dp(52)));top.addView(heading,new LinearLayout.LayoutParams(0,dp(52),1));top.addView(forYou,new LinearLayout.LayoutParams(dp(52),dp(52)));screen.addView(top);ScrollView scroll=new ScrollView(MainActivity.this);LinearLayout content=column();scroll.addView(content);screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            JSONArray artists=json.optJSONArray("artists"),albums=json.optJSONArray("albums");content.addView(sectionTitle("ИСПОЛНИТЕЛИ"));if(artists!=null)for(int i=0;i<artists.length();i++)addCatalogRow(content,artists.optJSONObject(i),false,dialog);content.addView(sectionTitle("АЛЬБОМЫ"));if(albums!=null)for(int i=0;i<albums.length();i++)addCatalogRow(content,albums.optJSONObject(i),true,dialog);
            close.setOnClickListener(v->dialog.dismiss());forYou.setOnClickListener(v->{dialog.dismiss();showRecommendations();});dialog.setContentView(screen);dialog.show();if(dialog.getWindow()!=null){dialog.getWindow().setLayout(-1,-1);dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);}applySystemInsets(screen);
        }@Override void fail(String message){toast(message);}});
    }

    private void addCatalogRow(LinearLayout content,JSONObject item,boolean album,Dialog parent){
        if(item==null)return;LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);ImageView cover=new ImageView(this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setImageResource(R.drawable.ic_music_note);cover.setBackground(round(Color.rgb(38,42,52),11));cover.setClipToOutline(true);String image=item.optString("image_url"),coverId=item.optString("cover_track_id");if(!image.isEmpty())images.load(image,cover);else if(!coverId.isEmpty())images.load("/api/v1/tracks/"+coverId+"/cover",cover);LinearLayout text=column();TextView name=label(item.optString("name"),16,Color.WHITE);name.setTypeface(null,android.graphics.Typeface.BOLD);TextView meta=label((album?item.optString("artist")+" · ":"")+item.optInt("track_count")+" треков"+(item.optInt("year")>0?" · "+item.optInt("year"):""),12,Color.rgb(167,171,182));text.addView(name);text.addView(meta);row.addView(cover,new LinearLayout.LayoutParams(dp(58),dp(58)));LinearLayout.LayoutParams textParams=new LinearLayout.LayoutParams(0,dp(66),1);textParams.setMargins(dp(12),0,0,0);row.addView(text,textParams);row.setBackground(round(Color.rgb(25,28,35),13));row.setOnClickListener(v->{parent.dismiss();showCollectionPage(item.optString("artist",item.optString("name")),album?item.optString("name"):"",album?0:item.optLong("id"));});content.addView(row,margin(-1,dp(70),0,0,0,8));
    }

    private void showCollectionPage(String artist,String album){showCollectionPage(artist,album,0);}
    private void showCollectionPage(String artist,String album,long artistId){
        String path="/tracks?queue=1&limit=10000&sort="+(album.isEmpty()?"title":"album")+"&artist="+Uri.encode(artist)+(album.isEmpty()?"":"&album="+Uri.encode(album));api.get(path,new UiCallback(){@Override void ok(JSONObject json){
            List<Track> tracks=tracksFrom(json);Dialog dialog=new Dialog(MainActivity.this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout screen=column();screen.setPadding(dp(14),dp(8),dp(14),dp(18));LinearLayout top=new LinearLayout(MainActivity.this);top.setGravity(Gravity.CENTER_VERTICAL);Button close=smallButton("‹");close.setTextSize(28);TextView heading=label(album.isEmpty()?artist:album,21,Color.WHITE);heading.setTypeface(null,android.graphics.Typeface.BOLD);heading.setGravity(Gravity.CENTER);top.addView(close,new LinearLayout.LayoutParams(dp(52),dp(52)));top.addView(heading,new LinearLayout.LayoutParams(0,dp(52),1));top.addView(new View(MainActivity.this),new LinearLayout.LayoutParams(dp(52),dp(52)));screen.addView(top);
            LinearLayout hero=new LinearLayout(MainActivity.this);hero.setGravity(Gravity.CENTER_VERTICAL);ImageView art=new ImageView(MainActivity.this);art.setScaleType(ImageView.ScaleType.CENTER_CROP);art.setImageResource(R.drawable.ic_music_note);art.setBackground(round(Color.rgb(38,42,52),14));art.setClipToOutline(true);for(Track track:tracks)if(!track.coverUrl.isEmpty()){images.load(track.coverUrl,art);break;}LinearLayout info=column();TextView title=label(album.isEmpty()?artist:album,22,Color.WHITE);title.setTypeface(null,android.graphics.Typeface.BOLD);long seconds=0;for(Track track:tracks)seconds+=(long)track.durationSeconds;TextView meta=label((album.isEmpty()?"Исполнитель":artist)+" · "+tracks.size()+" треков · "+Math.max(1,seconds/60)+" мин.",13,Color.rgb(167,171,182));LinearLayout actions=new LinearLayout(MainActivity.this);Button play=button("▶ Слушать"),shuffle=smallButton("Перемешать");actions.addView(play,new LinearLayout.LayoutParams(0,dp(46),1));actions.addView(shuffle,margin(dp(125),dp(46),8,0,0,0));info.addView(title);info.addView(meta,margin(-1,-2,0,5,0,8));info.addView(actions);hero.addView(art,new LinearLayout.LayoutParams(dp(112),dp(112)));LinearLayout.LayoutParams infoParams=new LinearLayout.LayoutParams(0,-2,1);infoParams.setMargins(dp(14),0,0,0);hero.addView(info,infoParams);screen.addView(hero,margin(-1,-2,0,8,0,12));
            ScrollView scroll=new ScrollView(MainActivity.this);LinearLayout list=column();scroll.addView(list);for(int i=0;i<tracks.size();i++){int position=i;Track track=tracks.get(i);LinearLayout row=new LinearLayout(MainActivity.this);row.setGravity(Gravity.CENTER_VERTICAL);TextView number=label(String.valueOf(i+1),13,Color.rgb(140,144,155));number.setGravity(Gravity.CENTER);LinearLayout names=column();TextView trackTitle=label(track.title,15,Color.WHITE);trackTitle.setSingleLine(true);trackTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);TextView detail=label(track.artist+(track.year==null?"":" · "+track.year),12,Color.rgb(167,171,182));names.addView(trackTitle);names.addView(detail);Button like=smallButton(track.liked?"♥":"♡"),download=smallButton(offline.contains(track.id)?"✓":"↓");like.setOnClickListener(v->{boolean next=!track.liked;setTrackLiked(track,next,-1);like.setText(next?"♥":"♡");});download.setOnClickListener(v->{if(offline.contains(track.id)){offline.remove(track.id);download.setText("↓");}else{downloadTrack(track,true);download.setText("…");}});row.addView(number,new LinearLayout.LayoutParams(dp(34),dp(54)));row.addView(names,new LinearLayout.LayoutParams(0,dp(54),1));row.addView(like,new LinearLayout.LayoutParams(dp(44),dp(44)));row.addView(download,new LinearLayout.LayoutParams(dp(44),dp(44)));row.setOnClickListener(v->{dialog.dismiss();startQueue(tracks,position,track,true,(album.isEmpty()?"Исполнитель · ":"Альбом · ")+(album.isEmpty()?artist:album));});list.addView(row);}
            screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));play.setOnClickListener(v->{if(tracks.isEmpty())return;dialog.dismiss();startQueue(tracks,0,tracks.get(0),true,album.isEmpty()?artist:album);});shuffle.setOnClickListener(v->{if(tracks.isEmpty())return;java.util.Collections.shuffle(tracks);dialog.dismiss();startQueue(tracks,0,tracks.get(0),true,"Перемешано · "+(album.isEmpty()?artist:album));});close.setOnClickListener(v->{dialog.dismiss();showCatalogBrowser();});dialog.setContentView(screen);dialog.show();if(dialog.getWindow()!=null){dialog.getWindow().setLayout(-1,-1);dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);}applySystemInsets(screen);
            if(album.isEmpty()&&artistId>0)api.get("/artists/"+artistId,new UiCallback(){@Override void ok(JSONObject card){String image=card.optString("image_url");if(!image.isEmpty())images.load(image,art);meta.setText(card.optInt("track_count")+" треков · "+card.optInt("album_count")+" альбомов · "+card.optInt("featured_count")+" feat. · "+card.optLong("play_count")+" просл.");String bio=card.optString("bio");if(!bio.isEmpty())info.addView(label(bio,13,Color.rgb(196,199,208)),2,margin(-1,-2,0,0,0,9));JSONArray albums=card.optJSONArray("albums");if(albums!=null&&albums.length()>0){HorizontalScrollView horizontal=new HorizontalScrollView(MainActivity.this);horizontal.setHorizontalScrollBarEnabled(false);LinearLayout albumRow=new LinearLayout(MainActivity.this);albumRow.setOrientation(LinearLayout.HORIZONTAL);for(int i=0;i<albums.length();i++){JSONObject item=albums.optJSONObject(i);Button albumButton=smallButton(item.optString("name")+" · "+item.optInt("track_count"));albumButton.setOnClickListener(v->{dialog.dismiss();showCollectionPage(artist,item.optString("name"),artistId);});albumRow.addView(albumButton,margin(-2,dp(42),0,0,8,0));}horizontal.addView(albumRow);screen.addView(horizontal,2,new LinearLayout.LayoutParams(-1,dp(50)));}}@Override void fail(String message){}});
        }@Override void fail(String message){toast(message);}});
    }

    private View playerBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        bar.setBackgroundColor(Color.rgb(25, 28, 35));
        nowCover = new ImageView(this);
        nowCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        nowCover.setImageResource(R.drawable.ic_music_note);
        nowCover.setBackground(round(Color.rgb(38, 42, 52), 9));
        nowCover.setClipToOutline(true);
        bar.addView(nowCover, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout nowPlayingText = new LinearLayout(this);
        nowPlayingText.setOrientation(LinearLayout.VERTICAL);
        nowPlayingText.setGravity(Gravity.CENTER_VERTICAL);
        nowPlayingText.setPadding(dp(11), 0, dp(6), 0);
        nowPlaying = label("Выберите трек", 14, Color.WHITE);
        nowPlaying.setTypeface(null, android.graphics.Typeface.BOLD);
        nowPlaying.setSingleLine(true);
        nowPlaying.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowPlayingArtist = label("", 12, Color.rgb(167, 171, 182));
        nowPlayingArtist.setSingleLine(true);
        nowPlayingArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowPlayingText.addView(nowPlaying, new LinearLayout.LayoutParams(-1, dp(23)));
        nowPlayingText.addView(nowPlayingArtist, new LinearLayout.LayoutParams(-1, dp(21)));
        bar.addView(nowPlayingText, new LinearLayout.LayoutParams(0, dp(54), 1));
        ImageButton previous = iconButton(R.drawable.ic_player_previous, "Предыдущий трек", Color.TRANSPARENT);
        playPause = iconButton(R.drawable.ic_player_play, "Воспроизвести", Color.rgb(255, 77, 115));
        ImageButton next = iconButton(R.drawable.ic_player_next, "Следующий трек", Color.TRANSPARENT);
        bar.addView(previous, new LinearLayout.LayoutParams(dp(42), dp(48)));
        bar.addView(playPause, margin(dp(52), dp(52), 5, 0, 5, 0));
        bar.addView(next, new LinearLayout.LayoutParams(dp(42), dp(48)));
        previous.setOnClickListener(view -> { if (controller != null) controller.seekToPreviousMediaItem(); });
        playPause.setOnClickListener(view -> togglePlayback());
        next.setOnClickListener(view -> skipToNext());
        bar.setOnClickListener(view -> openFullPlayer());
        return bar;
    }

    private void showSettings() {
        if (settingsDialog != null) settingsDialog.dismiss();
        settingsDialog = new Dialog(this);
        settingsDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout screen = column(); screen.setPadding(dp(16), dp(8), dp(16), dp(18));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        Button close = smallButton("‹"); close.setTextSize(28);
        TextView title = label("Настройки", 22, Color.WHITE); title.setTypeface(null, android.graphics.Typeface.BOLD); title.setGravity(Gravity.CENTER);
        top.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52))); top.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1)); top.addView(new View(this), new LinearLayout.LayoutParams(dp(52), dp(52)));
        screen.addView(top);
        ScrollView scroll = new ScrollView(this); LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(0, dp(10), 0, dp(14)); scroll.addView(content);
        addSection(content, "ЗВУК И КАЧЕСТВО");
        addSetting(content, "По Wi‑Fi", qualityName(settings.wifiQuality()), () -> openSetting(() -> chooseQuality(0)));
        addSetting(content, "Через мобильную сеть", qualityName(settings.mobileQuality()), () -> openSetting(() -> chooseQuality(1)));
        addSetting(content, "Скачивание", qualityName(settings.downloadQuality()), () -> openSetting(() -> chooseQuality(2)));
        addToggle(content, "Выравнивание громкости", "ReplayGain, оригиналы не изменяются", settings.loudnessNormalization(), value -> {
            settings.putBoolean("loudness_normalization", value);
            Intent intent = new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_NORMALIZATION_CHANGED);
            startService(intent);
        });
        addSection(content, "КЭШ И ЗАГРУЗКИ");
        addToggle(content, "Автосохранение лайков", "Хранить понравившиеся без интернета", settings.autoSaveLikes(), value -> settings.putBoolean("auto_save_likes", value));
        addSetting(content, "Когда сохранять", settings.autoSaveMode().equals("immediately") ? "Сразу после лайка" : "После прослушивания", () -> openSetting(this::chooseAutoSaveMode));
        addToggle(content, "Только по Wi‑Fi", "Для автоматических сохранений", settings.autoSaveWifiOnly(), value -> settings.putBoolean("auto_save_wifi_only", value));
        addSetting(content, "Размер кэша", sizeName(settings.cacheBytes()), () -> openSetting(this::chooseCacheSize));
        addSetting(content, "Предзагрузка", sizeName(settings.prefetchBytes()), () -> openSetting(this::choosePrefetchSize));
        addSetting(content, "Скачать «Мне нравится»", likedDownloadSummary(), () -> openSetting(this::confirmDownloadLiked));
        addSetting(content, "Загрузки и офлайн", downloadsInProgress.size() + " активных · " + offline.all().size() + " сохранено", () -> openSetting(this::showDownloadManager));
        addSetting(content, "Хранилище и очистка", humanBytes(PlaybackCache.get(this).sizeBytes() + offline.sizeBytes()), () -> openSetting(this::showStorageSettings));
        addSection(content, "ИНТЕРФЕЙС И ВОСПРОИЗВЕДЕНИЕ");
        addSetting(content, "Стартовый раздел", startTabName(settings.startTab()), () -> openSetting(this::chooseStartTab));
        addSetting(content, "Таймер сна", sleepTimerName(), () -> openSetting(this::chooseSleepTimer));
        addSection(content, "АККАУНТ");
        addSetting(content, "Устройства", "Активные входы и управление сессиями", () -> openSetting(this::showSessions));
        addSetting(content, "О приложении и диагностика", "Версия и локальные данные", () -> openSetting(this::showDiagnostics));
        addSetting(content, "Сообщить об ошибке", "Описание и журнал плеера", () -> openSetting(this::showProblemReport));
        Button logout = menuRow("Выйти из аккаунта"); logout.setTextColor(Color.rgb(255, 100, 120)); logout.setOnClickListener(v -> openSetting(this::confirmLogout)); content.addView(logout, margin(-1, dp(58), 0, 0, 0, 8));
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        close.setOnClickListener(view -> settingsDialog.dismiss());
        settingsDialog.setContentView(screen); settingsDialog.setOnDismissListener(d -> settingsDialog = null); settingsDialog.show();
        if (settingsDialog.getWindow() != null) { settingsDialog.getWindow().setLayout(-1, -1); settingsDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent); }
        applySystemInsets(screen);
    }

    private void openSetting(Runnable action) { if (settingsDialog != null) settingsDialog.dismiss(); action.run(); }

    private void showSessions() {
        Dialog dialog = new Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout screen = column(); screen.setPadding(dp(16), dp(8), dp(16), dp(18));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        Button close = smallButton("‹"); close.setTextSize(28);
        TextView heading = label("Устройства", 21, Color.WHITE); heading.setTypeface(null, android.graphics.Typeface.BOLD); heading.setGravity(Gravity.CENTER);
        top.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52))); top.addView(heading, new LinearLayout.LayoutParams(0, dp(52), 1)); top.addView(new View(this), new LinearLayout.LayoutParams(dp(52), dp(52))); screen.addView(top);
        ScrollView scroll = new ScrollView(this); LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(0, dp(12), 0, dp(12)); scroll.addView(content); screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView loading = label("Загрузка активных входов…", 14, Color.rgb(167, 171, 182)); loading.setGravity(Gravity.CENTER); content.addView(loading, new LinearLayout.LayoutParams(-1, dp(70)));
        close.setOnClickListener(v -> { dialog.dismiss(); showSettings(); }); dialog.setOnCancelListener(d -> showSettings());
        dialog.setContentView(screen); dialog.show(); if (dialog.getWindow() != null) { dialog.getWindow().setLayout(-1, -1); dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent); } applySystemInsets(screen);
        api.get("/sessions", new UiCallback() {
            @Override void ok(JSONObject json) {
                content.removeAllViews(); JSONArray items = json.optJSONArray("items"); int count = items == null ? 0 : items.length();
                TextView note = label(count + " " + sessionCountWord(count) + ". IP показывается только владельцу аккаунта.", 13, Color.rgb(167, 171, 182)); note.setPadding(dp(4), 0, dp(4), dp(14)); content.addView(note);
                for (int i = 0; i < count; i++) {
                    JSONObject item = items.optJSONObject(i); if (item == null) continue;
                    boolean current = item.optBoolean("current"); String id = item.optString("id");
                    LinearLayout card = new LinearLayout(MainActivity.this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(13), dp(12), dp(12)); card.setBackground(round(current ? Color.rgb(48, 35, 45) : Color.rgb(25, 28, 35), 14));
                    TextView name = label((current ? "●  " : "") + item.optString("device_name", "Неизвестное устройство"), 16, Color.WHITE); name.setTypeface(null, android.graphics.Typeface.BOLD); card.addView(name, new LinearLayout.LayoutParams(-1, dp(28)));
                    card.addView(label(item.optString("client_name", "Клиент") + (current ? " · это устройство" : ""), 12, current ? Color.rgb(255, 125, 153) : Color.rgb(177, 181, 191)), new LinearLayout.LayoutParams(-1, dp(24)));
                    card.addView(label("Активность: " + formatServerTime(item.optString("last_seen_at")), 12, Color.rgb(150, 154, 166)), new LinearLayout.LayoutParams(-1, dp(22)));
                    String ip = item.optString("ip_address"); if (!ip.isEmpty()) card.addView(label("IP: " + ip, 12, Color.rgb(150, 154, 166)), new LinearLayout.LayoutParams(-1, dp(22)));
                    Button revoke = smallButton(current ? "Выйти на этом устройстве" : "Завершить сессию"); revoke.setTextColor(Color.rgb(255, 112, 132));
                    revoke.setOnClickListener(v -> new AlertDialog.Builder(MainActivity.this).setTitle(current ? "Выйти из аккаунта?" : "Завершить эту сессию?").setMessage(item.optString("device_name")).setNegativeButton("Отмена", null).setPositiveButton("Завершить", (d, w) -> api.delete("/sessions/" + id, new UiCallback() {
                        @Override void ok(JSONObject result) { if (current) { api.clearSession(); stopService(new Intent(MainActivity.this, PlaybackService.class)); dialog.dismiss(); showLogin(); } else { dialog.dismiss(); showSessions(); } }
                        @Override void fail(String message) { toast(message); }
                    })).show());
                    card.addView(revoke, margin(-1, dp(46), 0, 8, 0, 0)); content.addView(card, margin(-1, -2, 0, 0, 0, 10));
                }
                if (count > 1) {
                    Button others = menuRow("Завершить все остальные сессии"); others.setTextColor(Color.rgb(255, 112, 132));
                    others.setOnClickListener(v -> new AlertDialog.Builder(MainActivity.this).setTitle("Завершить остальные сессии?").setMessage("Текущий вход останется активным.").setNegativeButton("Отмена", null).setPositiveButton("Завершить", (d, w) -> api.delete("/sessions/others", new UiCallback() {
                        @Override void ok(JSONObject result) { toast("Остальные сессии завершены"); dialog.dismiss(); showSessions(); }
                        @Override void fail(String message) { toast(message); }
                    })).show()); content.addView(others, margin(-1, dp(58), 0, 10, 0, 0));
                }
            }
            @Override void fail(String message) { loading.setText(message); }
        });
    }

    private String sessionCountWord(int count) { int mod100=count%100,mod10=count%10; return mod100>=11&&mod100<=14?"активных входов":mod10==1?"активный вход":mod10>=2&&mod10<=4?"активных входа":"активных входов"; }
    private String formatServerTime(String value) { if (value == null || value.isEmpty()) return "неизвестно"; String text=value.replace('T',' '); return text.length() >= 16 ? text.substring(0,16) : text; }
    private String appVersion() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { return ""; } }

    private void chooseQuality(int target) {
        String[] names = {"Автоматически", "Оригинал", "Высокое · AAC 192", "Экономия · AAC 96"};
        String[] values = {"auto", "original", "high", "compact"};
        String current = target == 0 ? settings.wifiQuality() : target == 1 ? settings.mobileQuality() : settings.downloadQuality();
        showChoiceScreen("Качество аудио", names, java.util.Arrays.asList(values).indexOf(current), index -> {
            settings.putString(target == 0 ? "wifi_quality" : target == 1 ? "mobile_quality" : "download_quality", values[index]);
            showSettings();
        });
    }

    private void chooseCacheSize() {
        String[] names = {"512 МБ", "1 ГБ", "2 ГБ", "5 ГБ", "10 ГБ"};
        long mb = 1024L * 1024L;
        long[] sizes = {512 * mb, 1024 * mb, 2048 * mb, 5120 * mb, 10240 * mb};
        int selected = 0; for (int i = 0; i < sizes.length; i++) if (sizes[i] == settings.cacheBytes()) selected = i;
        showChoiceScreen("Размер кэша", names, selected, i -> { settings.putLong("cache_bytes", sizes[i]); toast("Новый лимит полностью применится после перезапуска приложения"); showSettings(); });
    }

    private void chooseAutoSaveMode() {
        showChoiceScreen("Когда сохранять", new String[]{"После полного прослушивания", "Сразу после нажатия сердечка"}, settings.autoSaveMode().equals("immediately") ? 1 : 0, i -> { settings.putString("auto_save_mode", i == 0 ? "after_listen" : "immediately"); showSettings(); });
    }

    private void choosePrefetchSize() {
        String[] names = {"Выключена", "1 МБ", "3 МБ", "5 МБ"}; long mb = 1024L * 1024L;
        long[] sizes = {0, mb, 3 * mb, 5 * mb};
        int selected = 0; for (int i = 0; i < sizes.length; i++) if (sizes[i] == settings.prefetchBytes()) selected = i;
        showChoiceScreen("Предзагрузка", names, selected, i -> { settings.putLong("prefetch_bytes", sizes[i]); showSettings(); });
    }

    private void chooseStartTab() {
        String[] names = {"Мне нравится", "Все треки", "Скачано", "Последний раздел"};
        String[] values = {"liked", "all", "downloaded", "last"};
        showChoiceScreen("Стартовый раздел", names, java.util.Arrays.asList(values).indexOf(settings.startTab()), i -> { settings.putString("start_tab", values[i]); showSettings(); });
    }

    private void chooseSleepTimer() {
        String[] names = {"Выключить таймер", "15 минут", "30 минут", "45 минут", "60 минут", "90 минут"};
        int[] minutes = {0, 15, 30, 45, 60, 90};
        showChoiceScreen("Таймер сна", names, -1, i -> {
            Intent intent = new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_SLEEP_TIMER).putExtra(PlaybackService.EXTRA_SLEEP_MINUTES, minutes[i]);
            startService(intent); toast(minutes[i] == 0 ? "Таймер выключен" : "Музыка остановится через " + minutes[i] + " минут"); showSettings();
        });
    }

    private void showStorageSettings() {
        long cache = PlaybackCache.get(this).sizeBytes(), saved = offline.sizeBytes();
        String[] actions = {"Очистить временный кэш · " + humanBytes(cache), "Удалить скачанные треки · " + humanBytes(saved)};
        showChoiceScreen("Хранилище · " + humanBytes(cache + saved), actions, -1, i -> {
            if (i == 0) new AlertDialog.Builder(this).setTitle("Очистить временный кэш?").setMessage("Сохранённые офлайн-треки останутся.").setNegativeButton("Отмена", (x, y) -> showSettings()).setPositiveButton("Очистить", (x, y) -> { PlaybackCache.get(this).clear(); toast("Кэш очищен"); showSettings(); }).show();
            else new AlertDialog.Builder(this).setTitle("Удалить все скачанные треки?").setMessage("Серверная библиотека и лайки останутся.").setNegativeButton("Отмена", (x, y) -> showSettings()).setPositiveButton("Удалить", (x, y) -> { offline.clearAll(); if (downloadedOnly) loadTracks(); toast("Скачанные треки удалены"); showSettings(); }).show();
        });
    }

    private void showDownloadManager() {
        Dialog dialog=new Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout screen=column();screen.setPadding(dp(16),dp(8),dp(16),dp(18));LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        Button close=smallButton("‹");close.setTextSize(28);TextView heading=label("Загрузки и офлайн",21,Color.WHITE);heading.setTypeface(null,android.graphics.Typeface.BOLD);heading.setGravity(Gravity.CENTER);
        top.addView(close,new LinearLayout.LayoutParams(dp(52),dp(52)));top.addView(heading,new LinearLayout.LayoutParams(0,dp(52),1));top.addView(new View(this),new LinearLayout.LayoutParams(dp(52),dp(52)));screen.addView(top);
        ScrollView scroll=new ScrollView(this);LinearLayout content=column();content.setPadding(0,dp(8),0,dp(12));scroll.addView(content);screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        close.setOnClickListener(v->{dialog.dismiss();showSettings();});dialog.setOnCancelListener(d->showSettings());dialog.setContentView(screen);dialog.show();if(dialog.getWindow()!=null){dialog.getWindow().setLayout(-1,-1);dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);}applySystemInsets(screen);
        Handler refresh=new Handler(Looper.getMainLooper());final JSONArray[] serverHistory={new JSONArray()};final Runnable[] render={null};render[0]=()->{
            if(!dialog.isShowing())return;content.removeAllViews();
            content.addView(sectionTitle("ЗАГРУЗКА НА СЕРВЕР"));SharedPreferences uploadState=getSharedPreferences("active_upload",MODE_PRIVATE);String uploadStatus=uploadState.getString("status","");
            if(uploadStatus.isEmpty()||uploadStatus.equals("done"))content.addView(managerNote(uploadState.getString("last_message","Нет активной загрузки")));
            else {String message=uploadState.getString("last_message",uploadState.getString("name","Файл"));int progress=uploadState.getInt("progress",0);content.addView(managerNote(message+" · "+progress+"%"));Button action=menuRow(uploadStatus.equals("paused")||uploadStatus.equals("failed")?"Продолжить загрузку":"Приостановить загрузку");action.setOnClickListener(v->{if(uploadStatus.equals("paused")||uploadStatus.equals("failed"))UploadService.resume(this);else startService(new Intent(this,UploadService.class).setAction(UploadService.ACTION_PAUSE));refresh.postDelayed(render[0],400);});content.addView(action,margin(-1,dp(52),0,6,0,8));}
            content.addView(sectionTitle("ПОСЛЕДНИЕ ЗАГРУЗКИ НА СЕРВЕР"));if(serverHistory[0].length()==0)content.addView(managerNote("История пока пуста"));for(int i=0;i<Math.min(10,serverHistory[0].length());i++){JSONObject item=serverHistory[0].optJSONObject(i);if(item==null)continue;String state=uploadStatus(item.optString("status")),error=item.optString("error");content.addView(managerNote(item.optString("filename")+"\n"+state+(error.isEmpty()?"":" · "+error)));}
            content.addView(sectionTitle("ВСЁ ИЗ «МНЕ НРАВИТСЯ»"));SharedPreferences likedState=getSharedPreferences("liked_download",MODE_PRIVATE);String likedMessage=likedState.getString("message","Массовое скачивание ещё не запускалось");int likedProgress=likedState.getInt("progress",0);content.addView(managerNote(likedState.getString("status","").equals("active")?likedMessage+" · "+likedProgress+"%":likedMessage));
            content.addView(sectionTitle("СКАЧИВАЕТСЯ НА УСТРОЙСТВО"));JSONArray tasks=offline.transferTasks();if(tasks.length()==0)content.addView(managerNote("Нет активных или неудачных скачиваний"));
            for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task==null)continue;JSONObject trackJson=task.optJSONObject("track");if(trackJson==null)continue;Track track=new Track(trackJson);String state=task.optString("status"),error=task.optString("error");LinearLayout row=column();row.setPadding(dp(14),dp(11),dp(14),dp(11));row.setBackground(round(Color.rgb(25,28,35),12));row.addView(label(track.artist+" — "+track.title,14,Color.WHITE));row.addView(label(state.equals("downloading")?"Скачано "+task.optInt("progress")+"%":state.equals("paused")?"Приостановлено":error.isEmpty()?state:error,12,Color.rgb(167,171,182)));Button action=smallButton(state.equals("downloading")?"Пауза":"Повторить");action.setOnClickListener(v->{if(state.equals("downloading"))offline.pause(track.id);else{downloadsInProgress.remove(track.id);downloadTrack(track,true);}refresh.postDelayed(render[0],350);});row.addView(action,margin(-1,dp(42),0,7,0,0));content.addView(row,margin(-1,-2,0,0,0,8));}
            content.addView(sectionTitle("ДОСТУПНО БЕЗ ИНТЕРНЕТА"));List<Track> saved=offline.all();if(saved.isEmpty())content.addView(managerNote("Сохранённых треков пока нет"));for(Track track:saved){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);TextView name=label(track.artist+" — "+track.title,14,Color.WHITE);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);Button remove=smallButton("Удалить");remove.setOnClickListener(v->{offline.remove(track.id);if(adapter!=null)adapter.notifyDataSetChanged();render[0].run();});row.addView(name,new LinearLayout.LayoutParams(0,dp(52),1));row.addView(remove,new LinearLayout.LayoutParams(dp(92),dp(42)));content.addView(row);}
            refresh.postDelayed(render[0],1000);
        };render[0].run();api.get("/uploads",new UiCallback(){@Override void ok(JSONObject json){JSONArray items=json.optJSONArray("items");serverHistory[0]=items==null?new JSONArray():items;render[0].run();}@Override void fail(String message){}});dialog.setOnDismissListener(d->refresh.removeCallbacks(render[0]));
    }

    private TextView sectionTitle(String text){TextView value=label(text,12,Color.rgb(145,149,160));value.setPadding(dp(4),dp(14),0,dp(8));return value;}
    private TextView managerNote(String text){TextView value=label(text,14,Color.rgb(180,184,194));value.setPadding(dp(14),dp(14),dp(14),dp(14));value.setBackground(round(Color.rgb(25,28,35),12));return value;}

    private String likedDownloadSummary() {
        SharedPreferences value=getSharedPreferences("liked_download",MODE_PRIVATE);String state=value.getString("status","");
        if(state.equals("active"))return value.getInt("progress",0)+"% · "+value.getString("message","Скачивание…");
        return value.getString("message","Сохранить всю коллекцию одной кнопкой");
    }

    private void confirmDownloadLiked() {
        new AlertDialog.Builder(this).setTitle("Скачать всё из «Мне нравится»?")
                .setMessage("Уже сохранённые треки повторно скачиваться не будут. Будет использовано качество из настроек скачивания.")
                .setNegativeButton("Отмена", (dialog, which) -> showSettings())
                .setPositiveButton("Скачать", (dialog, which) -> { LikedDownloadService.enqueue(this); toast("Скачивание понравившихся началось"); showSettings(); }).show();
    }

    private void showDiagnostics() {
        String version = "0.16";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        new AlertDialog.Builder(this).setTitle("Family Music").setMessage("Android " + version + "\nСервер: " + ApiClient.ORIGIN + "\nКэш: " + humanBytes(PlaybackCache.get(this).sizeBytes()) + "\nОфлайн: " + humanBytes(offline.sizeBytes()) + "\n\nПароли и сессионные данные здесь не отображаются.").setPositiveButton("Закрыть", null).show();
    }

    private void showProblemReport() {
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(22), dp(18), dp(22), dp(8));
        TextView note = label("Опишите, что произошло и что вы нажимали перед ошибкой. Вместе с описанием отправятся состояние плеера и последние 120 технических событий.", 14, Color.rgb(180, 184, 194));
        note.setPadding(0, 0, 0, dp(14)); content.addView(note);
        EditText description = new EditText(this); description.setHint("Например: быстро переключил пять песен, на шестой пропала перемотка…");
        description.setTextColor(Color.WHITE); description.setHintTextColor(Color.rgb(125, 130, 142)); description.setGravity(Gravity.TOP); description.setMinLines(5); description.setMaxLines(9);
        description.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        description.setBackground(round(Color.rgb(25, 28, 35), 12)); description.setPadding(dp(14), dp(12), dp(14), dp(12)); content.addView(description, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Сообщить об ошибке").setView(content)
                .setNegativeButton("Отмена", (ignored, which) -> showSettings())
                .setPositiveButton("Отправить", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String text = description.getText().toString().trim();
            if (text.isEmpty()) { description.setError("Добавьте описание"); return; }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            api.post("/reports", buildProblemReport(text), new UiCallback() {
                @Override void ok(JSONObject json) { dialog.dismiss(); toast("Отчёт №" + json.optLong("id") + " отправлен"); }
                @Override void fail(String message) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); description.setError(message); }
            });
        });
    }

    private JSONObject buildProblemReport(String description) {
        JSONObject report = new JSONObject(), details = new JSONObject();
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            report.put("description", description); report.put("app_version", version);
            report.put("device", Build.MANUFACTURER + " " + Build.MODEL); report.put("android_version", "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
            Track track = currentTrack(); JSONObject trackState = new JSONObject();
            MediaItem currentItem = controller == null ? null : controller.getCurrentMediaItem();
            if (currentItem != null) {
                trackState.put("id", currentItem.mediaId);
                trackState.put("title", String.valueOf(currentItem.mediaMetadata.title == null ? "" : currentItem.mediaMetadata.title));
                trackState.put("artist", String.valueOf(currentItem.mediaMetadata.artist == null ? "" : currentItem.mediaMetadata.artist));
            }
            if (track != null) trackState.put("server_duration_seconds", track.durationSeconds);
            else if(currentItem!=null&&currentItem.mediaMetadata.extras!=null)trackState.put("server_duration_seconds",currentItem.mediaMetadata.extras.getLong("server_duration_ms",0)/1000.0);
            details.put("track", trackState);
            JSONObject player = new JSONObject();
            if (controller != null) player.put("state", controller.getPlaybackState()).put("playing", controller.isPlaying()).put("play_when_ready", controller.getPlayWhenReady())
                    .put("position_ms", controller.getCurrentPosition()).put("duration_ms", controller.getDuration()).put("buffered_ms", controller.getBufferedPosition());
            details.put("player", player);
            JSONObject queue = new JSONObject();
            if (controller != null) queue.put("source", queueSource).put("count", controller.getMediaItemCount()).put("index", controller.getCurrentMediaItemIndex())
                    .put("shuffle", controller.getShuffleModeEnabled()).put("repeat", controller.getRepeatMode()).put("has_next", controller.hasNextMediaItem());
            details.put("queue", queue);
            details.put("network", new JSONObject().put("transport", isWifi() ? "wifi" : "mobile_or_other"));
            details.put("storage", new JSONObject().put("cache_bytes", PlaybackCache.get(this).sizeBytes()).put("offline_bytes", offline.sizeBytes()));
            details.put("events", DiagnosticLog.snapshot(this)); report.put("details", details);
        } catch (Exception error) { DiagnosticLog.add(this, "report build error=" + error.getMessage()); }
        return report;
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this).setTitle("Выйти из аккаунта?").setNegativeButton("Отмена", null).setPositiveButton("Выйти", (d, w) ->
                api.post("/logout", new JSONObject(), new UiCallback() {
                    @Override void ok(JSONObject json) { api.clearSession(); stopService(new Intent(MainActivity.this, PlaybackService.class)); showLogin(); }
                    @Override void fail(String message) { toast(message); }
                })).show();
    }

    private String qualityName(String value) { return value.equals("auto") ? "авто" : value.equals("high") ? "AAC 192" : value.equals("compact") ? "AAC 96" : "оригинал"; }
    private String sizeName(long bytes) { if (bytes == 0) return "выключена"; long mb = bytes / 1024 / 1024; return mb >= 1024 ? (mb / 1024) + " ГБ" : mb + " МБ"; }
    private String startTabName(String value) { return value.equals("all") ? "Все треки" : value.equals("downloaded") ? "Скачано" : value.equals("last") ? "Последний" : "Мне нравится"; }
    private String humanBytes(long bytes) { if (bytes >= 1024L * 1024 * 1024) return String.format(java.util.Locale.getDefault(), "%.1f ГБ", bytes / 1073741824.0); if (bytes >= 1024L * 1024) return String.format(java.util.Locale.getDefault(), "%.1f МБ", bytes / 1048576.0); return Math.max(0, bytes / 1024) + " КБ"; }
    private String sleepTimerName() { long remaining = settings.sleepDeadline() - System.currentTimeMillis(); if (remaining <= 0) return "Выключен"; return "Осталось около " + Math.max(1, (remaining + 59_999) / 60_000) + " мин"; }

    private void updateTabs() {
        setNavActive(likedButton, !historyMode && !downloadedOnly && likedOnly);
        setNavActive(allButton, !historyMode && !playlistMode && !downloadedOnly && !likedOnly);
        setNavActive(playlistsButton, playlistMode);
        setNavActive(downloadedButton, downloadedOnly);
        setNavActive(historyButton, historyMode);
        if (pageTitle != null) pageTitle.setText(playlistMode ? activePlaylistTitle : historyMode ? "История" : downloadedOnly ? "Скачано" : likedOnly ? "Мне нравится" : "Все треки");
        if (sectionBack != null) sectionBack.setVisibility(playlistMode ? View.VISIBLE : View.GONE);
    }

    private void loadTracks() {
        trackLoadGeneration++;
        trackOffset = 0; trackTotal = 0; trackHasMore = false; trackLoading = false;
        if (downloadedOnly) {
            List<Track> tracks = new ArrayList<>();
            String query = searchQuery.toLowerCase(java.util.Locale.getDefault());
            for (Track track : offline.all()) {
                String searchable = (track.title + " " + track.artist + " " + track.album).toLowerCase(java.util.Locale.getDefault());
                if (query.isEmpty() || searchable.contains(query)) tracks.add(track);
            }
            adapter.setTracks(tracks);
            trackTotal = tracks.size();
            status.setText(tracks.isEmpty() ? (query.isEmpty() ? "Скачанных треков пока нет" : "Ничего не найдено") : tracks.size() + " скачано");
            return;
        }
        if (historyMode) {
            status.setText("Загрузка истории…");
            api.get("/history", new UiCallback() {
                @Override void ok(JSONObject json) {
                    List<Track> tracks = new ArrayList<>(); JSONArray items = json.optJSONArray("items");
                    String query = searchQuery.toLowerCase(java.util.Locale.getDefault());
                    if (items != null) for (int i = 0; i < items.length(); i++) {
                        Track track = new Track(items.optJSONObject(i));
                        if (query.isEmpty() || (track.title + " " + track.artist + " " + track.album).toLowerCase(java.util.Locale.getDefault()).contains(query)) tracks.add(track);
                    }
                    adapter.setTracks(tracks); status.setText(tracks.isEmpty() ? "История пока пуста" : tracks.size() + " недавних треков");
                    trackTotal = tracks.size();
                }
                @Override void fail(String message) { status.setText(message); }
            });
            return;
        }
        adapter.setTracks(new ArrayList<>()); status.setText("Загрузка…"); loadTrackPage(true);
    }

    private String libraryQuery(String sort, boolean queue, int offset, int limit, String seed) {
        String endpoint = playlistMode ? "/tracks" : likedOnly ? "/favorites" : "/library";
        String path = endpoint + "?sort=" + sort + "&offset=" + offset + "&limit=" + limit + (queue ? "&queue=1" : "") + (playlistMode ? "&playlist_id=" + Uri.encode(activePlaylistId) : !likedOnly ? "&scope=all" : "");
        if (!searchQuery.isEmpty()) path += "&q=" + Uri.encode(searchQuery);
        if (!seed.isEmpty()) path += "&seed=" + Uri.encode(seed);
        return path;
    }

    private void loadTrackPage(boolean first) {
        if (downloadedOnly || historyMode || trackLoading || !first && !trackHasMore) return;
        final int generation = trackLoadGeneration, requestedOffset = first ? 0 : trackOffset;
        trackLoading = true;
        api.get(libraryQuery("newest", false, requestedOffset, 100, ""), new UiCallback() {
            @Override void ok(JSONObject json) {
                if (generation != trackLoadGeneration) return;
                List<Track> tracks = new ArrayList<>();
                JSONArray items = json.optJSONArray("items");
                if (items != null) for (int i = 0; i < items.length(); i++) tracks.add(new Track(items.optJSONObject(i)));
                if (first) adapter.setTracks(tracks); else adapter.appendTracks(tracks);
                trackOffset = requestedOffset + tracks.size(); trackTotal = json.optInt("total", trackOffset); trackHasMore = json.optBoolean("has_more", trackOffset < trackTotal); trackLoading = false;
                status.setText(trackTotal == 0 ? (!searchQuery.isEmpty() ? "Ничего не найдено" : playlistMode ? "В этом плейлисте пока нет треков" : likedOnly ? "Здесь появятся отмеченные сердечком треки" : "Медиатека пуста") : "Показано " + trackOffset + " из " + trackTotal);
            }
            @Override void fail(String message) {
                if (generation != trackLoadGeneration) return; trackLoading = false;
                if (offline.hasTracks()) {
                    historyMode = false; playlistMode = false; downloadedOnly = true; likedOnly = false; updateTabs(); loadTracks();
                    toast("Нет сети — открыты скачанные треки");
                } else status.setText(message);
            }
        });
    }

    @Override public void play(Track selected, int position) {
        if (controller == null) { toast("Плеер ещё подключается"); return; }
        if (!selected.streamAvailable) { toast("Сервер с этой песней временно недоступен"); return; }
        final String sourceName = currentQueueSource();
        if (!downloadedOnly && !historyMode) {
            status.setText("Готовим очередь…");
            api.get(libraryQuery("newest", true, 0, 10000, ""), new UiCallback() {
                @Override void ok(JSONObject json) {
                    List<Track> tracks = tracksFrom(json); int selectedPosition = 0;
                    for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id.equals(selected.id)) { selectedPosition = i; break; }
                    startQueue(tracks, selectedPosition, selected, true, sourceName);
                    status.setText("В очереди " + tracks.size() + " из " + json.optInt("total", tracks.size()));
                }
                @Override void fail(String message) { startQueue(new ArrayList<>(adapter.tracks()), position, selected, true, sourceName); toast("Очередь ограничена загруженными треками"); }
            });
            return;
        }
        startQueue(new ArrayList<>(adapter.tracks()), position, selected, true, sourceName);
    }

    private List<Track> tracksFrom(JSONObject json) {
        List<Track> tracks = new ArrayList<>(); JSONArray items = json.optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) tracks.add(new Track(items.optJSONObject(i)));
        return tracks;
    }

    private void startQueue(List<Track> tracks, int position, Track selected, boolean playNow, String sourceName) {
        queueSource = sourceName;
        List<MediaItem> items = new ArrayList<>();
        playbackTracks.clear();
        int playablePosition = 0;
        for (Track track : tracks) {
            if (!track.streamAvailable && !offline.contains(track.id)) continue;
            if (track.id.equals(selected.id)) playablePosition = items.size();
            playbackTracks.put(track.id, track);
            items.add(mediaItemFor(track));
        }
        if (items.isEmpty() || !playbackTracks.containsKey(selected.id)) { toast("Трек сейчас недоступен"); return; }
        lastPlayingId = selected.id;
        controller.setMediaItems(items, playablePosition, 0);
        stateRestored = true; controller.prepare(); if (playNow) controller.play(); recordHistory(selected.id); savePlaybackState(); updatePlayer();
    }

    private String currentQueueSource() {
        if (downloadedOnly) return "Скачано";
        if (historyMode) return "Недавно слушали";
        if (playlistMode) return "Плейлист · " + activePlaylistTitle;
        if (!searchQuery.isEmpty()) return "Поиск · " + searchQuery;
        return likedOnly ? "Мне нравится" : "Все треки";
    }

    @Override public void toggleLike(Track track, int position) {
        setTrackLiked(track, !track.liked, position);
    }

    private void setTrackLiked(Track track, boolean liked, int adapterPosition) {
        ApiClient.Callback callback = new UiCallback() {
            @Override void ok(JSONObject json) {
                track.liked = liked;
                Track queued = playbackTracks.get(track.id); if (queued != null) queued.liked = liked;
                if (likedOnly && !liked) loadTracks();
                else if (adapter != null && adapterPosition >= 0 && adapterPosition < adapter.tracks().size()) adapter.notifyItemChanged(adapterPosition);
                else if (adapter != null) adapter.notifyDataSetChanged();
                updateFullPlayer();
                if (liked && track.remote) { toast("Песня импортируется на ваш сервер"); stateHandler.postDelayed(() -> loadTracks(), 3000); }
                else if (liked && settings.autoSaveLikes() && settings.autoSaveMode().equals("immediately") && (!settings.autoSaveWifiOnly() || isWifi())) downloadTrack(track, false);
            }
            @Override void fail(String message) { toast(message); updateFullPlayer(); }
        };
        String path = track.remote ? "/federation/like?ref=" + Uri.encode(track.remoteRef) : "/tracks/" + track.id + "/like";
        if (liked) api.put(path, new JSONObject(), callback); else api.delete(path, callback);
    }

    private Track currentTrack() {
        if (controller == null || controller.getCurrentMediaItem() == null) return null;
        String mediaId = controller.getCurrentMediaItem().mediaId;
        Track current = playbackTracks.get(mediaId);
        if (current == null && adapter != null) for (Track track : adapter.tracks()) if (track.id.equals(mediaId)) { current = track; break; }
        return current;
    }

    private long effectiveDuration(){
        if(controller==null)return 0;long value=controller.getDuration();if(value!=androidx.media3.common.C.TIME_UNSET&&value>0)return value;
        MediaItem item=controller.getCurrentMediaItem();Bundle extras=item==null?null:item.mediaMetadata.extras;if(extras!=null&&extras.getLong("server_duration_ms",0)>0)return extras.getLong("server_duration_ms");
        Track track=currentTrack();return track!=null&&track.durationSeconds>0?Math.round(track.durationSeconds*1000):0;
    }

    @Override public void toggleOffline(Track track, int position) {
        if (offline.contains(track.id)) {
            new AlertDialog.Builder(this).setTitle("Удалить скачанный трек?")
                    .setMessage(track.artist + " — " + track.title)
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить", (dialog, which) -> {
                        offline.remove(track.id);
                        if (downloadedOnly) loadTracks(); else adapter.notifyItemChanged(position);
                        toast("Удалено с устройства");
                    }).show();
            return;
        }
        downloadTrack(track, true);
    }

    private void downloadTrack(Track track, boolean notifyUser) {
        if (offline.contains(track.id) || !downloadsInProgress.add(track.id)) return;
        if (adapter != null) adapter.setDownloading(track.id, true);
        if (notifyUser) toast("Скачивание началось");
        offline.download(track, api.cookie(), settings.downloadQuality(), new OfflineStore.Callback() {
            @Override public void done() { runOnUiThread(() -> {
                downloadsInProgress.remove(track.id);
                if (adapter != null) adapter.setDownloading(track.id, false);
                if (notifyUser) toast("Трек доступен без интернета");
            }); }
            @Override public void failure(String message) { runOnUiThread(() -> {
                downloadsInProgress.remove(track.id);
                if (adapter != null) adapter.setDownloading(track.id, false);
                if (notifyUser) toast(message);
            }); }
        });
    }

    private void saveListenedLikedTrack(String mediaId) {
        Track listened = playbackTracks.get(mediaId);
        if (!settings.autoSaveLikes() || settings.autoSaveWifiOnly() && !isWifi() || listened == null || listened.remote || !listened.liked || offline.contains(listened.id) || !downloadsInProgress.add(listened.id)) return;
        if (adapter != null) adapter.setDownloading(listened.id, true);
        offline.saveFromPlaybackCache(listened, api.cookie(), playbackQuality(listened, streamQuality()), new OfflineStore.Callback() {
            @Override public void done() { runOnUiThread(() -> {
                downloadsInProgress.remove(listened.id);
                if (adapter != null) adapter.setDownloading(listened.id, false);
            }); }
            @Override public void failure(String message) { runOnUiThread(() -> {
                downloadsInProgress.remove(listened.id);
                if (adapter != null) adapter.setDownloading(listened.id, false);
            }); }
        });
    }

    private void showPlaylistChooser() {
        api.get("/playlists", new UiCallback() {
            @Override void ok(JSONObject json) {
                JSONArray items = json.optJSONArray("items");
                int count = items == null ? 0 : items.length();
                if (playlistDialog != null) playlistDialog.dismiss();
                playlistDialog = new Dialog(MainActivity.this); playlistDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                LinearLayout screen = column(); screen.setPadding(dp(16), dp(8), dp(16), dp(18)); LinearLayout top = new LinearLayout(MainActivity.this); top.setGravity(Gravity.CENTER_VERTICAL);
                Button close = smallButton("‹"); close.setTextSize(28); TextView heading = label("Плейлисты", 22, Color.WHITE); heading.setTypeface(null, android.graphics.Typeface.BOLD); heading.setGravity(Gravity.CENTER); Button create = smallButton("＋"); create.setTextSize(23);
                top.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52))); top.addView(heading, new LinearLayout.LayoutParams(0, dp(52), 1)); top.addView(create, new LinearLayout.LayoutParams(dp(52), dp(52))); screen.addView(top);
                ScrollView scroll = new ScrollView(MainActivity.this); LinearLayout content = new LinearLayout(MainActivity.this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(0, dp(12), 0, dp(12)); scroll.addView(content);
                if (count == 0) { TextView empty = label("Здесь появятся ваши плейлисты\nНажмите ＋, чтобы создать первый", 16, Color.rgb(167,171,182)); empty.setGravity(Gravity.CENTER); content.addView(empty, new LinearLayout.LayoutParams(-1, dp(180))); }
                for (int i = 0; i < count; i++) {
                    JSONObject item = items.optJSONObject(i); LinearLayout card = new LinearLayout(MainActivity.this); card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(8), dp(8), dp(8), dp(8)); card.setBackground(round(Color.rgb(25,28,35), 15));
                    ImageView cover = new ImageView(MainActivity.this); cover.setScaleType(ImageView.ScaleType.CENTER_CROP); cover.setImageResource(R.drawable.ic_music_note); cover.setBackground(round(Color.rgb(38,42,52), 10)); cover.setClipToOutline(true); String coverId = item.optString("cover_track_id"); if (!coverId.isEmpty()) images.load("/api/v1/tracks/" + coverId + "/cover", cover); card.addView(cover, new LinearLayout.LayoutParams(dp(58), dp(58)));
                    LinearLayout info = new LinearLayout(MainActivity.this); info.setOrientation(LinearLayout.VERTICAL); info.setPadding(dp(12),0,dp(6),0); TextView name = label(item.optString("title"), 16, Color.WHITE); name.setTypeface(null, android.graphics.Typeface.BOLD); TextView meta = label(item.optInt("track_count") + " треков · " + formatDuration(item.optDouble("duration_seconds")), 12, Color.rgb(167,171,182)); info.addView(name, new LinearLayout.LayoutParams(-1,0,1)); info.addView(meta, new LinearLayout.LayoutParams(-1,0,1)); card.addView(info, new LinearLayout.LayoutParams(0,dp(58),1));
                    Button more = smallButton("⋮"); more.setTextSize(22); card.addView(more, new LinearLayout.LayoutParams(dp(46),dp(50))); Runnable open = () -> { activePlaylistId=item.optString("id"); activePlaylistTitle=item.optString("title"); historyMode=false; playlistMode=true; downloadedOnly=false; likedOnly=false; playlistDialog.dismiss(); updateTabs(); loadTracks(); }; card.setOnClickListener(v -> open.run()); more.setOnClickListener(v -> { activePlaylistId=item.optString("id"); activePlaylistTitle=item.optString("title"); playlistDialog.dismiss(); manageActivePlaylist(); }); content.addView(card, margin(-1,dp(74),0,0,0,9));
                }
                screen.addView(scroll, new LinearLayout.LayoutParams(-1,0,1)); close.setOnClickListener(v -> playlistDialog.dismiss()); create.setOnClickListener(v -> { playlistDialog.dismiss(); promptNewPlaylist(); }); playlistDialog.setContentView(screen); playlistDialog.setOnDismissListener(d -> playlistDialog=null); playlistDialog.show(); if (playlistDialog.getWindow()!=null) { playlistDialog.getWindow().setLayout(-1,-1); playlistDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent); } applySystemInsets(screen);
            }
            @Override void fail(String message) { toast(message); }
        });
    }

    private void promptNewPlaylist() {
        showPlaylistEditor("Новый плейлист", "", value -> {
                    if (value.isEmpty()) { toast("Введите название плейлиста"); return; }
                    JSONObject body = new JSONObject();
                    try { body.put("title", value); body.put("description", ""); } catch (Exception ignored) {}
                    api.post("/playlists", body, new UiCallback() {
                        @Override void ok(JSONObject json) {
                            activePlaylistId = json.optString("id"); activePlaylistTitle = json.optString("title", value);
                            historyMode = false; playlistMode = true; downloadedOnly = false; likedOnly = false;
                            updateTabs(); loadTracks(); toast("Плейлист создан. Удерживайте трек для добавления");
                        }
                        @Override void fail(String message) { toast(message); }
                    });
                });
    }

    private void manageActivePlaylist() {
        showChoiceScreen(activePlaylistTitle, new String[]{"Переименовать", "Удалить плейлист"}, -1, which -> {
            if (which == 0) promptRenamePlaylist(); else confirmDeletePlaylist();
        }, this::showPlaylistChooser);
    }

    private void promptRenamePlaylist() {
        showPlaylistEditor("Переименовать", activePlaylistTitle, value -> {
                    if (value.isEmpty()) return;
                    JSONObject body = new JSONObject();
                    try { body.put("title", value); body.put("description", ""); } catch (Exception ignored) {}
                    api.patch("/playlists/" + activePlaylistId, body, new UiCallback() {
                        @Override void ok(JSONObject json) { activePlaylistTitle = value; updateTabs(); showPlaylistChooser(); }
                        @Override void fail(String message) { toast(message); }
                    });
                });
    }

    private void showPlaylistEditor(String headingText, String initial, Consumer<String> saveAction) {
        Dialog dialog = new Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE); LinearLayout screen = column(); screen.setPadding(dp(20), dp(8), dp(20), dp(20));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); Button close = smallButton("‹"); close.setTextSize(28); TextView heading = label(headingText, 21, Color.WHITE); heading.setTypeface(null, android.graphics.Typeface.BOLD); heading.setGravity(Gravity.CENTER); top.addView(close, new LinearLayout.LayoutParams(dp(52),dp(52))); top.addView(heading,new LinearLayout.LayoutParams(0,dp(52),1)); top.addView(new View(this),new LinearLayout.LayoutParams(dp(52),dp(52))); screen.addView(top);
        EditText title = input("Название плейлиста", false); title.setText(initial); title.setSelectAllOnFocus(true); screen.addView(title, margin(-1,dp(54),0,dp(28),0,14)); Button save = button("Сохранить"); screen.addView(save,new LinearLayout.LayoutParams(-1,dp(52))); screen.addView(new View(this),new LinearLayout.LayoutParams(-1,0,1));
        close.setOnClickListener(v -> { dialog.dismiss(); showPlaylistChooser(); }); save.setOnClickListener(v -> { String value=title.getText().toString().trim(); if(value.isEmpty()){ toast("Введите название плейлиста"); return; } dialog.dismiss(); saveAction.accept(value); }); dialog.setOnCancelListener(d -> showPlaylistChooser()); dialog.setContentView(screen); dialog.show(); if(dialog.getWindow()!=null){dialog.getWindow().setLayout(-1,-1);dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);} applySystemInsets(screen); title.requestFocus();
    }

    private void confirmDeletePlaylist() {
        new AlertDialog.Builder(this).setTitle("Удалить плейлист?").setMessage(activePlaylistTitle + "\nМузыкальные файлы останутся в общей библиотеке.")
                .setNegativeButton("Отмена", (dialog, which) -> showPlaylistChooser()).setPositiveButton("Удалить", (dialog, which) -> api.delete("/playlists/" + activePlaylistId, new UiCallback() {
                    @Override void ok(JSONObject json) {
                        playlistMode = false; activePlaylistId = ""; activePlaylistTitle = ""; likedOnly = true;
                        updateTabs(); loadTracks(); showPlaylistChooser();
                    }
                    @Override void fail(String message) { toast(message); }
                })).show();
    }

    @Override public void managePlaylists(Track track, int position) {
        String[] actions = track.remote
                ? new String[]{"Играть следующим", "Добавить или убрать из плейлиста"}
                : new String[]{"Играть следующим", "Добавить или убрать из плейлиста", "Редактировать теги", "Изменить обложку", "Удалить из общей библиотеки"};
        showChoiceScreen(track.title, actions, -1, which -> {
            if (which == 0) playNext(track, position); else if (which == 1) choosePlaylistForTrack(track); else if (which == 2) editTrackTags(track, position); else if (which == 3) manageTrackCover(track); else confirmDeleteTrack(track);
        }, () -> {});
    }

    @Override public void selectionChanged(int count) {
        if (selectionBar == null || selectionTitle == null) return;
        boolean active = adapter != null && adapter.selectionMode();
        selectionBar.setVisibility(active ? View.VISIBLE : View.GONE);
        selectionTitle.setText("Выбрано: " + count);
        if (selectButton != null) selectButton.setText(active ? "✕" : "☑");
    }

    private void bulkDownload(List<Track> tracks) {
        if (tracks.isEmpty()) { toast("Выберите треки"); return; }
        int queued = 0;
        for (Track track : tracks) if (!offline.contains(track.id) && !downloadsInProgress.contains(track.id)) { downloadTrack(track, false); queued++; }
        adapter.setSelectionMode(false);
        toast(queued == 0 ? "Все выбранные треки уже скачаны" : "Начато скачивание: " + queued);
    }

    private void bulkAddToPlaylist(List<Track> tracks) {
        if (tracks.isEmpty()) { toast("Выберите треки"); return; }
        api.get("/playlists", new UiCallback() {
            @Override void ok(JSONObject json) {
                JSONArray items = json.optJSONArray("items");
                if (items == null || items.length() == 0) { toast("Сначала создайте плейлист"); return; }
                String[] names = new String[items.length()];
                String[] ids = new String[items.length()];
                for (int i = 0; i < items.length(); i++) { JSONObject item = items.optJSONObject(i); names[i] = item.optString("title"); ids[i] = item.optString("id"); }
                showChoiceScreen("Добавить " + tracks.size() + " треков", names, -1, which -> addTracksToPlaylist(ids[which], tracks), () -> {});
            }
            @Override void fail(String message) { toast(message); }
        });
    }

    private void addTracksToPlaylist(String playlistId, List<Track> tracks) {
        adapter.setSelectionMode(false);
        final int[] remaining = {tracks.size()}, failed = {0};
        for (Track track : tracks) {
            JSONObject body = new JSONObject(); try { body.put("track_id", track.id); } catch (Exception ignored) {}
            String path=track.remote?"/playlists/"+playlistId+"/remote?ref="+Uri.encode(track.remoteRef):"/playlists/" + playlistId + "/tracks";
            api.post(path, track.remote?new JSONObject():body, new UiCallback() {
                @Override void ok(JSONObject json) { finished(); }
                @Override void fail(String message) { failed[0]++; finished(); }
                private void finished() {
                    if (--remaining[0] == 0) toast(failed[0] == 0 ? "Треки добавлены в плейлист" : "Добавлено: " + (tracks.size() - failed[0]) + ", ошибок: " + failed[0]);
                }
            });
        }
    }

    private void confirmBulkDelete(List<Track> tracks) {
        List<Track> localTracks = new ArrayList<>(); for (Track track : tracks) if (!track.remote) localTracks.add(track);
        if (localTracks.isEmpty()) { toast("Удалять можно только локальные треки"); return; }
        new AlertDialog.Builder(this).setTitle("Удалить " + localTracks.size() + " треков?")
                .setMessage("Файлы, лайки и упоминания в плейлистах будут удалены для всех.")
                .setNegativeButton("Отмена", null).setPositiveButton("Удалить", (dialog, which) -> deleteTracks(localTracks)).show();
    }

    private void deleteTracks(List<Track> tracks) {
        adapter.setSelectionMode(false);
        final int[] remaining = {tracks.size()}, failed = {0};
        for (Track track : tracks) api.delete("/tracks/" + track.id, new UiCallback() {
            @Override void ok(JSONObject json) { offline.remove(track.id); finished(); }
            @Override void fail(String message) { failed[0]++; finished(); }
            private void finished() {
                if (--remaining[0] == 0) { loadTracks(); toast(failed[0] == 0 ? "Треки удалены" : "Удалено: " + (tracks.size() - failed[0]) + ", ошибок: " + failed[0]); }
            }
        });
    }

    private void confirmDeleteTrack(Track track) {
        new AlertDialog.Builder(this).setTitle("Удалить трек из библиотеки?").setMessage(track.artist + " — " + track.title + "\n\nФайл, лайки и упоминания в плейлистах будут удалены для всех.").setNegativeButton("Отмена", null).setPositiveButton("Удалить", (d,w) -> api.delete("/tracks/" + track.id, new UiCallback() {
            @Override void ok(JSONObject json) { offline.remove(track.id); loadTracks(); toast("Трек удалён"); }
            @Override void fail(String message) { toast(message); }
        })).show();
    }

    private MediaItem mediaItemFor(Track track) {
        playbackTracks.put(track.id, track);
        Bundle extras = new Bundle(); if (track.replayGainDb != null) extras.putDouble("replay_gain_db", track.replayGainDb);if(track.durationSeconds>0)extras.putLong("server_duration_ms",Math.round(track.durationSeconds*1000));
        MediaMetadata.Builder metadata = new MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).setExtras(extras);
        String localCover = offline.cover(track.id);
        if (!localCover.isEmpty()) metadata.setArtworkUri(Uri.parse(localCover));
        else if (!track.coverUrl.isEmpty()) metadata.setArtworkUri(Uri.parse(ApiClient.ORIGIN + track.coverUrl));
        boolean local = offline.contains(track.id);
        String requestedQuality = streamQuality(), quality = playbackQuality(track, requestedQuality);
        String prepare = quality.equals("original") && !requestedQuality.equals("original") ? "&prepare=" + Uri.encode(requestedQuality.equals("compact") ? "aac_96" : "aac_192") : "";
        String streamPath = track.streamPath(quality);
        if (!track.remote) streamPath += prepare;
        Uri source = local ? offline.uri(track.id) : Uri.parse(ApiClient.ORIGIN + streamPath);
        MediaItem.Builder item = new MediaItem.Builder().setMediaId(track.id).setUri(source).setMimeType(local ? track.mimeType : null).setMediaMetadata(metadata.build());
        if (!local) item.setCustomCacheKey(PlaybackCache.key(track.id, quality));
        return item.build();
    }

    private String streamQuality() {
        return isWifi() ? settings.wifiQuality() : settings.mobileQuality();
    }

    private String playbackQuality(Track track, String requested) {
        return QualitySelector.playback(track.aac192Ready,track.aac96Ready,requested);
    }

    private boolean isWifi() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities capabilities = manager == null ? null : manager.getNetworkCapabilities(manager.getActiveNetwork());
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private void playNext(Track track, int position) {
        if (controller == null || controller.getCurrentMediaItem() == null) { play(track, position); return; }
        controller.addMediaItem(controller.getCurrentMediaItemIndex() + 1, mediaItemFor(track));
        toast("Будет играть следующим");
    }

    private void manageTrackCover(Track track) {
        List<String> actions = new ArrayList<>(); actions.add("Выбрать из галереи");
        if (!track.coverUrl.isEmpty() || !offline.cover(track.id).isEmpty()) actions.add("Удалить обложку");
        showChoiceScreen("Обложка · " + track.title, actions.toArray(new String[0]), -1, which -> {
            if (which == 0) { pendingCoverTrack = track; coverPicker.launch("image/*"); }
            else api.delete("/tracks/" + track.id + "/cover", new UiCallback() {
                @Override void ok(JSONObject json) {
                    track.coverUrl = ""; offline.removeCover(track.id); adapter.notifyDataSetChanged(); updatePlayer(); toast("Обложка удалена");
                }
                @Override void fail(String message) { toast(message); }
            });
        }, () -> {});
    }

    private void uploadCover(Track track, Uri uri) {
        new Thread(() -> {
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new Exception("Не удалось открыть изображение");
                    byte[] buffer = new byte[64 * 1024]; int read, total = 0;
                    while ((read = input.read(buffer)) != -1) { total += read; if (total > 12 * 1024 * 1024) throw new Exception("Обложка больше 12 МиБ"); output.write(buffer, 0, read); }
                }
                byte[] bytes = output.toByteArray(); String mime = getContentResolver().getType(uri); if (mime == null) mime = "image/jpeg";
                api.putBytes("/tracks/" + track.id + "/cover", bytes, mime, new UiCallback() {
                    @Override void ok(JSONObject json) {
                        track.coverUrl = json.optString("cover_url", "/api/v1/tracks/" + track.id + "/cover?v=" + System.currentTimeMillis());
                        offline.saveCover(track.id, bytes); adapter.notifyDataSetChanged(); updatePlayer(); toast("Обложка сохранена");
                    }
                    @Override void fail(String message) { toast(message); }
                });
            } catch (Exception error) { runOnUiThread(() -> toast(error.getMessage() == null ? "Ошибка изображения" : error.getMessage())); }
        }).start();
    }

    private void recordHistory(String trackId) {
        Track track=playbackTracks.get(trackId);if(track!=null&&track.remote)return;
        JSONObject body = new JSONObject(); try { body.put("track_id", trackId); } catch (Exception ignored) {}
        api.post("/history", body, new ApiClient.Callback() {
            @Override public void success(JSONObject json) {}
            @Override public void failure(String message) {}
        });
    }

    private void choosePlaylistForTrack(Track track) {
        api.get("/playlists", new UiCallback() {
            @Override void ok(JSONObject json) {
                JSONArray items = json.optJSONArray("items");
                List<String> actions = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                if (playlistMode) { actions.add("Убрать из «" + activePlaylistTitle + "»"); ids.add(""); }
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (!playlistMode || !activePlaylistId.equals(item.optString("id"))) {
                        actions.add("Добавить в «" + item.optString("title") + "»"); ids.add(item.optString("id"));
                    }
                }
                if (actions.isEmpty()) { toast("Сначала создайте плейлист"); return; }
                showChoiceScreen("Плейлисты · " + track.title, actions.toArray(new String[0]), -1, which -> {
                    if (ids.get(which).isEmpty()) {
                        String removePath=track.remote?"/playlists/"+activePlaylistId+"/remote?ref="+Uri.encode(track.remoteRef):"/playlists/" + activePlaylistId + "/tracks/" + track.id;
                        api.delete(removePath, new UiCallback() {
                            @Override void ok(JSONObject json) { loadTracks(); }
                            @Override void fail(String message) { toast(message); }
                        });
                    } else {
                        if(track.remote){api.post("/playlists/"+ids.get(which)+"/remote?ref="+Uri.encode(track.remoteRef),new JSONObject(),new UiCallback(){@Override void ok(JSONObject json){toast("Добавлено в плейлист");}@Override void fail(String message){toast(message);}});return;}
                        JSONObject body = new JSONObject(); try { body.put("track_id", track.id); } catch (Exception ignored) {}
                        api.post("/playlists/" + ids.get(which) + "/tracks", body, new UiCallback() {
                            @Override void ok(JSONObject json) { toast("Добавлено в плейлист"); }
                            @Override void fail(String message) { toast(message); }
                        });
                    }
                }, () -> {});
            }
            @Override void fail(String message) { toast(message); }
        });
    }

    private void editTrackTags(Track track, int position) {
        Dialog dialog = new Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE); LinearLayout screen = column(); screen.setPadding(dp(18),dp(8),dp(18),dp(18)); LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); Button close=smallButton("‹");close.setTextSize(28);TextView heading=label("Метаданные",21,Color.WHITE);heading.setTypeface(null,android.graphics.Typeface.BOLD);heading.setGravity(Gravity.CENTER);top.addView(close,new LinearLayout.LayoutParams(dp(52),dp(52)));top.addView(heading,new LinearLayout.LayoutParams(0,dp(52),1));top.addView(new View(this),new LinearLayout.LayoutParams(dp(52),dp(52)));screen.addView(top);
        ScrollView scroll=new ScrollView(this); LinearLayout form = column(); form.setPadding(0, dp(16), 0, dp(14)); scroll.addView(form);
        EditText title = input("Название", false); title.setText(track.title);
        EditText artist = input("Исполнитель", false); artist.setText(track.artist);
        EditText album = input("Альбом", false); album.setText(track.album);
        EditText genre = input("Жанр", false); genre.setText(track.genre);
        EditText year = input("Год", false); year.setInputType(InputType.TYPE_CLASS_NUMBER); year.setText(track.year == null ? "" : String.valueOf(track.year));
        form.addView(title, new LinearLayout.LayoutParams(-1, dp(50)));
        form.addView(artist, margin(-1, dp(50), 0, 8, 0, 0));
        form.addView(album, margin(-1, dp(50), 0, 8, 0, 0));
        form.addView(genre, margin(-1, dp(50), 0, 8, 0, 0));
        form.addView(year, margin(-1, dp(50), 0, 8, 0, 0));
        screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); Button save=button("Сохранить");screen.addView(save,new LinearLayout.LayoutParams(-1,dp(52))); close.setOnClickListener(v->dialog.dismiss());
        save.setOnClickListener(v -> {
                    String nextTitle = title.getText().toString().trim(), nextArtist = artist.getText().toString().trim();
                    if (nextTitle.isEmpty() || nextArtist.isEmpty()) { toast("Название и исполнитель обязательны"); return; }
                    JSONObject body = new JSONObject();
                    try {
                        body.put("title", nextTitle); body.put("artist", nextArtist); body.put("album", album.getText().toString().trim());
                        body.put("genre", genre.getText().toString().trim()); body.put("year", year.getText().toString().trim());
                    } catch (Exception ignored) {}
                    api.patch("/tracks/" + track.id, body, new UiCallback() {
                        @Override void ok(JSONObject json) {
                            track.title = nextTitle; track.artist = nextArtist; track.album = album.getText().toString().trim();
                            track.genre = genre.getText().toString().trim();
                            String yearValue = year.getText().toString().trim(); track.year = yearValue.isEmpty() ? null : Integer.valueOf(yearValue);
                            offline.updateMetadata(track);
                            adapter.notifyItemChanged(position); toast("Метаданные сохранены"); dialog.dismiss();
                        }
                        @Override void fail(String message) { toast(message); }
                    });
                });
        dialog.setContentView(screen);dialog.show();if(dialog.getWindow()!=null){dialog.getWindow().setLayout(-1,-1);dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);}applySystemInsets(screen);
    }

    private void showUploadHistory() {
        api.get("/uploads", new UiCallback() {
            @Override void ok(JSONObject json) {
                JSONArray items = json.optJSONArray("items"); int count = items == null ? 0 : items.length();
                if (count == 0) { new AlertDialog.Builder(MainActivity.this).setTitle("Загрузки").setMessage("История пока пуста").setPositiveButton("Закрыть", null).show(); return; }
                String[] rows = new String[count];
                for (int i = 0; i < count; i++) {
                    JSONObject item = items.optJSONObject(i); String state = uploadStatus(item.optString("status"));
                    long total = item.optLong("total_bytes"), received = item.optLong("received_bytes");
                    int percent = total <= 0 ? 0 : (int) Math.min(100, received * 100 / total);
                    String error = item.optString("error", "");
                    rows[i] = item.optString("filename") + "\n" + state + (item.optString("status").equals("uploading") ? " · " + percent + "%" : "") + (error.isEmpty() ? "" : " · " + error);
                }
                new AlertDialog.Builder(MainActivity.this).setTitle("Последние загрузки").setItems(rows, null).setPositiveButton("Закрыть", null).show();
            }
            @Override void fail(String message) { toast(message); }
        });
    }

    private String uploadStatus(String value) {
        return switch (value) {
            case "uploading" -> "Загружается"; case "processing" -> "Обрабатывается";
            case "ready" -> "Готово"; case "duplicate" -> "Уже было в библиотеке";
            case "failed" -> "Ошибка"; default -> value;
        };
    }

    private void connectController() {
        stateRestored = false;
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                controller.addListener(new Player.Listener() {
                    @Override public void onIsPlayingChanged(boolean playing) { updatePlayer(); }
                    @Override public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !lastPlayingId.isEmpty()) saveListenedLikedTrack(lastPlayingId);
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem != null) recordHistory(mediaItem.mediaId);
                        lastPlayingId = mediaItem == null ? "" : mediaItem.mediaId;
                        lastPlaybackErrorMediaId = "";
                        updatePlayer();
                    }
                    @Override public void onPlaybackStateChanged(int state) {
                        if (state == Player.STATE_ENDED && !lastPlayingId.isEmpty()) saveListenedLikedTrack(lastPlayingId);
                    }
                    @Override public void onPlayerError(@NonNull PlaybackException error) {
                        String mediaId = controller.getCurrentMediaItem() == null ? "" : controller.getCurrentMediaItem().mediaId;
                        long now = System.currentTimeMillis();
                        if (!mediaId.equals(lastPlaybackErrorMediaId) || now - lastPlaybackErrorAt > 12_000) {
                            lastPlaybackErrorMediaId = mediaId;
                            lastPlaybackErrorAt = now;
                            toast("Не удалось загрузить трек — пробуем снова");
                        }
                    }
                    @Override public void onEvents(Player player, Player.Events events) { if (stateRestored) scheduleStateSave(); }
                });
                restorePlaybackState(); updatePlayer();
                stateHandler.removeCallbacks(periodicStateSave); stateHandler.postDelayed(periodicStateSave, 10000);
            } catch (Exception error) { toast("Не удалось подключить плеер"); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void updatePlayer() {
        runOnUiThread(() -> {
            if (controller == null || nowPlaying == null) return;
            MediaMetadata metadata = controller.getMediaMetadata();
            CharSequence title = metadata.title;
            boolean emptyTitle = title == null || title.length() == 0;
            nowPlaying.setText(emptyTitle ? "Выберите трек" : title);
            if (nowPlayingArtist != null) nowPlayingArtist.setText(emptyTitle || metadata.artist == null ? "" : metadata.artist);
            playPause.setImageResource(controller.isPlaying() ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
            playPause.setContentDescription(controller.isPlaying() ? "Пауза" : "Воспроизвести");
            String mediaId = controller.getCurrentMediaItem() == null ? "" : controller.getCurrentMediaItem().mediaId;
            Track current = adapter == null ? null : adapter.tracks().stream().filter(track -> track.id.equals(mediaId)).findFirst().orElse(null);
            if (current == null) nowCover.setImageResource(R.drawable.ic_music_note); else images.load(coverFor(current), nowCover);
            updateFullPlayer();
        });
    }

    private void restorePlaybackState() {
        if (controller == null) return;
        if (controller.getMediaItemCount() > 0) {
            stateRestored = true;
            MediaItem current = controller.getCurrentMediaItem(); lastPlayingId = current == null ? "" : current.mediaId;
            return;
        }
        api.get("/playback-state", new UiCallback() {
            @Override void ok(JSONObject saved) {
                queueSource = saved.optString("queue_source", "Очередь");
                JSONArray queue = saved.optJSONArray("queue");
                if (queue == null || queue.length() == 0) { stateRestored = true; return; }
                JSONObject resolveBody = new JSONObject(); try { resolveBody.put("ids", queue); } catch (Exception ignored) {}
                api.post("/tracks/resolve", resolveBody, new UiCallback() {
                    @Override void ok(JSONObject library) {
                        JSONArray items = library.optJSONArray("items");
                        List<MediaItem> restored = new ArrayList<>(); String currentId = saved.optString("track_id", ""); int currentIndex = 0;
                        playbackTracks.clear();
                        if (items != null) for (int i = 0; i < items.length(); i++) {
                            Track track = new Track(items.optJSONObject(i));
                            if (track.id.equals(currentId)) currentIndex = restored.size(); restored.add(mediaItemFor(track));
                        }
                        if (!restored.isEmpty() && controller != null) {
                            controller.setShuffleModeEnabled(saved.optBoolean("shuffle"));
                            String repeat = saved.optString("repeat_mode", "off");
                            controller.setRepeatMode(repeat.equals("one") ? Player.REPEAT_MODE_ONE : repeat.equals("all") ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
                            controller.setMediaItems(restored, Math.min(currentIndex, restored.size() - 1), Math.max(0, saved.optLong("position_seconds")) * 1000);
                            controller.prepare(); lastPlayingId = currentId;
                        }
                        stateRestored = true; updatePlayer();
                    }
                    @Override void fail(String message) { stateRestored = true; }
                });
            }
            @Override void fail(String message) { stateRestored = true; }
        });
    }

    private void scheduleStateSave() {
        stateHandler.removeCallbacks(stateSaveOnce);
        stateHandler.postDelayed(stateSaveOnce, 1200);
    }

    private final Runnable stateSaveOnce = this::savePlaybackState;

    private void savePlaybackState() {
        if (!stateRestored || controller == null) return;
        JSONObject body = new JSONObject(); JSONArray queue = new JSONArray();
        try {
            for (int i = 0; i < controller.getMediaItemCount(); i++) queue.put(controller.getMediaItemAt(i).mediaId);
            MediaItem current = controller.getCurrentMediaItem();
            body.put("track_id", current == null ? JSONObject.NULL : current.mediaId);
            body.put("position_seconds", Math.max(0, controller.getCurrentPosition()) / 1000.0);
            body.put("queue", queue); body.put("shuffle", controller.getShuffleModeEnabled());
            body.put("repeat_mode", controller.getRepeatMode() == Player.REPEAT_MODE_ONE ? "one" : controller.getRepeatMode() == Player.REPEAT_MODE_ALL ? "all" : "off");
            body.put("queue_source", queueSource);
        } catch (Exception ignored) { return; }
        api.put("/playback-state", body, new ApiClient.Callback() {
            @Override public void success(JSONObject json) {}
            @Override public void failure(String message) {}
        });
    }

    private void openFullPlayer() {
        if (controller == null || controller.getCurrentMediaItem() == null) { toast("Сначала выберите трек"); return; }
        playerDialog = new Dialog(this);
        playerDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout screen = column();
        screen.setPadding(dp(20), dp(8), dp(20), dp(18));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button close = smallButton("⌄");
        TextView caption = label("Сейчас играет", 15, Color.rgb(167, 171, 182));
        caption.setGravity(Gravity.CENTER);
        top.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        top.addView(caption, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button queueButton = smallButton("≡");
        queueButton.setTextSize(23);
        queueButton.setContentDescription("Открыть очередь");
        top.addView(queueButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        screen.addView(top);
        fullCover = new ImageView(this);
        fullCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        fullCover.setImageResource(R.drawable.ic_music_note);
        fullCover.setBackground(round(Color.rgb(38, 42, 52), 18));
        fullCover.setClipToOutline(true);
        int coverSize = Math.min(dp(360), getResources().getDisplayMetrics().widthPixels - dp(40));
        coverSize = Math.min(coverSize, Math.round(getResources().getDisplayMetrics().heightPixels * .36f));
        LinearLayout.LayoutParams coverLayout = new LinearLayout.LayoutParams(coverSize, coverSize);
        coverLayout.gravity = Gravity.CENTER_HORIZONTAL;
        coverLayout.setMargins(0, dp(14), 0, dp(24));
        screen.addView(fullCover, coverLayout);
        LinearLayout trackInfo = new LinearLayout(this);
        trackInfo.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout textInfo = new LinearLayout(this);
        textInfo.setOrientation(LinearLayout.VERTICAL);
        fullTitle = label("", 23, Color.WHITE);
        fullTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        fullTitle.setMaxLines(2);
        fullTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        fullTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        fullArtist = label("", 15, Color.rgb(167, 171, 182));
        fullArtist.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        fullArtist.setSingleLine(true);
        fullArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        fullQuality = label("", 12, Color.rgb(255, 77, 115)); fullQuality.setSingleLine(true);
        textInfo.addView(fullTitle, new LinearLayout.LayoutParams(-1, -2));
        textInfo.addView(fullArtist, margin(-1, dp(28), 0, 3, 0, 0));
        textInfo.addView(fullQuality, margin(-1, dp(24), 0, 1, 0, 0));
        trackInfo.addView(textInfo, new LinearLayout.LayoutParams(0, -2, 1));
        fullLike = iconButton(R.drawable.ic_player_heart, "Добавить в Мне нравится", Color.TRANSPARENT);
        trackInfo.addView(fullLike, margin(dp(54), dp(54), 12, 0, 0, 0));
        screen.addView(trackInfo, margin(-1, -2, 2, 0, 2, 12));
        fullSeek = new SeekBar(this);
        screen.addView(fullSeek, new LinearLayout.LayoutParams(-1, dp(44)));
        fullTime = label("0:00                                      0:00", 12, Color.rgb(167, 171, 182));
        fullTime.setGravity(Gravity.CENTER_VERTICAL);
        screen.addView(fullTime, new LinearLayout.LayoutParams(-1, dp(30)));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        fullShuffle = iconButton(R.drawable.ic_player_shuffle, "Перемешать", Color.TRANSPARENT);
        ImageButton previous = iconButton(R.drawable.ic_player_previous, "Предыдущий трек", Color.TRANSPARENT);
        fullPlayPause = iconButton(R.drawable.ic_player_play, "Воспроизвести", Color.rgb(255, 77, 115));
        ImageButton next = iconButton(R.drawable.ic_player_next, "Следующий трек", Color.TRANSPARENT);
        fullRepeat = iconButton(R.drawable.ic_player_repeat, "Режим повтора", Color.TRANSPARENT);
        controls.addView(fullShuffle, new LinearLayout.LayoutParams(0, dp(58), 1));
        controls.addView(previous, margin(dp(58), dp(64), 5, 0, 5, 0));
        controls.addView(fullPlayPause, margin(dp(72), dp(72), 6, 0, 6, 0));
        controls.addView(next, margin(dp(58), dp(64), 5, 0, 5, 0));
        controls.addView(fullRepeat, new LinearLayout.LayoutParams(0, dp(58), 1));
        screen.addView(controls, margin(-1, dp(80), 0, 8, 0, 0));
        close.setOnClickListener(view -> playerDialog.dismiss());
        fullLike.setOnClickListener(view -> {
            Track current = currentTrack();
            if (current == null) toast("Не удалось определить текущий трек");
            else setTrackLiked(current, !current.liked, -1);
        });
        previous.setOnClickListener(view -> controller.seekToPreviousMediaItem());
        fullPlayPause.setOnClickListener(view -> togglePlayback());
        next.setOnClickListener(view -> skipToNext());
        fullShuffle.setOnClickListener(view -> {
            if (controller.getShuffleModeEnabled()) { controller.setShuffleModeEnabled(false); updateFullPlayer(); savePlaybackState(); }
            else enableGlobalShuffle();
        });
        fullRepeat.setOnClickListener(view -> {
            int mode = controller.getRepeatMode();
            controller.setRepeatMode(mode == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ALL : mode == Player.REPEAT_MODE_ALL ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            updateFullPlayer();
        });
        queueButton.setOnClickListener(view -> showQueue());
        fullSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) { if (fromUser) fullTime.setText(formatTime(value) + "   ·   " + formatTime(effectiveDuration())); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { controller.seekTo(seekBar.getProgress()); }
        });
        playerDialog.setContentView(screen);
        playerDialog.setOnDismissListener(dialog -> { progressHandler.removeCallbacks(progressUpdate); playerDialog = null; });
        playerDialog.show();
        if (playerDialog.getWindow() != null) {
            playerDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            playerDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        applySystemInsets(screen);
        updateFullPlayer();
        progressHandler.removeCallbacks(progressUpdate);
        progressHandler.post(progressUpdate);
    }

    private void updateFullPlayer() {
        if (playerDialog == null || !playerDialog.isShowing() || controller == null || fullTitle == null) return;
        MediaMetadata metadata = controller.getMediaMetadata();
        fullTitle.setText(metadata.title == null ? "Без названия" : metadata.title);
        fullArtist.setText(metadata.artist == null ? "Неизвестный исполнитель" : metadata.artist);
        fullPlayPause.setImageResource(controller.isPlaying() ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        fullPlayPause.setContentDescription(controller.isPlaying() ? "Пауза" : "Воспроизвести");
        if (fullShuffle != null) {
            boolean enabled = controller.getShuffleModeEnabled();
            fullShuffle.setColorFilter(enabled ? Color.rgb(255, 77, 115) : Color.rgb(167, 171, 182));
            fullShuffle.setContentDescription(enabled ? "Перемешивание включено" : "Перемешивание выключено");
        }
        if (fullRepeat != null) {
            int repeatMode = controller.getRepeatMode();
            fullRepeat.setImageResource(repeatMode == Player.REPEAT_MODE_ONE ? R.drawable.ic_player_repeat_one : R.drawable.ic_player_repeat);
            fullRepeat.setColorFilter(repeatMode == Player.REPEAT_MODE_OFF ? Color.rgb(167, 171, 182) : Color.rgb(255, 77, 115));
            fullRepeat.setContentDescription(repeatMode == Player.REPEAT_MODE_ONE ? "Повтор одного трека" : repeatMode == Player.REPEAT_MODE_ALL ? "Повтор очереди" : "Повтор выключен");
        }
        Track current = currentTrack();
        if (fullQuality != null) {
            MediaItem item = controller.getCurrentMediaItem(); String quality = streamQuality(), playbackQuality = current == null ? quality : playbackQuality(current,quality), actual = item == null ? "" : PlaybackQualityTracker.variant(PlaybackCache.key(item.mediaId,playbackQuality));
            if (item != null && item.localConfiguration != null && "file".equals(item.localConfiguration.uri.getScheme())) fullQuality.setText("Офлайн-копия");
            else if (actual.equals("aac_192")) fullQuality.setText("AAC 192 кбит/с"); else if (actual.equals("aac_96")) fullQuality.setText("AAC 96 кбит/с"); else if (actual.equals("original")) fullQuality.setText("Оригинал"); else fullQuality.setText("Профиль: " + qualityName(quality));
        }
        if (fullLike != null) {
            boolean liked = current != null && current.liked;
            fullLike.setImageResource(liked ? R.drawable.ic_player_heart_filled : R.drawable.ic_player_heart);
            fullLike.setColorFilter(liked ? Color.rgb(255, 77, 115) : Color.WHITE);
            fullLike.setContentDescription(liked ? "Убрать из Мне нравится" : "Добавить в Мне нравится");
        }
        if (current != null) images.load(coverFor(current), fullCover);
        else if (metadata.artworkUri != null) {
            String artwork = metadata.artworkUri.toString();
            images.load(artwork.startsWith(ApiClient.ORIGIN) ? artwork.replace(ApiClient.ORIGIN, "") : artwork, fullCover);
        }
        else fullCover.setImageResource(R.drawable.ic_music_note);
    }

    private void enableGlobalShuffle() {
        if (controller == null || controller.getCurrentMediaItem() == null) return;
        if (downloadedOnly || historyMode) { controller.setShuffleModeEnabled(true); updateFullPlayer(); savePlaybackState(); return; }
        String currentId = controller.getCurrentMediaItem().mediaId; long position = Math.max(0, controller.getCurrentPosition()); boolean playing = controller.isPlaying();
        String seed = java.util.UUID.randomUUID().toString(); toast("Перемешиваем всю коллекцию…");
        api.get("/tracks?sort=random&queue=1&offset=0&limit=10000&seed=" + Uri.encode(seed), new UiCallback() {
            @Override void ok(JSONObject json) {
                List<Track> tracks = tracksFrom(json); int currentIndex = 0;
                for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id.equals(currentId)) { currentIndex = i; break; }
                List<MediaItem> items = new ArrayList<>(); playbackTracks.clear();
                for (Track track : tracks) { playbackTracks.put(track.id, track); items.add(mediaItemFor(track)); }
                if (items.isEmpty()) return;
                controller.setShuffleModeEnabled(false); controller.setMediaItems(items, currentIndex, position); controller.setShuffleModeEnabled(true); controller.prepare(); if (playing) controller.play();
                queueSource = "Все треки";
                updateFullPlayer(); savePlaybackState(); toast("В shuffle-очереди: " + tracks.size() + " треков");
            }
            @Override void fail(String message) { controller.setShuffleModeEnabled(true); updateFullPlayer(); toast("Перемешана текущая очередь"); }
        });
    }

    private String coverFor(Track track) {
        String local = offline.cover(track.id);
        return local.isEmpty() ? track.coverUrl : local;
    }

    private void showQueue() {
        if (controller == null || controller.getMediaItemCount() == 0) { toast("Очередь пуста"); return; }
        if (queueDialog != null) queueDialog.dismiss();
        int count = controller.getMediaItemCount();
        queueDialog = new Dialog(this); queueDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout screen = column(); screen.setPadding(dp(16), dp(8), dp(16), dp(18));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        Button close = smallButton("‹"); close.setTextSize(28);
        TextView heading = label(queueSource + " · " + count, 20, Color.WHITE); heading.setTypeface(null, android.graphics.Typeface.BOLD); heading.setGravity(Gravity.CENTER);
        top.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52))); top.addView(heading, new LinearLayout.LayoutParams(0, dp(52), 1)); top.addView(new View(this), new LinearLayout.LayoutParams(dp(52), dp(52))); screen.addView(top);
        ScrollView scroll = new ScrollView(this); LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(0, dp(10), 0, dp(10)); scroll.addView(content);
        for (int i = 0; i < count; i++) {
            MediaItem item = controller.getMediaItemAt(i); CharSequence title = item.mediaMetadata.title, artist = item.mediaMetadata.artist;
            String text = (i == controller.getCurrentMediaItemIndex() ? "▶   " : "") + (title == null ? "Без названия" : title) + (artist == null ? "" : "\n" + artist);
            Button row = menuRow(text); row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); row.setMaxLines(2); row.setTextSize(14);
            if (i == controller.getCurrentMediaItemIndex()) row.setBackground(round(Color.rgb(72, 38, 54), 14));
            int index = i; row.setOnClickListener(view -> showQueueItemActions(index)); content.addView(row, margin(-1, dp(66), 0, 0, 0, 7));
        }
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button shuffleAll = smallButton("Перемешать все треки"); screen.addView(shuffleAll, margin(-1, dp(48), 0, 0, 0, 8));
        Button keep = smallButton("Оставить только текущий трек"); screen.addView(keep, new LinearLayout.LayoutParams(-1, dp(48)));
        close.setOnClickListener(view -> queueDialog.dismiss()); shuffleAll.setOnClickListener(view -> { queueDialog.dismiss(); enableGlobalShuffle(); }); keep.setOnClickListener(view -> { keepOnlyCurrent(); showQueue(); });
        queueDialog.setContentView(screen); queueDialog.setOnDismissListener(d -> queueDialog = null); queueDialog.show();
        if (queueDialog.getWindow() != null) { queueDialog.getWindow().setLayout(-1, -1); queueDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent); }
        applySystemInsets(screen);
    }

    private void showQueueItemActions(int index) {
        List<String> actions = new ArrayList<>();
        actions.add("Играть сейчас"); actions.add("Удалить из очереди");
        if (index > 0) actions.add("Поднять выше");
        if (index < controller.getMediaItemCount() - 1) actions.add("Опустить ниже");
        new AlertDialog.Builder(this).setTitle("Трек в очереди").setItems(actions.toArray(new String[0]), (dialog, which) -> {
            String action = actions.get(which);
            if (action.equals("Играть сейчас")) { controller.seekToDefaultPosition(index); controller.play(); }
            else if (action.equals("Удалить из очереди")) { if (controller.getMediaItemCount() == 1) controller.clearMediaItems(); else controller.removeMediaItem(index); }
            else if (action.equals("Поднять выше")) controller.moveMediaItem(index, index - 1);
            else controller.moveMediaItem(index, index + 1);
            updatePlayer(); showQueue();
        }).setNegativeButton("Отмена", null).show();
    }

    private void keepOnlyCurrent() {
        if (controller == null || controller.getMediaItemCount() == 0) return;
        int current = controller.getCurrentMediaItemIndex();
        if (current + 1 < controller.getMediaItemCount()) controller.removeMediaItems(current + 1, controller.getMediaItemCount());
        if (current > 0) controller.removeMediaItems(0, current);
        queueSource = "Текущий трек";
        toast("В очереди оставлен текущий трек");
    }

    private void togglePlayback() {
        if (controller == null) return;
        DiagnosticLog.add(this, "user play/pause track=" + (controller.getCurrentMediaItem() == null ? "none" : controller.getCurrentMediaItem().mediaId) + " state=" + controller.getPlaybackState());
        if (controller.getPlayWhenReady()) controller.pause();
        else if (controller.getPlaybackState() == Player.STATE_ENDED && controller.getShuffleModeEnabled() && controller.getMediaItemCount() > 1) restartShuffleCycle(true);
        else controller.play();
        updatePlayer();
    }

    private void skipToNext() {
        if (controller == null) return;
        DiagnosticLog.add(this, "user next index=" + controller.getCurrentMediaItemIndex() + " count=" + controller.getMediaItemCount() + " hasNext=" + controller.hasNextMediaItem());
        boolean resume = controller.getPlayWhenReady();
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem();
            controller.prepare();
            if (resume) controller.play();
        } else if (controller.getShuffleModeEnabled() && controller.getMediaItemCount() > 1) restartShuffleCycle(resume);
        else controller.stop();
        updatePlayer();
    }

    private void restartShuffleCycle(boolean play) {
        int first = controller == null ? androidx.media3.common.C.INDEX_UNSET : controller.getCurrentTimeline().getFirstWindowIndex(true);
        if (first == androidx.media3.common.C.INDEX_UNSET) return;
        controller.seekToDefaultPosition(first); controller.prepare(); if (play) controller.play(); savePlaybackState();
    }

    private String formatTime(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format(java.util.Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }
    private String formatDuration(double secondsValue) { long minutes = Math.max(0, Math.round(secondsValue)) / 60; return minutes >= 60 ? (minutes / 60) + " ч " + (minutes % 60) + " мин" : minutes + " мин"; }

    private void applySystemInsets(View view) {
        int left = view.getPaddingLeft(), top = view.getPaddingTop(), right = view.getPaddingRight(), bottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (target, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            target.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    private void disconnectController() {
        savePlaybackState();
        stateHandler.removeCallbacksAndMessages(null);
        controller = null;
        if (controllerFuture != null) { MediaController.releaseFuture(controllerFuture); controllerFuture = null; }
    }

    @Override protected void onDestroy() { disconnectController(); super.onDestroy(); }

    @Override protected void onStart() {
        super.onStart();
        if (!uploadReceiverRegistered) {
            ContextCompat.registerReceiver(this, uploadReceiver, new IntentFilter(UploadService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED);
            uploadReceiverRegistered = true;
        }
    }

    @Override protected void onStop() {
        savePlaybackState();
        if (uploadReceiverRegistered) { unregisterReceiver(uploadReceiver); uploadReceiverRegistered = false; }
        super.onStop();
    }

    abstract class UiCallback implements ApiClient.Callback {
        abstract void ok(JSONObject json);
        abstract void fail(String message);
        @Override public final void success(JSONObject json) { runOnUiThread(() -> ok(json)); }
        @Override public final void failure(String message) { runOnUiThread(() -> fail(message)); }
    }

    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); view.setBackgroundColor(Color.rgb(14, 16, 20)); return view; }
    private TextView label(String text, int sp, int color) { TextView view = new TextView(this); view.setText(text); view.setTextSize(sp); view.setTextColor(color); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private EditText input(String hint, boolean password) { EditText view = new EditText(this); view.setHint(hint); view.setTextColor(Color.WHITE); view.setHintTextColor(Color.rgb(130, 134, 145)); view.setSingleLine(true); view.setBackground(round(Color.rgb(25, 28, 35), 10)); view.setPadding(dp(14), 0, dp(14), 0); if (password) view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); return view; }
    private Button button(String text) { Button view = new Button(this); view.setText(text); view.setTextColor(Color.WHITE); view.setAllCaps(false); view.setBackground(round(Color.rgb(255, 77, 115), 12)); return view; }
    private Button smallButton(String text) { Button view = new Button(this); view.setText(text); view.setTextColor(Color.WHITE); view.setTextSize(12); view.setAllCaps(false); view.setMinWidth(0); view.setMinimumWidth(0); view.setPadding(dp(10), 0, dp(10), 0); view.setBackground(round(Color.rgb(41, 45, 56), 10)); return view; }
    private Button menuRow(String text) { Button view = smallButton(text); view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); view.setTextSize(15); view.setPadding(dp(18), 0, dp(14), 0); view.setBackground(round(Color.rgb(25, 28, 35), 14)); return view; }
    private View navItem(int drawable, String text) {
        LinearLayout item = new LinearLayout(this); item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER); item.setBackground(round(Color.TRANSPARENT, 14));
        ImageView icon = new ImageView(this); icon.setImageResource(drawable); icon.setColorFilter(Color.WHITE); item.addView(icon, new LinearLayout.LayoutParams(dp(25), dp(25)));
        TextView caption = label(text, 10, Color.WHITE); caption.setGravity(Gravity.CENTER); caption.setSingleLine(true); item.addView(caption, new LinearLayout.LayoutParams(-1, dp(23)));
        return item;
    }
    private void setNavActive(View view, boolean active) {
        view.setAlpha(1f);
        view.setBackground(round(active ? Color.rgb(57, 31, 43) : Color.TRANSPARENT, 14));
        if (view instanceof LinearLayout) {
            LinearLayout item=(LinearLayout)view;
            int color=active?Color.rgb(255,77,115):Color.rgb(145,149,160);
            if(item.getChildCount()>0&&item.getChildAt(0) instanceof ImageView)((ImageView)item.getChildAt(0)).setColorFilter(color);
            if(item.getChildCount()>1&&item.getChildAt(1) instanceof TextView){TextView caption=(TextView)item.getChildAt(1);caption.setTextColor(active?Color.WHITE:Color.rgb(145,149,160));caption.setTypeface(null,active?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);}
        }
    }
    private void addSection(LinearLayout parent, String title) { TextView view = label(title, 11, Color.rgb(255, 77, 115)); view.setTypeface(null, android.graphics.Typeface.BOLD); view.setPadding(dp(6), dp(16), 0, dp(7)); parent.addView(view, new LinearLayout.LayoutParams(-1, dp(46))); }
    private void addSetting(LinearLayout parent, String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(18), dp(7), dp(42), dp(7)); row.setBackground(round(Color.rgb(25, 28, 35), 14));
        TextView primary = label(title, 15, Color.WHITE), secondary = label(subtitle + "   ›", 12, Color.rgb(167, 171, 182)); secondary.setSingleLine(true);
        row.addView(primary, new LinearLayout.LayoutParams(-1, 0, 1)); row.addView(secondary, new LinearLayout.LayoutParams(-1, 0, 1)); row.setOnClickListener(v -> action.run()); parent.addView(row, margin(-1, dp(66), 0, 0, 0, 8));
    }
    private void addToggle(LinearLayout parent, String title, String subtitle, boolean checked, Consumer<Boolean> action) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(18), dp(5), dp(10), dp(5)); row.setBackground(round(Color.rgb(25, 28, 35), 14));
        LinearLayout text = new LinearLayout(this); text.setOrientation(LinearLayout.VERTICAL); text.addView(label(title, 15, Color.WHITE), new LinearLayout.LayoutParams(-1, 0, 1)); text.addView(label(subtitle, 12, Color.rgb(167, 171, 182)), new LinearLayout.LayoutParams(-1, 0, 1)); row.addView(text, new LinearLayout.LayoutParams(0, -1, 1));
        SwitchCompat toggle = new SwitchCompat(this); toggle.setChecked(checked); toggle.setButtonTintList(null); toggle.setOnCheckedChangeListener((button, value) -> action.accept(value)); row.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(52))); row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked())); parent.addView(row, margin(-1, dp(66), 0, 0, 0, 8));
    }
    private void showChoiceScreen(String title, String[] choices, int selected, IntConsumer callback) {
        showChoiceScreen(title, choices, selected, callback, this::showSettings);
    }
    private void showChoiceScreen(String title, String[] choices, int selected, IntConsumer callback, Runnable backAction) {
        Dialog dialog = new Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE); LinearLayout screen = column(); screen.setPadding(dp(16), dp(8), dp(16), dp(18));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); Button close = smallButton("‹"); close.setTextSize(28); TextView heading = label(title, 21, Color.WHITE); heading.setTypeface(null, android.graphics.Typeface.BOLD); heading.setGravity(Gravity.CENTER); top.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52))); top.addView(heading, new LinearLayout.LayoutParams(0, dp(52), 1)); top.addView(new View(this), new LinearLayout.LayoutParams(dp(52), dp(52))); screen.addView(top);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(0, dp(12), 0, 0); for (int i = 0; i < choices.length; i++) { int index = i; Button row = menuRow((i == selected ? "✓   " : "      ") + choices[i]); if (i == selected) { row.setTextColor(Color.rgb(255, 77, 115)); row.setBackground(round(Color.rgb(72, 38, 54), 14)); } row.setOnClickListener(v -> { dialog.dismiss(); callback.accept(index); }); list.addView(row, margin(-1, dp(60), 0, 0, 0, 8)); }
        screen.addView(list, new LinearLayout.LayoutParams(-1, 0, 1)); close.setOnClickListener(v -> { dialog.dismiss(); backAction.run(); }); dialog.setOnCancelListener(d -> backAction.run()); dialog.setContentView(screen); dialog.show(); if (dialog.getWindow() != null) { dialog.getWindow().setLayout(-1, -1); dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent); } applySystemInsets(screen);
    }
    private ImageButton iconButton(int drawable, String description, int backgroundColor) {
        ImageButton view = new ImageButton(this);
        view.setImageResource(drawable);
        view.setContentDescription(description);
        view.setColorFilter(Color.WHITE);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setPadding(dp(11), dp(11), dp(11), dp(11));
        view.setBackground(backgroundColor == Color.TRANSPARENT ? round(Color.TRANSPARENT, 28) : round(backgroundColor, 38));
        view.setElevation(backgroundColor == Color.TRANSPARENT ? 0 : dp(5));
        return view;
    }
    private GradientDrawable round(int color, int radiusDp) { GradientDrawable value = new GradientDrawable(); value.setColor(color); value.setCornerRadius(dp(radiusDp)); return value; }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams margin(int width, int height, int left, int top, int right, int bottom) { return margin(width, height, left, top, right, bottom, 0); }
    private LinearLayout.LayoutParams margin(int width, int height, int left, int top, int right, int bottom, float weight) { LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(width, height, weight); value.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return value; }
}
