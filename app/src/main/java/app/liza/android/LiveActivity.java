package app.liza.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ContentValues;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.content.res.Configuration;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.media.MediaScannerConnection;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.SurfaceHolder;
import android.graphics.SurfaceTexture;
import android.graphics.Matrix;
import android.graphics.Bitmap;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.videogo.errorlayer.ErrorInfo;
import com.videogo.exception.BaseException;
import com.videogo.openapi.EZConstants;
import com.videogo.openapi.EZGlobalSDK;
import com.videogo.openapi.EZPlayer;
import com.videogo.openapi.EZConstants.EZRealPlayConstants;
import com.videogo.openapi.bean.EZDeviceRecordFile;
import com.videogo.openapi.bean.EZCloudRecordFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LiveActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int BG = Color.rgb(12, 17, 25);
    private static final int PANEL = Color.rgb(23, 31, 43);
    private static final int MUTED = Color.rgb(158, 172, 191);
    private static final int ACCENT = Color.rgb(57, 197, 160);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private EZPlayer player;
    private EZPlayer livePlayer;
    private EZPlayer playbackPlayer;
    private EZPlayer talkPlayer;
    private TextureView surface;
    private FrameLayout videoFrame;
    private LinearLayout page;
    private android.widget.ScrollView screenScroll;
    private Button fullscreenButton;
    private Button qualityButton;
    private Button audioButton;
    private boolean liveAudioEnabled = true;
    private Button browseSdButton;
    private AlertDialog sdSearchDialog;
    private final Runnable sdSearchTimeout = this::onSdSearchTimeout;

    private void onSdSearchTimeout() {
        if (sdSearchDialog == null || !sdSearchDialog.isShowing()) return;
        sdSearchDialog.dismiss();
        sdSearchDialog = null;
        resetSdSearchButton();
        String message = "The camera did not respond to the SD card search. Check that it is online and try again.";
        state.setText(message);
        new AlertDialog.Builder(this).setTitle("Search timed out")
                .setMessage(message).setPositiveButton("OK", null).show();
        startLive();
    }
    private TextView state;
    private Button talkButton;
    private Button playButton;
    private Button recordButton;
    private String serial;
    private String deviceName;
    private int cameraNo;
    private int videoQuality = 2;
    private boolean encrypted;
    private boolean surfaceReady;
    private boolean playing;
    private boolean talking;
    private boolean recording;
    private boolean playbackMode;
    private boolean gotVideoSize;
    private boolean pendingTalk;
    private boolean talkStopping;
    private int pendingStorageAction;
    private boolean routeRetryUsed;
    private String recordingPath;
    private SeekBar playbackSeek;
    private LinearLayout playbackControls;
    private Button previousClipButton;
    private Button nextClipButton;
    private List<EZDeviceRecordFile> sdFiles = new ArrayList<>();
    private int selectedSdIndex = -1;
    private EZDeviceRecordFile currentSdFile;
    private boolean thumbnailCapturePending;
    private boolean thumbnailScanActive;
    private boolean thumbnailScanCaptureScheduled;
    private final List<EZDeviceRecordFile> thumbnailScanQueue = new ArrayList<>();
    private int thumbnailScanIndex;
    private final Map<String, ImageView> thumbnailViews = new HashMap<>();
    private int thumbnailScanCaptureRetries;
    private final Runnable thumbnailScanCaptureTimeout = () -> finishThumbnailScanItem();
    private Calendar sdSearchDate = Calendar.getInstance();
    private AlertDialog recordingsDialog;
    private TextView recordingsHint;
    private CheckBox autoplayNextCheckBox;
    private boolean autoAdvanceTriggered;
    private boolean autoPlayNext = true;
    private long playbackFromMillis;
    private long playbackToMillis;
    private long pendingPlaybackSeekAt = -1;
    private long alarmPlaybackAt = -1;
    private int alarmPreTime;
    private ScaleGestureDetector zoomDetector;
    private float videoZoom = 1f;
    private float videoAspectRatio = 16f / 9f;
    private int videoSourceWidth = 1920;
    private int videoSourceHeight = 1080;
    private float videoPanX;
    private float videoPanY;
    private boolean videoZoomUpdateScheduled;
    private float lastGestureX;
    private float lastGestureY;
    private int previewHeight;
    private int playbackViewHeight;
    private boolean fullscreen;
    private int fullscreenSavedSystemUi;
    private int fullscreenSavedPageLeft, fullscreenSavedPageTop, fullscreenSavedPageRight, fullscreenSavedPageBottom;
    private int fullscreenControlsIndex = -1;
    private LinearLayout.LayoutParams fullscreenControlsLayout;
    private final Runnable playbackTicker = new Runnable() {
        @Override public void run() {
            if (!playbackMode || playbackPlayer == null || playbackSeek == null) return;
            try {
                Calendar now = playbackPlayer.getOSDTime();
                Calendar start = playbackStart();
                Calendar end = playbackEnd();
                if (now != null && start != null && end != null && end.getTimeInMillis() > start.getTimeInMillis()) {
                    long total = end.getTimeInMillis() - start.getTimeInMillis();
                    long elapsed = Math.max(0, Math.min(total, now.getTimeInMillis() - start.getTimeInMillis()));
                    playbackSeek.setProgress((int) (elapsed * playbackSeek.getMax() / total));
                    if (autoPlayNext && !autoAdvanceTriggered
                            && now.getTimeInMillis() >= end.getTimeInMillis() - 300L) {
                        autoAdvanceTriggered = true;
                        playNextNewerRecording();
                        return;
                    }
                }
            } catch (Exception ignored) { }
            main.postDelayed(this, 500);
        }
    };

    private final Handler playerHandler = new Handler(Looper.getMainLooper(), this::handlePlayerMessage);
    private final Runnable firstFrameTimeout = () -> {
        if (playing && !playbackMode && !gotVideoSize) {
            retryAlternateRoute("The camera connected but sent no video frames.");
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        serial = getIntent().getStringExtra("serial");
        deviceName = getIntent().getStringExtra("name");
        cameraNo = getIntent().getIntExtra("cameraNo", 1);
        encrypted = getIntent().getBooleanExtra("encrypted", false);
        videoQuality = getSharedPreferences("liza_settings", MODE_PRIVATE)
                .getInt("quality_" + serial, getIntent().getIntExtra("videoLevel", 2));
        videoQuality = Math.max(0, Math.min(2, videoQuality));
        autoPlayNext = getSharedPreferences("liza_settings", MODE_PRIVATE).getBoolean("sd_autoplay_next", true);
        String alarmTime = getIntent().getStringExtra("alarmTime");
        alarmPlaybackAt = parseAlarmTime(alarmTime);
        alarmPreTime = getIntent().getIntExtra("alarmPreTime", 0);
        ((LizaApplication) getApplication()).setP2pEnabled(false);
        routeRetryUsed = false;
        buildScreen();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (videoFrame != null) {
            screenScroll.requestApplyInsets();
            screenScroll.post(() -> {
                if (videoFrame == null) return;
                ViewGroup.LayoutParams params = videoFrame.getLayoutParams();
                params.height = fullscreen ? fullscreenVideoHeight()
                        : (playbackMode && playbackViewHeight > 0 ? playbackViewHeight : previewHeight);
                videoFrame.setLayoutParams(params);
                surface.post(this::updateVideoSurfaceLayout);
            });
        }
    }

    private void buildScreen() {
        screenScroll = new android.widget.ScrollView(this);
        screenScroll.setFillViewport(true);
        screenScroll.setVerticalScrollBarEnabled(false);
        screenScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        applySystemInsets(screenScroll);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(22));
        page.setBackgroundColor(BG);
        TextView title = text(deviceName == null ? "Front door" : deviceName, 23, Color.WHITE, true);
        page.addView(title);
        state = text("Starting live view…", 13, MUTED, false);
        page.addView(state, top(5));

        videoFrame = new FrameLayout(this);
        videoFrame.setBackgroundColor(Color.BLACK);
        surface = new TextureView(this);
        surface.setSurfaceTextureListener(this);
        zoomDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                videoZoom = Math.max(1f, Math.min(4f, videoZoom * detector.getScaleFactor()));
                // Ignore tiny pinch jitter around 1x so zooming out settles in one gesture.
                if (videoZoom < 1.05f) {
                    videoZoom = 1f;
                    videoPanX = 0f;
                    videoPanY = 0f;
                }
                scheduleVideoZoomUpdate();
                return true;
            }
        });
        zoomDetector.setQuickScaleEnabled(false);
        surface.setOnTouchListener((v, event) -> handleVideoTouch(event));
        int availableWidth = getResources().getDisplayMetrics().widthPixels - dp(36);
        previewHeight = Math.max(dp(180), Math.round(availableWidth * 9f / 16f));
        videoFrame.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        fullscreenButton = button("Full screen", PANEL, Color.WHITE);
        fullscreenButton.setTextSize(12);
        fullscreenButton.setPadding(dp(14), 0, dp(14), 0);
        fullscreenButton.setOnClickListener(v -> setFullscreen(!fullscreen));
        FrameLayout.LayoutParams fullButtonParams = new FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP | Gravity.RIGHT);
        fullButtonParams.setMargins(0, dp(12), dp(12), 0);
        videoFrame.addView(fullscreenButton, fullButtonParams);
        qualityButton = button("Quality · " + qualityName(videoQuality), PANEL, Color.WHITE);
        qualityButton.setTextSize(12);
        qualityButton.setPadding(dp(12), 0, dp(12), 0);
        qualityButton.setOnClickListener(v -> showVideoQualityMenu());
        FrameLayout.LayoutParams qualityParams = new FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP | Gravity.LEFT);
        qualityParams.setMargins(dp(12), dp(12), 0, 0);
        videoFrame.addView(qualityButton, qualityParams);
        audioButton = button("Audio on", PANEL, Color.WHITE);
        audioButton.setTextSize(12);
        audioButton.setPadding(dp(12), 0, dp(12), 0);
        audioButton.setOnClickListener(v -> toggleLiveAudio());
        FrameLayout.LayoutParams audioParams = new FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP | Gravity.LEFT);
        audioParams.setMargins(dp(12), dp(58), 0, 0);
        videoFrame.addView(audioButton, audioParams);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, previewHeight);
        vp.topMargin = dp(16);
        vp.bottomMargin = dp(16);
        page.addView(videoFrame, vp);

        playbackControls = new LinearLayout(this);
        playbackControls.setOrientation(LinearLayout.VERTICAL);
        playbackControls.setPadding(dp(12), dp(8), dp(12), dp(10));
        playbackControls.setBackground(rounded(PANEL, 16));
        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        previousClipButton = button("−10s", Color.TRANSPARENT, Color.WHITE);
        previousClipButton.setOnClickListener(v -> seekPlaybackBy(-10000));
        seekRow.addView(previousClipButton, new LinearLayout.LayoutParams(dp(62), dp(44)));
        playbackSeek = new SeekBar(this);
        playbackSeek.setMax(1000);
        playbackSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        playbackSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seekRow.addView(playbackSeek, new LinearLayout.LayoutParams(0, dp(44), 1f));
        nextClipButton = button("+10s", Color.TRANSPARENT, Color.WHITE);
        nextClipButton.setOnClickListener(v -> seekPlaybackBy(10000));
        seekRow.addView(nextClipButton, new LinearLayout.LayoutParams(dp(62), dp(44)));
        playbackControls.addView(seekRow);
        playbackSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean fromUser;
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) { fromUser = user; }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { if (fromUser) seekPlaybackTo(bar.getProgress()); }
        });
        playbackControls.setVisibility(View.GONE);
        page.addView(playbackControls, top(-8));

        playButton = button("Pause video", PANEL, Color.WHITE);
        playButton.setOnClickListener(v -> togglePlayback());
        page.addView(playButton, height(48));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        talkButton = button("Talk", PANEL, Color.WHITE);
        talkButton.setOnClickListener(v -> toggleTalk());
        recordButton = button("Record", PANEL, Color.WHITE);
        recordButton.setOnClickListener(v -> toggleRecord());
        Button capture = button("Capture", PANEL, Color.WHITE);
        capture.setOnClickListener(v -> capture());
        addEqual(row, talkButton);
        addEqual(row, recordButton);
        addEqual(row, capture);
        page.addView(row, top(10));

        browseSdButton = button("Browse SD card recordings", ACCENT, BG);
        browseSdButton.setOnClickListener(v -> {
            state.setText("Connecting to the camera’s SD card…");
            browseRecordings();
        });
        page.addView(browseSdButton, top(10));
        screenScroll.addView(page);
        setContentView(screenScroll);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        surfaceReady = true;
        if (width > 0 && height > 0) main.postDelayed(() -> {
            startLive();
            if (alarmPlaybackAt > 0) findAlarmRecording();
        }, 120);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        if (width > 0 && height > 0 && surfaceReady && !playing && !playbackMode) main.postDelayed(this::startLive, 120);
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        surfaceReady = false;
        stopPlayer();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }

    private void startLive() {
        if (!surfaceReady || playing || serial == null || talking || playbackMode) return;
        try {
            if (playbackMode) {
                if (playbackPlayer != null) playbackPlayer.stopPlayback();
                playbackMode = false;
            }
            if (livePlayer == null) livePlayer = createPlayer();
            player = livePlayer;
            gotVideoSize = false;
            boolean started = player.startRealPlay();
            playing = started;
            if (started) {
                if (liveAudioEnabled) player.openSound(); else player.closeSound();
                if (videoZoom > 1f) scheduleVideoZoomUpdate();
                else player.setDisplayRegion(-1L, -1L, -1L, -1L);
            }
            state.setText(started ? "Connecting to camera…" : "The EZVIZ SDK could not start live view. Tap Resume video to retry.");
            playButton.setText(started ? "Pause video" : "Resume video");
            playbackControls.setVisibility(View.GONE);
            qualityButton.setVisibility(View.VISIBLE);
            audioButton.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            state.setText("Could not start video: " + e.getMessage());
        }
    }

    private boolean handlePlayerMessage(Message message) {
        if (message.what == EZRealPlayConstants.MSG_REALPLAY_PLAY_SUCCESS) {
            if (thumbnailScanActive) return true;
            if (playbackMode) {
                state.setText("Playing recording…");
                if (pendingPlaybackSeekAt > 0) {
                    long target = pendingPlaybackSeekAt;
                    pendingPlaybackSeekAt = -1;
                    main.postDelayed(() -> {
                        if (!playbackMode || playbackPlayer == null) return;
                        Calendar seek = Calendar.getInstance(); seek.setTimeInMillis(target);
                        playbackPlayer.seekPlayback(seek);
                    }, 500);
                }
            }
            else if (gotVideoSize) state.setText("Live · " + serial);
            else {
                state.setText("Live · waiting for video…");
                main.removeCallbacks(firstFrameTimeout);
                main.postDelayed(firstFrameTimeout, 10000);
                if (pendingTalk) beginTalk();
            }
            return true;
        }
        if (message.what == EZConstants.MSG_VIDEO_SIZE_CHANGED) {
            gotVideoSize = true;
            main.removeCallbacks(firstFrameTimeout);
            updateVideoAspectRatio(message.obj);
            if (thumbnailScanActive) {
                if (!thumbnailScanCaptureScheduled) {
                    thumbnailScanCaptureScheduled = true;
                    main.postDelayed(this::captureThumbnailScanFrame, 650);
                }
                return true;
            }
            state.setText((playbackMode ? "Playing recording" : "Live · " + serial)
                    + (talking ? " · Two-way talk active" : "") + " · " + String.valueOf(message.obj));
            if (playbackMode && currentSdFile != null) cacheCurrentThumbnail();
            surface.post(this::updateVideoSurfaceLayout);
            if (videoZoom > 1f) scheduleVideoZoomUpdate();
            return true;
        }
        if (message.what == EZRealPlayConstants.MSG_REALPLAY_PLAY_FAIL) {
            if (thumbnailScanActive) {
                main.removeCallbacks(thumbnailScanCaptureTimeout);
                finishThumbnailScanItem();
                return true;
            }
            playing = false;
            main.removeCallbacks(firstFrameTimeout);
            Object result = message.obj;
            if (result instanceof ErrorInfo) {
                ErrorInfo error = (ErrorInfo) result;
                if (encrypted && (error.errorCode == 400035 || error.errorCode == 400036)) {
                    askVerifyCode();
                } else {
                    if (!retryAlternateRoute("EZVIZ stream error " + error.errorCode + "."))
                        state.setText("Video error " + error.errorCode + ": " + error.description);
                }
            } else if (!retryAlternateRoute("Could not start the camera stream.")) state.setText("Could not start the camera stream.");
            return true;
        }
        if (message.what == EZRealPlayConstants.MSG_REALPLAY_VOICETALK_SUCCESS) {
            talking = true;
            talkStopping = false;
            talkButton.setEnabled(true);
            talkButton.setText("End talk");
            state.setText("Live · " + serial + " · Two-way talk active");
            return true;
        }
        if (message.what == EZRealPlayConstants.MSG_REALPLAY_VOICETALK_FAIL) {
            state.setText("Two-way audio could not start. Live video is still active.");
            finishTalkSession();
            return true;
        }
        if (message.what == EZRealPlayConstants.MSG_REALPLAY_VOICETALK_STOP) {
            state.setText("Live · " + serial);
            finishTalkSession();
            return true;
        }
        return false;
    }

    private boolean retryAlternateRoute(String reason) {
        LizaApplication app = (LizaApplication) getApplication();
        if (routeRetryUsed || playbackMode || !surfaceReady || app.isP2pEnabled()) return false;
        routeRetryUsed = true;
        state.setText(reason + " Retrying direct connection…");
        main.removeCallbacks(firstFrameTimeout);
        playing = false;
        try { if (livePlayer != null) { livePlayer.stopRealPlay(); livePlayer.release(); } } catch (Exception ignored) { }
        livePlayer = null;
        player = null;
        app.setP2pEnabled(true);
        main.postDelayed(this::startLive, 700);
        return true;
    }

    private void askVerifyCode() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("6-character device verification code");
        new AlertDialog.Builder(this)
            .setTitle("Unlock camera video")
            .setMessage("Enter the verification code printed on your DP2C label.")
            .setView(input)
            .setNegativeButton("Cancel", (d, w) -> state.setText("Video is encrypted; verification code is required."))
            .setPositiveButton("Continue", (d, w) -> {
                String code = input.getText().toString().trim();
                if (code.length() < 4) { state.setText("Check the verification code and try again."); return; }
                try {
                    new SecureVault(this).put("verify_" + serial, code);
                    player.setPlayVerifyCode(code);
                    player.startRealPlay();
                    playing = true;
                    state.setText("Retrying live view…");
                } catch (Exception e) { state.setText("Could not save or apply the verification code."); }
            }).show();
    }

    private void togglePlayback() {
        if (playbackMode) {
            try { if (playbackPlayer != null) playbackPlayer.stopPlayback(); } catch (Exception ignored) { }
            main.removeCallbacks(playbackTicker);
            playbackMode = false;
            playing = false;
            playbackControls.setVisibility(View.GONE);
            qualityButton.setVisibility(View.VISIBLE);
            audioButton.setVisibility(View.VISIBLE);
            currentSdFile = null;
            videoFrame.getLayoutParams().height = fullscreen ? fullscreenVideoHeight() : previewHeight;
            videoFrame.requestLayout();
            player = livePlayer;
            playButton.setText("Resume video");
            startLive();
        } else if (playing && livePlayer != null) {
            stopLocalRecordingIfNeeded();
            try { livePlayer.stopRealPlay(); } catch (Exception ignored) { }
            playing = false;
            playButton.setText("Resume video");
            state.setText("Video paused");
        } else startLive();
    }

    private void toggleTalk() {
        if (!talking && (livePlayer == null || !playing)) {
            Toast.makeText(this, "Wait for live video before starting talk", Toast.LENGTH_SHORT).show();
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingTalk = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 40);
            return;
        }
        try {
            if (talking) {
                if (!talkStopping) {
                    talkStopping = true;
                    talkButton.setEnabled(false);
                    state.setText("Ending talk…");
                    if (talkPlayer != null) talkPlayer.stopVoiceTalk();
                    main.postDelayed(() -> {
                        if (talkStopping) finishTalkSession();
                    }, 1800);
                }
            } else beginTalk();
        } catch (Exception e) {
            state.setText("Two-way audio failed: " + e.getMessage());
            finishTalkSession();
        }
    }

    private void beginTalk() {
        pendingTalk = false;
        try {
            talkStopping = false;
            stopLocalRecordingIfNeeded();
            talkButton.setEnabled(false);
            if (livePlayer != null) livePlayer.closeSound();
            if (talkPlayer == null) talkPlayer = createTalkPlayer();
            boolean started = talkPlayer.startVoiceTalk();
            if (started) state.setText("Live video continues · starting two-way talk…");
            else {
                state.setText("EZVIZ could not start two-way audio. Live video is still active.");
                finishTalkSession();
            }
        } catch (Exception e) {
            state.setText("Two-way audio failed: " + e.getMessage());
            finishTalkSession();
        }
    }

    private void finishTalkSession() {
        talkStopping = false;
        talking = false;
        pendingTalk = false;
        talkButton.setEnabled(true);
        talkButton.setText("Talk");
        playButton.setEnabled(true);
        try { if (talkPlayer != null) talkPlayer.release(); } catch (Exception ignored) { }
        talkPlayer = null;
        try { if (livePlayer != null) { if (liveAudioEnabled) livePlayer.openSound(); else livePlayer.closeSound(); } } catch (Exception ignored) { }
        player = livePlayer;
        if (playing && !playbackMode) state.setText("Live · " + serial);
    }

    private EZPlayer createTalkPlayer() {
        EZPlayer created = EZGlobalSDK.getInstance().createPlayer(serial, cameraNo);
        if (created == null) throw new IllegalStateException("EZVIZ could not create a talk player");
        created.setHandler(playerHandler);
        created.setAudioOnly(true);
        String verifyCode = new SecureVault(this).get("verify_" + serial);
        if (verifyCode != null) created.setPlayVerifyCode(verifyCode);
        return created;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 40) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                if (livePlayer == null && surfaceReady) startLive();
                else if (playing) beginTalk();
            } else {
                pendingTalk = false;
                state.setText("Microphone permission is needed for two-way audio.");
            }
        } else if (requestCode == 41) {
            int action = pendingStorageAction;
            pendingStorageAction = 0;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                if (action == 1) capture();
                else if (action == 2) toggleRecord();
            } else state.setText("Storage access is needed to save media to Photos.");
        }
    }

    private void toggleRecord() {
        if (!recording && !ensureMediaPermission(2)) return;
        if (player == null || !playing || playbackMode) {
            Toast.makeText(this, "Start live video before recording", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (recording) {
                player.stopLocalRecord();
                recording = false;
                recordButton.setText("Record");
                state.setText("Saving recording to Movies/Liza…");
                publishMediaFile(new File(recordingPath), "Liza-" + System.currentTimeMillis() + ".mp4", "video/mp4", "Movies/Liza");
            } else {
                File movieRoot = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                if (movieRoot == null) movieRoot = getFilesDir();
                File folder = new File(movieRoot, "Liza");
                if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Cannot create recording folder");
                recordingPath = new File(folder, "Liza-" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
                recording = player.startLocalRecordWithFile(recordingPath);
                recordButton.setText(recording ? "Stop recording" : "Record");
                state.setText(recording ? "Recording video…" : "Recording did not start");
            }
        } catch (Exception e) { state.setText("Recording failed: " + e.getMessage()); }
    }

    private void stopLocalRecordingIfNeeded() {
        if (!recording) return;
        try { if (livePlayer != null) livePlayer.stopLocalRecord(); } catch (Exception ignored) { }
        recording = false;
        recordButton.setText("Record");
        if (recordingPath != null) {
            state.setText("Saving recording to Movies/Liza…");
            publishMediaFile(new File(recordingPath), "Liza-" + System.currentTimeMillis() + ".mp4", "video/mp4", "Movies/Liza");
        }
    }

    private void capture() {
        if (!ensureMediaPermission(1)) return;
        EZPlayer capturePlayer = livePlayer;
        if (capturePlayer == null || !playing || playbackMode || talking) {
            Toast.makeText(this, "Start live video before taking a snapshot", Toast.LENGTH_SHORT).show();
            return;
        }
        worker.execute(() -> {
            try {
                File pictureRoot = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (pictureRoot == null) pictureRoot = getFilesDir();
                File folder = new File(pictureRoot, "Liza");
                if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Cannot create photo folder");
                String name = "Liza-" + System.currentTimeMillis() + ".jpg";
                File photo = new File(folder, name);
                String path = photo.getAbsolutePath();
                int code = capturePlayer.capturePicture(path);
                if (code != 0 || !photo.isFile()) throw new IllegalStateException("EZVIZ could not create the snapshot (" + code + ")");
                publishMediaFile(photo, name, "image/jpeg", "Pictures/Liza");
            } catch (Exception e) { main.post(() -> Toast.makeText(this, "Snapshot failed: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
        });
    }

    private boolean ensureMediaPermission(int action) {
        if (android.os.Build.VERSION.SDK_INT >= 23 && android.os.Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingStorageAction = action;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 41);
            return false;
        }
        return true;
    }

    private void publishMediaFile(File source, String name, String mime, String relativeFolder) {
        worker.execute(() -> {
            Uri inserted = null;
            try {
                if (mime.startsWith("video/")) {
                    // EZPlayer finalizes the MP4 asynchronously after stopLocalRecord();
                    // until then it may only have the adjacent .mp4_temp file.
                    long deadline = System.currentTimeMillis() + 12000;
                    long previousLength = -1;
                    int stableSamples = 0;
                    while (System.currentTimeMillis() < deadline) {
                        long length = source.isFile() ? source.length() : -1;
                        if (length > 0 && length == previousLength) stableSamples++;
                        else stableSamples = 0;
                        if (stableSamples >= 2) break;
                        previousLength = length;
                        Thread.sleep(350);
                    }
                }
                if (!source.isFile() || source.length() == 0) throw new IllegalStateException("The media file did not finish saving");
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    Uri collection = mime.startsWith("image/")
                            ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                            : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder);
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    inserted = getContentResolver().insert(collection, values);
                    if (inserted == null) throw new IllegalStateException("The media library declined the file");
                    try (InputStream in = new FileInputStream(source); OutputStream out = getContentResolver().openOutputStream(inserted, "w")) {
                        if (out == null) throw new IllegalStateException("Could not open the media library file");
                        copy(in, out);
                    }
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(inserted, ready, null, null);
                } else {
                    String publicType = relativeFolder.startsWith("Pictures") ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES;
                    File folder = new File(Environment.getExternalStoragePublicDirectory(publicType), "Liza");
                    if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Could not create the Photos folder");
                    File destination = new File(folder, name);
                    try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(destination)) { copy(in, out); }
                    MediaScannerConnection.scanFile(this, new String[]{destination.getAbsolutePath()}, new String[]{mime}, null);
                }
                main.post(() -> {
                    if (mime.startsWith("video/") && state.getText().toString().startsWith("Saving recording"))
                        state.setText("Recording saved to Movies/Liza");
                    if (mime.startsWith("image/")) state.setText("Photo saved to Pictures/Liza");
                    Toast.makeText(this, (mime.startsWith("image/") ? "Photo" : "Video") + " saved to " + relativeFolder, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                if (inserted != null) try { getContentResolver().delete(inserted, null, null); } catch (Exception ignored) { }
                main.post(() -> {
                    String message = "Could not save media to Photos: " + e.getMessage();
                    if (mime.startsWith("video/") && state.getText().toString().startsWith("Saving recording")) state.setText("Recording save failed");
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        out.flush();
    }

    private void browseRecordings() {
        searchRecordingsForDate(Calendar.getInstance());
    }

    private void searchRecordingsForDate(Calendar selectedDay) {
        if (!surfaceReady || serial == null) {
            state.setText("Camera view is not ready yet. Try again in a moment.");
            Toast.makeText(this, "Camera view is not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (livePlayer == null) livePlayer = createPlayer();
        } catch (Exception e) {
            state.setText("Could not prepare the camera for SD card search.");
            Toast.makeText(this, "Could not prepare the camera", Toast.LENGTH_LONG).show();
            return;
        }
        stopLocalRecordingIfNeeded();
        if (playbackMode && playbackPlayer != null) {
            try { playbackPlayer.stopPlayback(); } catch (Exception ignored) { }
            playbackMode = false;
        }
        cancelThumbnailGeneration(false);
        sdSearchDate = (Calendar) selectedDay.clone();
        sdSearchDate.set(Calendar.HOUR_OF_DAY, 0);
        sdSearchDate.set(Calendar.MINUTE, 0);
        sdSearchDate.set(Calendar.SECOND, 0);
        sdSearchDate.set(Calendar.MILLISECOND, 0);
        player = livePlayer;
        browseSdButton.setEnabled(false);
        browseSdButton.setText("Searching recordings…");
        showSdSearchProgress();
        playButton.setText("Resume video");
        // Release the live stream while the SDK asks the camera for its SD-card index.
        if (playing && livePlayer != null) {
            try { livePlayer.stopRealPlay(); } catch (Exception ignored) { }
            playing = false;
        }
        String dayLabel = recordingDayLabel(sdSearchDate);
        state.setText("Searching SD card recordings for " + dayLabel + "…");
        Calendar queryFrom = (Calendar) sdSearchDate.clone();
        Calendar dayEnd = (Calendar) sdSearchDate.clone();
        dayEnd.add(Calendar.DAY_OF_MONTH, 1);
        dayEnd.add(Calendar.MILLISECOND, -1);
        Calendar now = Calendar.getInstance();
        Calendar queryTo = now.before(dayEnd) ? now : dayEnd;
        worker.execute(() -> {
            try {
                List<EZDeviceRecordFile> files = EZGlobalSDK.getInstance().searchRecordFileFromDevice(serial, cameraNo, queryFrom, queryTo);
                main.post(() -> {
                    main.removeCallbacks(sdSearchTimeout);
                    dismissSdSearchProgress();
                    resetSdSearchButton();
                showRecordings(files);
                });
            } catch (BaseException e) {
                String message;
                if (e.getErrorCode() == 120008) {
                    message = "The camera did not answer the SD recording search (timeout 120008). Make sure it is awake and online, then retry.";
                } else if (e.getErrorCode() == 110001) {
                    message = "EZVIZ rejected the SD recording search parameters (110001). Check the camera's date and time settings, then try another date.";
                } else {
                    message = "Recording search failed (EZVIZ error " + e.getErrorCode() + ").";
                }
                main.post(() -> {
                    main.removeCallbacks(sdSearchTimeout);
                    dismissSdSearchProgress();
                    resetSdSearchButton();
                    state.setText(message);
                    new AlertDialog.Builder(this).setTitle("SD card search failed")
                            .setMessage(message).setPositiveButton("OK", null).show();
                    startLive();
                });
            } catch (Exception e) {
                String message = "Recording search failed: " + e.getMessage();
                main.post(() -> {
                    main.removeCallbacks(sdSearchTimeout);
                    dismissSdSearchProgress();
                    resetSdSearchButton();
                    state.setText(message);
                    new AlertDialog.Builder(this).setTitle("SD card search failed")
                            .setMessage(message).setPositiveButton("OK", null).show();
                    startLive();
                });
            }
        });
    }

    private void showSdSearchProgress() {
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(this);
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(22), dp(18), dp(22), dp(18));
        box.addView(spinner, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView message = text("Contacting camera…", 15, MUTED, false);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(0, -2, 1f);
        messageParams.leftMargin = dp(16);
        box.addView(message, messageParams);
        sdSearchDialog = new AlertDialog.Builder(this).setTitle("SD card recordings")
                .setView(box).create();
        sdSearchDialog.setCancelable(false);
        sdSearchDialog.show();
        main.postDelayed(sdSearchTimeout, 30000);
    }

    private void dismissSdSearchProgress() {
        if (sdSearchDialog != null) {
            sdSearchDialog.dismiss();
            sdSearchDialog = null;
        }
    }

    private void resetSdSearchButton() {
        if (browseSdButton != null) {
            browseSdButton.setEnabled(true);
            browseSdButton.setText("Browse SD card recordings");
        }
    }

    private void showRecordings(List<EZDeviceRecordFile> files) {
        if (files == null || files.isEmpty()) {
            String dayLabel = recordingDayLabel(sdSearchDate);
            state.setText("No SD card recordings found for " + dayLabel + ".");
            new AlertDialog.Builder(this).setTitle("No recordings found")
                    .setMessage("The camera has no SD card recordings for " + dayLabel + ".")
                    .setNegativeButton("Done", null)
                    .setPositiveButton("Choose another date", (dialog, which) -> showRecordingDatePicker(null)).show();
            startLive();
            return;
        }
        sdFiles = new ArrayList<>(files);
        Collections.sort(sdFiles, (left, right) -> Long.compare(
                right.getStartTime() == null ? 0L : right.getStartTime().getTimeInMillis(),
                left.getStartTime() == null ? 0L : left.getStartTime().getTimeInMillis()));
        thumbnailViews.clear();
        DateFormat day = new SimpleDateFormat("MMM d", Locale.getDefault());
        DateFormat time = new SimpleDateFormat("HH:mm", Locale.getDefault());
        final AlertDialog[] dialogRef = new AlertDialog[1];
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(10), dp(18), dp(16));
        content.setBackgroundColor(BG);
        TextView heading = text("SD card", 22, Color.WHITE, true);
        content.addView(heading);
        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        recordingsHint = text(recordingsCountLabel(sdFiles.size()) + " · " + recordingDayLabel(sdSearchDate), 13, MUTED, false);
        filterRow.addView(recordingsHint, new LinearLayout.LayoutParams(0, -2, 1f));
        Button dateButton = button(recordingDateButtonLabel(sdSearchDate), PANEL, Color.WHITE);
        dateButton.setTextSize(12);
        dateButton.setOnClickListener(v -> showRecordingDatePicker(dialogRef[0]));
        filterRow.addView(dateButton, new LinearLayout.LayoutParams(-2, dp(40)));
        content.addView(filterRow, top(3));
        autoplayNextCheckBox = new CheckBox(this);
        autoplayNextCheckBox.setText("Autoplay newer recordings");
        autoplayNextCheckBox.setTextColor(MUTED);
        autoplayNextCheckBox.setTextSize(13);
        autoplayNextCheckBox.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        autoplayNextCheckBox.setChecked(autoPlayNext);
        autoplayNextCheckBox.setOnCheckedChangeListener((button, checked) -> {
            autoPlayNext = checked;
            getSharedPreferences("liza_settings", MODE_PRIVATE).edit().putBoolean("sd_autoplay_next", checked).apply();
        });
        content.addView(autoplayNextCheckBox, top(2));
        android.widget.ScrollView listScroll = new android.widget.ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < sdFiles.size(); i++) {
            final int index = i;
            EZDeviceRecordFile file = sdFiles.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(9), dp(12), dp(9));
            row.setBackground(rounded(PANEL, 14));
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(rounded(Color.rgb(34, 47, 61), 10));
            thumb.setImageResource(android.R.drawable.ic_media_play);
            File cachedThumb = thumbnailFile(file);
            if (cachedThumb.isFile()) thumb.setImageURI(Uri.fromFile(cachedThumb));
            thumbnailViews.put(thumbnailKey(file), thumb);
            row.addView(thumb, new LinearLayout.LayoutParams(dp(92), dp(64)));
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(12), 0, dp(8), 0);
            String startDay = day.format(file.getStartTime().getTime());
            String endDay = day.format(file.getStopTime().getTime());
            String range = startDay.equals(endDay)
                    ? startDay + " · " + time.format(file.getStartTime().getTime()) + "–" + time.format(file.getStopTime().getTime())
                    : startDay + " " + time.format(file.getStartTime().getTime()) + "–" + endDay + " " + time.format(file.getStopTime().getTime());
            TextView title = text(range, 15, Color.WHITE, true);
            info.addView(title);
            long seconds = Math.max(0, (file.getStopTime().getTimeInMillis() - file.getStartTime().getTimeInMillis()) / 1000);
            TextView sub = text(String.format(Locale.getDefault(), "%d min %02d sec  ·  SD card", seconds / 60, seconds % 60), 12, MUTED, false);
            info.addView(sub, top(4));
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView arrow = text("▶", 16, ACCENT, true);
            row.addView(arrow);
            row.setClickable(true);
            row.setOnClickListener(v -> {
                selectedSdIndex = index;
                cancelThumbnailGeneration(false);
                playRecording(file);
                if (dialogRef[0] != null) dialogRef[0].dismiss();
            });
            rows.addView(row, top(8));
        }
        listScroll.addView(rows);
        int availableListHeight = Math.max(dp(180), getResources().getDisplayMetrics().heightPixels - dp(300));
        int listHeight = Math.min(availableListHeight, Math.max(dp(96), dp(files.size() * 96)));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, listHeight);
        scrollParams.topMargin = dp(12);
        content.addView(listScroll, scrollParams);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).setNegativeButton("Done", null).create();
        dialogRef[0] = dialog;
        recordingsDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (recordingsDialog == dialog) recordingsDialog = null;
            if (thumbnailScanActive) cancelThumbnailGeneration(true);
        });
        dialog.show();
        dialog.getWindow().setBackgroundDrawable(rounded(BG, 22));
        dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(520)), -2);
        state.setText(recordingsCountLabel(sdFiles.size()) + " found · newest first");
        startThumbnailGeneration();
    }

    private void showRecordingDatePicker(AlertDialog listDialog) {
        Calendar initial = (Calendar) sdSearchDate.clone();
        DatePickerDialog picker = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            Calendar selected = Calendar.getInstance();
            selected.set(Calendar.YEAR, year);
            selected.set(Calendar.MONTH, month);
            selected.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            selected.set(Calendar.HOUR_OF_DAY, 0);
            selected.set(Calendar.MINUTE, 0);
            selected.set(Calendar.SECOND, 0);
            selected.set(Calendar.MILLISECOND, 0);
            if (listDialog != null && listDialog.isShowing()) listDialog.dismiss();
            searchRecordingsForDate(selected);
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH));
        picker.getDatePicker().setMaxDate(System.currentTimeMillis());
        picker.setTitle("Choose recording date");
        picker.show();
    }

    private String recordingDayLabel(Calendar date) {
        Calendar today = Calendar.getInstance();
        boolean isToday = date.get(Calendar.ERA) == today.get(Calendar.ERA)
                && date.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && date.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);
        return isToday ? "Today" : new SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date.getTime());
    }

    private String recordingDateButtonLabel(Calendar date) {
        return recordingDayLabel(date) + "  ▾";
    }

    private String recordingsCountLabel(int count) {
        return count + (count == 1 ? " recording" : " recordings");
    }

    private void startThumbnailGeneration() {
        thumbnailScanQueue.clear();
        thumbnailScanIndex = 0;
        for (EZDeviceRecordFile file : sdFiles) {
            if (file != null && !thumbnailFile(file).isFile()) thumbnailScanQueue.add(file);
        }
        if (thumbnailScanQueue.isEmpty()) {
            startLive();
            return;
        }
        thumbnailScanActive = true;
        main.removeCallbacks(firstFrameTimeout);
        if (recordingsHint != null) {
            recordingsHint.setText(recordingsCountLabel(sdFiles.size()) + " · preparing previews 0/" + thumbnailScanQueue.size());
        }
        playNextThumbnailForScan();
    }

    private void playNextThumbnailForScan() {
        if (!thumbnailScanActive || isFinishing()) return;
        if (thumbnailScanIndex >= thumbnailScanQueue.size()) {
            thumbnailScanActive = false;
            thumbnailScanQueue.clear();
            currentSdFile = null;
            if (playbackPlayer != null) try { playbackPlayer.stopPlayback(); } catch (Exception ignored) { }
            player = livePlayer;
            if (recordingsHint != null) recordingsHint.setText(recordingsCountLabel(sdFiles.size()) + " · " + recordingDayLabel(sdSearchDate));
            startLive();
            return;
        }
        EZDeviceRecordFile file = thumbnailScanQueue.get(thumbnailScanIndex);
        currentSdFile = file;
        gotVideoSize = false;
        thumbnailScanCaptureScheduled = false;
        thumbnailScanCaptureRetries = 0;
        try {
            if (playbackPlayer == null) playbackPlayer = createPlayer();
            player = playbackPlayer;
            if (!playbackPlayer.startPlayback(file)) {
                finishThumbnailScanItem();
                return;
            }
            main.removeCallbacks(thumbnailScanCaptureTimeout);
            main.postDelayed(thumbnailScanCaptureTimeout, 8000);
            if (recordingsHint != null) {
                recordingsHint.setText(recordingsCountLabel(sdFiles.size()) + " · preparing previews "
                        + (thumbnailScanIndex + 1) + "/" + thumbnailScanQueue.size());
            }
        } catch (Exception ignored) {
            finishThumbnailScanItem();
        }
    }

    private void captureThumbnailScanFrame() {
        if (!thumbnailScanActive || currentSdFile == null || playbackPlayer == null) return;
        Bitmap frame = null;
        try { frame = playbackPlayer.capturePicture(); } catch (Exception ignored) { }
        if (frame == null) {
            if (thumbnailScanCaptureRetries++ < 2) {
                main.postDelayed(this::captureThumbnailScanFrame, 450);
            } else finishThumbnailScanItem();
            return;
        }
        EZDeviceRecordFile capturedFile = currentSdFile;
        File target = thumbnailFile(capturedFile);
        Bitmap thumb = Bitmap.createScaledBitmap(frame, 240, 135, true);
        if (thumb != frame) frame.recycle();
        main.removeCallbacks(thumbnailScanCaptureTimeout);
        worker.execute(() -> {
            try (FileOutputStream out = new FileOutputStream(target)) {
                thumb.compress(Bitmap.CompressFormat.JPEG, 78, out);
            } catch (Exception ignored) { }
            main.post(() -> {
                ImageView view = thumbnailViews.get(thumbnailKey(capturedFile));
                if (view != null && target.isFile()) view.setImageURI(Uri.fromFile(target));
                thumb.recycle();
                finishThumbnailScanItem();
            });
        });
    }

    private void finishThumbnailScanItem() {
        if (!thumbnailScanActive) return;
        main.removeCallbacks(thumbnailScanCaptureTimeout);
        thumbnailScanCaptureScheduled = false;
        if (playbackPlayer != null) try { playbackPlayer.stopPlayback(); } catch (Exception ignored) { }
        currentSdFile = null;
        thumbnailScanIndex++;
        main.postDelayed(this::playNextThumbnailForScan, 250);
    }

    private void cancelThumbnailGeneration(boolean restartLive) {
        if (!thumbnailScanActive) return;
        thumbnailScanActive = false;
        main.removeCallbacks(thumbnailScanCaptureTimeout);
        if (playbackPlayer != null) try { playbackPlayer.stopPlayback(); } catch (Exception ignored) { }
        currentSdFile = null;
        thumbnailScanQueue.clear();
        player = livePlayer;
        if (restartLive) startLive();
    }

    private void playRecording(EZDeviceRecordFile file) {
        try {
            if (playing && livePlayer != null) livePlayer.stopRealPlay();
            playing = false;
            if (playbackPlayer == null) playbackPlayer = createPlayer();
            else if (playbackMode) playbackPlayer.stopPlayback();
            player = playbackPlayer;
            boolean started = playbackPlayer.startPlayback(file);
            if (!started) { state.setText("EZVIZ could not start this SD card recording. Try another clip."); startLive(); return; }
            playbackMode = true;
            autoAdvanceTriggered = false;
            qualityButton.setVisibility(View.GONE);
            audioButton.setVisibility(View.GONE);
            if (videoZoom > 1f) scheduleVideoZoomUpdate();
            else playbackPlayer.setDisplayRegion(-1L, -1L, -1L, -1L);
            currentSdFile = file;
            playbackFromMillis = file.getStartTime().getTimeInMillis();
            playbackToMillis = file.getStopTime().getTimeInMillis();
            thumbnailCapturePending = false;
            playbackControls.setVisibility(View.VISIBLE);
            playbackSeek.setProgress(0);
            main.removeCallbacks(playbackTicker);
            main.post(playbackTicker);
            enlargePlaybackView();
            playButton.setText("Return to live view");
            state.setText("Playing SD card recording");
        } catch (Exception e) { state.setText("Could not play recording: " + e.getMessage()); }
    }

    private void playNextNewerRecording() {
        if (selectedSdIndex > 0 && selectedSdIndex <= sdFiles.size() - 1) {
            int nextIndex = selectedSdIndex - 1;
            selectedSdIndex = nextIndex;
            playRecording(sdFiles.get(nextIndex));
        } else {
            main.removeCallbacks(playbackTicker);
            state.setText("End of the latest recording.");
        }
    }

    private void findAlarmRecording() {
        long eventAt = alarmPlaybackAt;
        alarmPlaybackAt = -1;
        selectedSdIndex = -1;
        state.setText("Finding the recording for this motion alert…");
        Calendar from = Calendar.getInstance(); from.setTimeInMillis(eventAt - Math.max(60000L, alarmPreTime * 1000L));
        Calendar to = Calendar.getInstance(); to.setTimeInMillis(eventAt + 180000L);
        worker.execute(() -> {
            try {
                List<EZDeviceRecordFile> records = EZGlobalSDK.getInstance().searchRecordFileFromDevice(serial, cameraNo, from, to);
                EZDeviceRecordFile match = null;
                if (records != null && !records.isEmpty()) {
                    long best = Long.MAX_VALUE;
                    for (EZDeviceRecordFile record : records) {
                        long start = record.getStartTime().getTimeInMillis();
                        long stop = record.getStopTime().getTimeInMillis();
                        long delta = eventAt < start ? start - eventAt : (eventAt > stop ? eventAt - stop : 0);
                        if (delta < best) { best = delta; match = record; }
                    }
                }
                EZDeviceRecordFile result = match;
                main.post(() -> {
                    if (result == null) { state.setText("No SD recording was found for this alert. The snapshot is shown in the alert list when available."); return; }
                    pendingPlaybackSeekAt = eventAt;
                    playRecording(result);
                });
            } catch (BaseException e) { main.post(() -> state.setText("Could not open this alert recording (EZVIZ error " + e.getErrorCode() + ").")); }
            catch (Exception e) { main.post(() -> state.setText("Could not open this alert recording. Check the camera connection.")); }
        });
    }

    private long parseAlarmTime(String value) {
        if (value == null) return -1;
        try { long numeric = Long.parseLong(value); return numeric < 100000000000L ? numeric * 1000L : numeric; } catch (Exception ignored) { }
        String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"};
        for (String pattern : patterns) try { return new SimpleDateFormat(pattern, Locale.US).parse(value).getTime(); } catch (Exception ignored) { }
        return -1;
    }

    private void seekPlaybackBy(long offset) {
        Calendar now = playbackPlayer == null ? null : playbackPlayer.getOSDTime();
        if (now == null) now = playbackStart();
        Calendar start = playbackStart(), end = playbackEnd();
        if (now == null || start == null || end == null) return;
        now.setTimeInMillis(Math.max(start.getTimeInMillis(), Math.min(end.getTimeInMillis(), now.getTimeInMillis() + offset)));
        if (playbackPlayer.seekPlayback(now)) state.setText("Playing recording · " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now.getTime()));
    }

    private void seekPlaybackTo(int progress) {
        Calendar start = playbackStart(), end = playbackEnd();
        if (start == null || end == null || playbackPlayer == null) return;
        long at = start.getTimeInMillis() + (end.getTimeInMillis() - start.getTimeInMillis()) * progress / Math.max(1, playbackSeek.getMax());
        Calendar position = (Calendar) start.clone(); position.setTimeInMillis(at); playbackPlayer.seekPlayback(position);
    }

    private Calendar playbackStart() { if (playbackFromMillis <= 0) return null; Calendar c = Calendar.getInstance(); c.setTimeInMillis(playbackFromMillis); return c; }
    private Calendar playbackEnd() { if (playbackToMillis <= 0) return null; Calendar c = Calendar.getInstance(); c.setTimeInMillis(playbackToMillis); return c; }

    private void enlargePlaybackView() {
        if (videoFrame == null) return;
        // Keep the recording view at the camera's 16:9 preview ratio. A portrait-height
        // frame makes the actual video look like a narrow strip with large black bars.
        playbackViewHeight = previewHeight;
        videoFrame.getLayoutParams().height = fullscreen ? fullscreenVideoHeight() : playbackViewHeight;
        videoFrame.requestLayout();
        surface.post(this::updateVideoSurfaceLayout);
    }

    private void updateVideoAspectRatio(Object size) {
        if (size == null) return;
        String[] values = size.toString().split("[^0-9]+");
        int width = 0, height = 0;
        for (String value : values) {
            if (value.isEmpty()) continue;
            try {
                int dimension = Integer.parseInt(value);
                if (width == 0) width = dimension;
                else { height = dimension; break; }
            } catch (NumberFormatException ignored) { }
        }
        if (width > 0 && height > 0) {
            videoSourceWidth = width;
            videoSourceHeight = height;
            float ratio = (float) width / height;
            if (ratio >= 0.5f && ratio <= 3.5f) videoAspectRatio = ratio;
        }
    }

    private void updateVideoSurfaceLayout() {
        if (videoFrame == null || surface == null) return;
        int frameWidth = videoFrame.getWidth();
        int frameHeight = videoFrame.getHeight();
        if (frameWidth <= 0 || frameHeight <= 0) return;
        int viewportBottom = frameHeight;
        int safeInsetX = 0;
        int safeInsetY = 0;
        boolean fullscreenPlayback = fullscreen && playbackMode
                && playbackControls != null && playbackControls.getParent() == videoFrame;
        if (fullscreen) {
            // Fit the entire SDK frame, including its edge timestamp, with a small gutter.
            safeInsetX = dp(6);
            safeInsetY = dp(6);
        }
        if (fullscreenPlayback) {
            int controlsHeight = playbackControls.getMeasuredHeight();
            if (controlsHeight <= 0) controlsHeight = dp(72);
            viewportBottom -= controlsHeight + dp(20);
        }
        int viewportWidth = Math.max(1, frameWidth - safeInsetX * 2);
        int viewportHeight = Math.max(1, viewportBottom - safeInsetY * 2);
        float frameAspect = (float) viewportWidth / viewportHeight;
        int contentWidth = viewportWidth;
        int contentHeight = viewportHeight;
        if (frameAspect > videoAspectRatio) {
            contentWidth = Math.round(viewportHeight * videoAspectRatio);
        } else {
            contentHeight = Math.round(viewportWidth / videoAspectRatio);
        }
        ViewGroup.LayoutParams current = surface.getLayoutParams();
        if (!(current instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) current;
        params.width = contentWidth;
        params.height = contentHeight;
        if (fullscreen) {
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.topMargin = safeInsetY + Math.max(0, (viewportHeight - contentHeight) / 2);
        } else {
            params.gravity = Gravity.CENTER;
            params.topMargin = 0;
        }
        surface.setLayoutParams(params);
        if (videoZoom > 1f) scheduleVideoZoomUpdate();
    }

    private void scheduleVideoZoomUpdate() {
        if (surface == null || videoZoomUpdateScheduled) return;
        videoZoomUpdateScheduled = true;
        surface.postOnAnimation(() -> {
            videoZoomUpdateScheduled = false;
            applyVideoZoom();
        });
    }

    private void applyVideoZoom() {
        if (surface == null || surface.getWidth() == 0 || surface.getHeight() == 0) return;
        EZPlayer zoomPlayer = playbackMode ? playbackPlayer : livePlayer;
        if (zoomPlayer == null) return;
        float width = surface.getWidth(), height = surface.getHeight();
        float maxX = Math.max(0, (videoZoom - 1f) * width / 2f);
        float maxY = Math.max(0, (videoZoom - 1f) * height / 2f);
        videoPanX = Math.max(-maxX, Math.min(maxX, videoPanX));
        videoPanY = Math.max(-maxY, Math.min(maxY, videoPanY));
        if (videoZoom <= 1.001f) {
            videoZoom = 1f;
            videoPanX = videoPanY = 0f;
            zoomPlayer.setDisplayRegion(-1L, -1L, -1L, -1L);
            return;
        }
        float cropWidth = videoSourceWidth / videoZoom;
        float cropHeight = videoSourceHeight / videoZoom;
        float left = (videoSourceWidth - cropWidth) / 2f
                - videoPanX * videoSourceWidth / (width * videoZoom);
        float top = (videoSourceHeight - cropHeight) / 2f
                - videoPanY * videoSourceHeight / (height * videoZoom);
        left = Math.max(0f, Math.min(videoSourceWidth - cropWidth, left));
        top = Math.max(0f, Math.min(videoSourceHeight - cropHeight, top));
        zoomPlayer.setDisplayRegion(Math.round(left), Math.round(top),
                Math.round(left + cropWidth), Math.round(top + cropHeight));
    }

    private boolean handleVideoTouch(android.view.MotionEvent event) {
        zoomDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        int count = event.getPointerCount();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            // Keep ScrollView from stealing a one-finger gesture and moving the video frame.
            surface.getParent().requestDisallowInterceptTouchEvent(true);
            lastGestureX = event.getX();
            lastGestureY = event.getY();
        } else if (action == android.view.MotionEvent.ACTION_POINTER_DOWN && count >= 2) {
            float[] center = pointerCenter(event, -1);
            lastGestureX = center[0];
            lastGestureY = center[1];
        } else if (action == android.view.MotionEvent.ACTION_MOVE && count >= 2) {
            float[] center = pointerCenter(event, -1);
            if (videoZoom > 1f) {
                videoPanX += center[0] - lastGestureX;
                videoPanY += center[1] - lastGestureY;
                scheduleVideoZoomUpdate();
            }
            lastGestureX = center[0];
            lastGestureY = center[1];
        } else if (action == android.view.MotionEvent.ACTION_POINTER_UP && count > 2) {
            float[] center = pointerCenter(event, event.getActionIndex());
            lastGestureX = center[0];
            lastGestureY = center[1];
        } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
            surface.getParent().requestDisallowInterceptTouchEvent(false);
            if (videoZoom <= 1f) {
                videoZoom = 1f;
                videoPanX = 0f;
                videoPanY = 0f;
                scheduleVideoZoomUpdate();
            }
        }
        return true;
    }

    private float[] pointerCenter(android.view.MotionEvent event, int excludedIndex) {
        float x = 0f, y = 0f;
        int count = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == excludedIndex) continue;
            x += event.getX(i);
            y += event.getY(i);
            count++;
        }
        return count == 0 ? new float[]{lastGestureX, lastGestureY} : new float[]{x / count, y / count};
    }

    private void setFullscreen(boolean enabled) {
        if (fullscreen == enabled || videoFrame == null) return;
        fullscreen = enabled;
        View decor = getWindow().getDecorView();
        if (enabled) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            fullscreenSavedSystemUi = decor.getSystemUiVisibility();
            fullscreenSavedPageLeft = page.getPaddingLeft();
            fullscreenSavedPageTop = page.getPaddingTop();
            fullscreenSavedPageRight = page.getPaddingRight();
            fullscreenSavedPageBottom = page.getPaddingBottom();
            page.setPadding(0, 0, 0, 0);
            screenScroll.setPadding(0, 0, 0, 0);
            screenScroll.scrollTo(0, 0);
            for (int i = 0; i < page.getChildCount(); i++) {
                View child = page.getChildAt(i);
                child.setVisibility(child == videoFrame ? View.VISIBLE : View.GONE);
            }
            if (playbackMode && playbackControls.getParent() == page) {
                fullscreenControlsIndex = page.indexOfChild(playbackControls);
                fullscreenControlsLayout = (LinearLayout.LayoutParams) playbackControls.getLayoutParams();
                page.removeView(playbackControls);
                FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
                overlayParams.setMargins(dp(12), 0, dp(12), dp(12));
                videoFrame.addView(playbackControls, overlayParams);
                playbackControls.setVisibility(View.VISIBLE);
            }
            videoFrame.getLayoutParams().height = fullscreenVideoHeight();
            videoFrame.requestLayout();
            fullscreenButton.setText("Exit full screen");
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            if (playbackControls.getParent() == videoFrame) {
                videoFrame.removeView(playbackControls);
                int index = fullscreenControlsIndex < 0 ? Math.min(3, page.getChildCount()) : Math.min(fullscreenControlsIndex, page.getChildCount());
                page.addView(playbackControls, index, fullscreenControlsLayout == null ? top(-8) : fullscreenControlsLayout);
            }
            for (int i = 0; i < page.getChildCount(); i++) {
                View child = page.getChildAt(i);
                child.setVisibility(child == playbackControls && !playbackMode ? View.GONE : View.VISIBLE);
            }
            page.setPadding(fullscreenSavedPageLeft, fullscreenSavedPageTop, fullscreenSavedPageRight, fullscreenSavedPageBottom);
            videoFrame.getLayoutParams().height = playbackMode
                    ? (playbackViewHeight > 0 ? playbackViewHeight : previewHeight) : previewHeight;
            videoFrame.requestLayout();
            fullscreenButton.setText("Full screen");
            decor.setSystemUiVisibility(fullscreenSavedSystemUi);
            screenScroll.requestApplyInsets();
        }
        surface.post(this::updateVideoSurfaceLayout);
    }

    private int fullscreenVideoHeight() {
        int measuredHeight = screenScroll == null ? 0
                : screenScroll.getHeight() - screenScroll.getPaddingTop() - screenScroll.getPaddingBottom();
        if (measuredHeight > 0) return measuredHeight;
        return Math.max(getResources().getDisplayMetrics().heightPixels, dp(360));
    }

    @Override public void onBackPressed() {
        if (fullscreen) setFullscreen(false);
        else super.onBackPressed();
    }

    private File thumbnailFile(EZDeviceRecordFile file) {
        File folder = new File(getCacheDir(), "record-thumbnails");
        if (!folder.exists()) folder.mkdirs();
        String key = thumbnailKey(file);
        return new File(folder, Integer.toHexString(key.hashCode()) + ".jpg");
    }

    private String thumbnailKey(EZDeviceRecordFile file) {
        long start = file.getStartTime() == null ? 0L : file.getStartTime().getTimeInMillis();
        long stop = file.getStopTime() == null ? 0L : file.getStopTime().getTimeInMillis();
        return serial + "_" + start + "_" + stop;
    }

    private void cacheCurrentThumbnail() {
        if (thumbnailCapturePending || currentSdFile == null || playbackPlayer == null) return;
        EZDeviceRecordFile capturedFile = currentSdFile;
        File target = thumbnailFile(capturedFile);
        if (target.isFile()) return;
        thumbnailCapturePending = true;
        main.postDelayed(() -> {
            try {
                Bitmap bitmap = playbackPlayer == null ? null : playbackPlayer.capturePicture();
                if (bitmap != null) try (FileOutputStream out = new FileOutputStream(target)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out);
                    ImageView view = thumbnailViews.get(thumbnailKey(capturedFile));
                    if (view != null) main.post(() -> view.setImageURI(Uri.fromFile(target)));
                }
            } catch (Exception ignored) { }
            thumbnailCapturePending = false;
        }, 700);
    }

    private void loadImage(String url, ImageView target) {
        try {
            Bitmap bitmap;
            java.net.URLConnection connection = new java.net.URL(url).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            try (InputStream in = connection.getInputStream()) { bitmap = android.graphics.BitmapFactory.decodeStream(in); }
            if (bitmap != null) main.post(() -> target.setImageBitmap(bitmap));
        } catch (Exception ignored) { }
    }

    private void stopPlayer() {
        main.removeCallbacks(firstFrameTimeout);
        try { if (talking && talkPlayer != null) talkPlayer.stopVoiceTalk(); } catch (Exception ignored) { }
        try { if (recording && livePlayer != null) livePlayer.stopLocalRecord(); } catch (Exception ignored) { }
        try { if (playbackMode && playbackPlayer != null) playbackPlayer.stopPlayback(); } catch (Exception ignored) { }
        try { if (playing && livePlayer != null) livePlayer.stopRealPlay(); } catch (Exception ignored) { }
        try { if (livePlayer != null) livePlayer.release(); } catch (Exception ignored) { }
        try { if (playbackPlayer != null) playbackPlayer.release(); } catch (Exception ignored) { }
        try { if (talkPlayer != null) talkPlayer.release(); } catch (Exception ignored) { }
        livePlayer = null; playbackPlayer = null; talkPlayer = null; player = null;
        talking = false; recording = false; playing = false; playbackMode = false;
    }

    @Override protected void onStop() { stopPlayer(); super.onStop(); }

    @Override protected void onStart() {
        super.onStart();
        if (surfaceReady) main.post(this::startLive);
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(firstFrameTimeout);
        stopPlayer();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void addEqual(LinearLayout parent, Button child) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1f);
        p.leftMargin = dp(4); p.rightMargin = dp(4);
        parent.addView(child, p);
    }

    private void applySystemInsets(View view) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                if (fullscreen && v == screenScroll) {
                    v.setPadding(0, 0, 0, 0);
                } else {
                    int bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
                    v.setPadding(bars.left, bars.top, bars.right, bottom);
                }
                return insets;
            });
        }
    }

    private String qualityName(int level) {
        if (level == 0) return "Fluent";
        if (level == 1) return "Balanced";
        return "HD";
    }

    private void showVideoQualityMenu() {
        if (playbackMode) {
            Toast.makeText(this, "Video quality can be changed in live view", Toast.LENGTH_SHORT).show();
            return;
        }
        PopupMenu menu = new PopupMenu(this, qualityButton);
        String[] names = {"Fluent", "Balanced", "HD"};
        for (int level = 0; level < names.length; level++) {
            menu.getMenu().add(0, level, level, names[level]).setCheckable(true)
                    .setChecked(level == videoQuality);
        }
        menu.setOnMenuItemClickListener(item -> {
            applyVideoQuality(item.getItemId());
            return true;
        });
        menu.show();
    }

    private void toggleLiveAudio() {
        if (playbackMode || livePlayer == null) {
            Toast.makeText(this, "Audio output is available during live view", Toast.LENGTH_SHORT).show();
            return;
        }
        liveAudioEnabled = !liveAudioEnabled;
        boolean applied = liveAudioEnabled ? livePlayer.openSound() : livePlayer.closeSound();
        if (!applied) {
            liveAudioEnabled = !liveAudioEnabled;
            Toast.makeText(this, "Could not change live audio", Toast.LENGTH_SHORT).show();
            return;
        }
        audioButton.setText(liveAudioEnabled ? "Audio on" : "Audio off");
    }

    private void applyVideoQuality(int level) {
        if (level < 0 || level > 2 || level == videoQuality) return;
        if (talking) {
            Toast.makeText(this, "End two-way talk before changing quality", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean resumeLive = playing && livePlayer != null;
        if (resumeLive) {
            try { livePlayer.stopRealPlay(); } catch (Exception ignored) { }
            playing = false;
        }
        qualityButton.setEnabled(false);
        state.setText("Changing video quality to " + qualityName(level) + "…");
        worker.execute(() -> {
            boolean success = false;
            String failure = "The camera could not change video quality.";
            try {
                success = EZGlobalSDK.getInstance().setVideoLevel(serial, cameraNo, level);
            } catch (BaseException e) {
                failure = "Could not change video quality (EZVIZ error " + e.getErrorCode() + ").";
            } catch (Exception ignored) { }
            boolean result = success;
            String error = failure;
            main.post(() -> {
                if (isFinishing()) return;
                qualityButton.setEnabled(true);
                if (result) {
                    videoQuality = level;
                    getSharedPreferences("liza_settings", MODE_PRIVATE).edit()
                            .putInt("quality_" + serial, level).apply();
                    qualityButton.setText("Quality · " + qualityName(level));
                    state.setText("Video quality · " + qualityName(level));
                } else state.setText(error);
                if (resumeLive) startLive();
            });
        });
    }

    private EZPlayer createPlayer() {
        EZPlayer created = EZGlobalSDK.getInstance().createPlayer(serial, cameraNo);
        if (created == null) throw new IllegalStateException("EZVIZ could not create a player");
        created.setHandler(playerHandler);
        if (surface.getSurfaceTexture() == null || !created.setSurfaceEx(surface.getSurfaceTexture()))
            throw new IllegalStateException("Could not attach the video display");
        String verifyCode = new SecureVault(this).get("verify_" + serial);
        if (verifyCode != null) created.setPlayVerifyCode(verifyCode);
        return created;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }
    private Button button(String value, int fill, int color) {
        Button v = new Button(this); v.setText(value); v.setTextColor(color); v.setTextSize(14); v.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(fill); bg.setCornerRadius(dp(14)); v.setBackground(bg); return v;
    }
    private GradientDrawable rounded(int fill, int radius) {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(fill); bg.setCornerRadius(dp(radius)); return bg;
    }
    private LinearLayout.LayoutParams top(int margin) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(margin); return p; }
    private LinearLayout.LayoutParams height(int h) { return new LinearLayout.LayoutParams(-1, dp(h)); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
