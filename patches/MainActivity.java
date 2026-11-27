package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ChangedPackages;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.SoundEffectConstants;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }


    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "com.gathin.tvhome.action.LAUNCHER_SETTINGS";
    /** Sent by the "library" action word of app_keys: open the App library over Home (needs app_library). */
    static final String ACTION_APP_LIBRARY = "com.gathin.tvhome.action.APP_LIBRARY";
    /** Sent by HomeKeyService on Apple TV style double-tap Home: open the Recents App Switcher over Home. */
    static final String ACTION_RECENTS = "com.gathin.tvhome.action.RECENTS";
    /** Opens the launcher's ambient screensaver (ClockActivity). */
    static final String ACTION_SCREENSAVER = "com.gathin.tvhome.action.SCREENSAVER";
    /** Opens the Apple tvOS style Control Center overlay. */
    static final String ACTION_CONTROL_CENTER = "com.gathin.tvhome.action.CONTROL_CENTER";
    /** The system input switcher, forwarded here by InputsRequestActivity while handle_view_inputs is on. */
    static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    private static final long MINUTE_MS = 60_000L;
    private static final long STATS_MS = 10_000L;
    /**
     * 2025-01-01. TVs without a clock battery boot at the epoch (08:00 in China) and keep it until network
     * time arrives; the clock, date and greeting stay hidden before then.
     */
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;
    private String liveTvPkg;
    private boolean homeIgnoreChannelKeys;
    /** Launcher settings' change count this Home was built with (see onResume). */
    private long settingsRevision;
    /** Idle time on Home before ClockActivity; 0 is off (config.xml, home_idle_clock_minutes). */
    private long idleClockMs;
    /** Back on Home opens ClockActivity (config.xml, back_opens_clock). */
    private boolean backOpensClock;
    private final Runnable showIdleClock = this::openScreensaverAnimated;
    private String liveTvAction;
    private String liveTvInputId;
    /** The app library is on (config.xml, app_library): only then can the tile menu take an app off Home. */
    private boolean appLibraryEnabled;
    /** The same opt-in menu is available from either hold-OK or the remote's Menu key. */
    private boolean tileMenuEnabled;
    private String inputAction;
    private List<String> inputSources;
    private String inputUriScheme;
    private String sourcePanelService;
    private String sourcePanelAction;
    private String sourcePanelExtra;
    private boolean coocaaSources;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private FrameLayout homeBackdropLayer;
    private WallpaperPlayer wallpaperPlayer;
    private DockDecoration dockDecoration;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    /** The top shelf: heroName, plus the cinematic shelf's "Last opened" line and dock position dots. */
    private View hero;
    private TextView heroMeta;
    private LinearLayout heroDots;
    /** Cinematic top shelf (config.xml, cinematic_home): the focused dock app's artwork behind a larger shelf. */
    private boolean cinematic;
    /** config.xml ambient_glow, overridable by a theme. Off: the glow view is gone and no fade or colour animator
     *  is started for it, so a focus move costs nothing extra on a launcher that runs all day. */
    private boolean ambientGlow;
    /** config.xml app_glow: the highlight takes the focused app's colour on every tile. */
    private boolean appGlow;
    /** config.xml glow_style: the shape setAmbient paints the glow mask in. */
    private String glowStyle;
    /** config.xml cinematic_white_focus: the cinematic shelf keeps the white highlight. */
    private boolean cinematicWhiteFocus;
    private ImageView heroArt;
    /** config.xml cinematic_dots: false leaves the dock position dots out of the cinematic shelf. */
    private boolean heroDotsShown = true;
    /** The artwork's opacity behind the cinematic shelf (config.xml cinematic_art); 0 leaves it out. */
    private float heroArtAlpha = 0.5f;
    /** Softened artwork per app (HeroArt, about 5 KB each), made off the UI thread the first time its dock tile is focused. */
    private final Map<String, Bitmap> heroArtCache = new HashMap<>();
    private final Set<String> heroArtInFlight = new HashSet<>();
    private int heroArtGeneration;
    private static volatile java.util.concurrent.ExecutorService heroArtWorker;
    /** The app whose artwork heroArt shows, so focusing it again only fades it back in. */
    private String heroArtKey;
    /** The focus glow's colour while the cinematic shelf tints it, 0 for plain white glass. */
    private int glassAccent;
    private int glassSecondaryAccent;
    /** True while the shared GlassView is currently tracking a top-bar pill instead of a grid tile. */
    private boolean glassOnTopBar;
    /** Cached live accent for the Wi-Fi top-bar pill (green / amber / red / 0) for the Fresnel focus ring. */
    private int wifiFocusAccent;
    /** Cached live accent for the Vinyl Disc media pill. */
    private int mediaFocusAccent;
    private int ambientColor = -1;
    private ValueAnimator ambientAnimator;
    private RenderEffect cachedBackdropBlur;
    private FrameLayout backdropBlurVeilHost;
    private ImageView backdropBlurVeil;
    private boolean backdropBlurred;
    private boolean homeBackdropBlurEnabled = true;
    private boolean mediaMiniPlayerPending;
    private int lastHeroPosition = -1;
    /** config.xml glow_snap: the glow takes a new colour in one frame instead of the dip-and-fade. */
    private boolean glowSnap;
    private boolean heroShown;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private View clockBlock;
    private View btnInputs;
    /** config show_inputs_pill: false keeps the Inputs pill hidden even when the TV has inputs. */
    private boolean showInputsPill = true;
    private TextView btnMedia;
    private VinylDiscDrawable vinylDisc;
    private MediaSessionTracker mediaTracker;
    private MediaMiniPlayerDialog mediaMiniPlayer;
    private View btnRecents;
    private Map<String, String> mediaAppLabels = Collections.emptyMap();
    private TextView btnWifi;
    /** The network pill's icon as last set, so a refresh that changes nothing does not lay the top bar out again. */
    private int wifiIcon;
    /** The pills have been filled in once; later resumes refresh them after the first frame. */
    private boolean pillsShown;
    private TextView shamsiDate;
    /** lunar_date: the lunisolar date under the date line, and the time zone it is computed for. */
    private TextView lunarDate;
    private boolean lunar;
    private int lunarOffset;
    private TextView stats;
    private List<RaceWeekend.Race> raceCalendar;
    private View racePanel;
    private TextView raceKicker, raceHeadline, raceSessions;
    /** game_day: each team's next game from an add-on, rotating in the race panel; null when the key is off. */
    private GameDay gameDay;
    private View raceBar;
    private LinearLayout racePager;
    private long rotationStart;
    private long lastEndedRead;
    private final Runnable turnTick = this::updatePanel;
    /** config.xml show_stats, overridable by a theme. Off: the line is gone and nothing is read. */
    private boolean showStats;
    /** config.xml inputs_connected_only: the Inputs menu lists only connected inputs, the rest behind "More inputs". */
    private boolean inputsConnectedOnly;
    /** config.xml inputs_cec_state_only: only CEC device rows show a connection state, bare HDMI ports none. */
    private boolean inputsCecStateOnly;
    private boolean shamsi;
    /** greeting_name: said after the greeting, which is shown in capitals. */
    private String greetingName = "";
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    /** vpn_pill: the VPN shown as its own pill (btnVpn) instead of a badge in the network pill. Off with top_menu. */
    private boolean vpnPill;
    private TextView btnVpn;
    /** The VpnBadge pill state btnVpn shows, so a refresh that changes nothing does not restyle it. */
    private int vpnPillState = VpnBadge.PILL_HIDDEN;
    /** icon_only_vpn: the VPN pill is a round lock button; off, it keeps the plain glass look saved here. */
    private boolean iconOnlyVpn;
    private Drawable.ConstantState vpnPlainBackground;
    private ColorStateList vpnPlainInk;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    /** The same for springX, when single_row scrolls the grid sideways. */
    private float glassScrollShiftX;
    private int glassCardW;   // width of the tile the glass was last fitted to
    /**
     * The focused tile's rect in root coordinates as {@link #onTileFocused} last fitted the glass to it. The size
     * alone cannot say whether the glass is on its tile: a cold start can run onTileFocused once the tile has its
     * final width but before the grid has its top padding, which leaves the right size at the old Y.
     */
    private final Rect glassCardRect = new Rect();
    private boolean glassShown;
    private final Rect tileRect = new Rect();
    private final Rect revealRect = new Rect();
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** config settings_key_keycodes / settings_key_panel, so the Settings button works on Home without HomeKeyService. */
    private int[] settingsKeycodes = new int[0];
    private boolean settingsKeyPanel;
    private boolean settingsKeyClearTask;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** config.xml dock_row: the first row is a separate dock (band, parked low, top shelf) rather than plain grid. */
    private boolean dockRow;
    /** True when the dock draws the live blurred wallpaper backdrop (dockRow && dockGlass). */
    private boolean dockBackdrop;
    /** config single_row: every app in the dock's row, scrolling sideways, no grid below. */
    private boolean singleRow;
    /** The highlight springs past its target (config.xml, focus_overshoot); false goes straight there. */
    private boolean focusOvershoot;
    /** Festive skin from the theme (see Festival); applied while it is active today. */
    private Festival festival = Festival.NONE;
    private boolean festive;
    /** The network pill's festive light: the diya, or the jack-o'-lantern for Halloween. */
    private FestiveLamp lamp;
    private BellDrawable bell;
    private MarigoldDrawable marigold;
    private int greetingColor;
    /** The day the petal shower last played, so it greets the first Home of each day only. */
    private static LocalDate lastPetalShower;
    /** boot_to_live_tv: open the Live TV app on the first Home of each boot. */
    private boolean bootToLiveTv;
    /** The input in the Live TV tile's launch_actions ("input:<id>"), opened at power-on instead of the Live TV app. */
    private String bootInputId;
    /** open_on_start_choice: the app picked in Launcher settings may open on the first Home of each boot. */
    private boolean openOnStartChoice;
    /** open_on_start_network_wait, in ms; the boot's app while it waits for the network, and until when. */
    private long openOnStartWaitMs;
    /** open_on_start_on_wake: wakeReceiver is registered for this activity's life. */
    private boolean openOnStartOnWake;
    private volatile Intent startPending;
    private long startDeadline;
    /** Opens the boot's app once the network is validated (networkCallback posts this) or the wait is over. */
    private final Runnable openWhenOnline = new Runnable() {
        @Override
        public void run() {
            if (startPending == null) return;
            if (!OpenOnStart.opensNow(OpenOnStart.online(MainActivity.this), SystemClock.elapsedRealtime(),
                    startDeadline)) return;
            handler.removeCallbacks(this);   // the deadline's run, when the network came first
            Intent i = startPending;
            startPending = null;
            open(i);
        }
    };
    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            openStartAppOnWake();
        }
    };
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private boolean settingsRecreationPending;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Between onStop and onStart: Home was fully covered by an app, not just by the translucent quick panel. */
    private boolean stopped;
    /** Between onStart and onStop: Home is (at least partly) on screen. */
    private boolean started;
    /**
     * Between onResume and onPause: Home is in front. The idle clock only runs then; a key's ACTION_UP can still
     * reach Home after onPause, when that key opened another app, and must not start the clock over the app.
     */
    private boolean resumed;
    /** Some Home between onResume and onPause, for HomeKeyService's second look after a stock_home_redirect. */
    static volatile boolean inFront;
    /** Rebuild the tiles on the way back to Home only when apps changed meanwhile (TileRefresh). */
    private final TileRefresh tileRefresh = new TileRefresh();
    /** PackageManager's change counter at the last rebuild, to catch a package broadcast that never arrived. */
    private int packageSequence;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;
    /** Reorder mode is on (top-bar button). */
    private boolean reordering;
    /** Key of the tile being carried in reorder mode, or null. */
    private String movingKey;
    /** Tile order when reorder mode started, to save only when something moved. */
    private List<String> orderAtStart;
    /**
     * fill_dock false: tiles in the dock when reorder mode started, plus those carried or swapped into it since
     * (#499). A grid app that only slid into a free slot, which reorder mode does not show, is not one of them.
     */
    private Set<String> dockInSession;
    /** fill_dock false: how many dock slots held an app when reorder mode started. */
    private int dockedAtStart;
    private View btnReorder;
    /** dock_apps as loaded (config.xml or a theme): a saved Order only applies while these are unchanged. */
    private List<String> dockApps;
    /** fill_dock false: the dock can have empty slots, which reorder mode leaves out and restores afterwards. */
    private boolean fillDockLeavesSlots;
    /** Edit-dock mode (Settings menu): a reorder session that swaps one chosen app into one dock place. */
    private boolean dockEditing;
    /** Dock place being changed in edit-dock mode, or -1 while the place is still being chosen. */
    private int dockEditSlot = -1;
    /** Tile menu's "Add to dock": the app waiting for a dock place in edit-dock mode, or null. */
    private String dockEditAppKey;
    private FolderDialog openFolder;
    private Dialog folderPrompt;
    private boolean libraryFocusPending;
    private RecyclerView.OnScrollListener pendingFocusScroll;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        /** wifi_status_warnings follows validation; signal-strength updates also land here, so skip those. */
        private boolean lastValidated;

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
            boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            // open_on_start_network_wait: the boot's app opens on the first validated network.
            if (validated && startPending != null) handler.post(openWhenOnline);
            if (validated == lastValidated) return;
            lastValidated = validated;
            if (wifiNoInternetTint != null) handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final BroadcastReceiver addonDismissReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ExpressiveGlass.ACTION_ADDON_DISMISSING.equals(intent.getAction())) {
                fadeOutAddonBlurNow();
            }
        }
    };
    private boolean addonDismissReceiverRegistered;

    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            // In the background this only marks the tiles stale; onResume rebuilds them once.
            if (!tileRefresh.onPackagesChanged()) return;
            reloadApps(false);
        }
    };
    // Right after boot the clock is often wrong until network time arrives; don't wait a minute to catch up.
    private final IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClockVisibility();
            clockRefresh.start();   // the wall clock was set: line the redraw up with the new minute
        }
    };
    /** Keeps the Home clocks moving on Android before 12 when the firmware stops ACTION_TIME_TICK (see ClockRefresh). */
    private ClockRefresh clockRefresh;
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updatePanel();
            // The pill follows the network callbacks and each return to Home; only the festive light follows the
            // signal level, so only it needs this minute's binder calls (about 7 ms on the UI thread).
            if (festive) updateWifiLabel();
            refreshAutoGlassStyleIfNeeded();
            sky.update();
            wallpaperPlayer.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    private java.util.Map<Integer, String> homeAppKeys = java.util.Collections.emptyMap();
    /** clock_format, for the race panel's session times (the TextClocks take it in onCreate). */
    private String clockFormat = "system";
    private java.util.Map<Integer, String> homeDoubleAppKeys = java.util.Collections.emptyMap();
    private java.util.Map<Integer, String> homeHoldAppKeys = java.util.Collections.emptyMap();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentInstance = this;
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        homeAppKeys = RemoteKeys.parseAppKeys(cfg.appKeys);
        clockFormat = cfg.clockFormat;
        ClockFormat.apply(getWindow().getDecorView(), clockFormat);
        homeDoubleAppKeys = RemoteKeys.parseDoubleAppKeys(cfg.appKeys);
        homeHoldAppKeys = RemoteKeys.parseHoldAppKeys(cfg.appKeys);
        // restore_key_services: after a force stop, put back the remote's accessibility helpers that Android took
        // off the list (needs WRITE_SECURE_SETTINGS, granted by build-and-install with RESTORE_KEY_SERVICES=1).
        if (cfg.restoreKeyServices) ServiceRepair.restore(this);
        NightAudioController.restore(this);
        settingsRevision = settingsRevision();
        applyViewInputsHandler(cfg.handleViewInputs);
        settingsKeycodes = cfg.settingsKeycodes;
        settingsKeyPanel = cfg.settingsKeyPanel;
        settingsKeyClearTask = cfg.settingsKeyClearTask;
        COLUMNS = cfg.columns;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        dockRow = cfg.dockRow;
        singleRow = cfg.singleRow;
        focusOvershoot = cfg.focusOvershoot;
        festival = cfg.festival;
        festive = festival.active(LocalDate.now());
        liveTvPkg = cfg.liveTvPackage;
        homeIgnoreChannelKeys = cfg.homeIgnoreChannelKeys;
        idleClockMs = cfg.homeIdleClockMinutes * MINUTE_MS;
        backOpensClock = cfg.backOpensClock;
        bootToLiveTv = cfg.bootToLiveTv;
        bootInputId = LaunchActions.inputId(cfg.launchActions.get(cfg.liveTvPackage));
        openOnStartChoice = cfg.openOnStartChoice;
        openOnStartWaitMs = cfg.openOnStartNetworkWait * 1000L;
        openOnStartOnWake = OpenOnStart.onWake(cfg.openOnStartChoice, cfg.openOnStartOnWake);
        liveTvAction = cfg.liveTvAction;
        liveTvInputId = cfg.liveTvInputId;
        appLibraryEnabled = cfg.appLibrary;
        tileMenuEnabled = cfg.tileMenu;
        inputAction = cfg.inputAction;
        inputSources = cfg.inputSources;
        inputUriScheme = cfg.inputUriScheme;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;
        sourcePanelExtra = cfg.sourcePanelExtra;
        coocaaSources = cfg.coocaaSources;

        root = findViewById(R.id.root);
        PointerSupport.install(root);
        UiClickSound.attachFocusSound(root);
        root.setBackgroundColor(cfg.backgroundColor);
        // root fills the window with an opaque colour (Config keeps background_color opaque), so the window's own
        // black under it is a second full-screen fill on every frame for nothing. The starting window still uses
        // the theme's background, so launch looks the same.
        getWindow().setBackgroundDrawable(null);
        // Sync glass optics, material style & sharp-Home preference BEFORE WallpaperPlayer decodes the first wallpaper!
        ExpressiveGlass.setGlassStyle(FeaturePrefs.resolveGlassStyle(this));
        ExpressiveGlass.setHomeLiquidOpticsEnabled(FeaturePrefs.isHomeLiquidOpticsEnabled(this));
        homeBackdropBlurEnabled = FeaturePrefs.isHomeBackdropBlurEnabled(this);
        ExpressiveGlass.setHomeBackdropBlurEnabled(homeBackdropBlurEnabled);
        // Group Wallpaper + Sky + HeroArt + Ambient + HeroScrim into one hardware-cached layer when settled,
        // collapsing 3 full-screen 1920x1080 alpha-blended passes (6.2M px) into 1 opaque texture blit!
        homeBackdropLayer = wrapHomeBackdropLayer(root);
        wallpaperPlayer = new WallpaperPlayer(this, root, homeBackdropLayer, cfg);
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        glass = findViewById(R.id.glass);
        glass.setStyle(cfg.glassStyle);
        glass.setLiquidEnabled(animations && !reducedMotion);
        if (festive) glass.setAccent(festival.accentColor());
        ambient = findViewById(R.id.ambient);
        // The glow is one bitmap, so its alpha can be applied while drawing it. Otherwise every frame of
        // a fade renders it into a full-screen offscreen buffer first.
        ambient.forceHasOverlappingRendering(false);
        ambientGlow = cfg.ambientGlow;
        glowSnap = cfg.glowSnap;
        appGlow = cfg.appGlow;
        if (!ambientGlow) ambient.setVisibility(View.GONE);
        heroName = findViewById(R.id.heroName);
        hero = findViewById(R.id.hero);
        // The hero is now a group (name, and the cinematic lines): without this every fade of the default
        // shelf would render it through an offscreen buffer first, as the glow above notes.
        hero.forceHasOverlappingRendering(false);
        heroMeta = findViewById(R.id.heroMeta);
        heroDots = findViewById(R.id.heroDots);
        heroArt = findViewById(R.id.heroArt);
        cinematic = cfg.cinematicHome;
        glowStyle = cfg.glowStyle;
        heroDotsShown = cfg.cinematicDots;
        cinematicWhiteFocus = cfg.cinematicWhiteFocus;
        heroArtAlpha = cfg.cinematicArt / 100f;
        if (cinematic) setUpCinematicShelf(cfg.cinematicScrim);
        // dock_title false: the name above the dock (and the cinematic lines under it) never shows; the glow
        // still follows the dock.
        if (!cfg.dockTitle) hero.setVisibility(View.GONE);
        greeting = findViewById(R.id.greeting);
        clockBlock = findViewById(R.id.leftBlock);
        clockRefresh = new ClockRefresh(clockBlock);
        greetingColor = greeting.getCurrentTextColor();
        if (!cfg.showGreeting) greeting.setVisibility(View.GONE);
        // Keep the other header layouts as designed: their extra lines change the block's centre.
        if (!cfg.showGreeting && !cfg.dateBelowClock && !cfg.shamsiDate && !cfg.lunarDate
                && !cfg.topMenu && !getResources().getBoolean(R.bool.rtl_menu_bar)) {
            centerCompactHeader();
        }
        if (cfg.headerWhite) {
            // The clock's own white for the date and the line before it, instead of their dimmed whites.
            ((TextView) findViewById(R.id.date)).setTextColor(Color.WHITE);
            findViewById(R.id.clockDivider).setBackgroundColor(Color.WHITE);
        }
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            // Center Hebrew greetings over the numeric time, independent of a visible AM/PM label. The clock's row
            // is watched too: date_below_clock can move the whole row right under a wider date.
            View clock = findViewById(R.id.clock);
            View clockRow = (View) clock.getParent();
            View.OnLayoutChangeListener centerGreeting = (v, l, t, right, bottom, ol, ot, or, ob) -> {
                ViewGroup.LayoutParams params = greeting.getLayoutParams();
                if (params.width != clock.getWidth()) {
                    params.width = clock.getWidth();
                    greeting.setLayoutParams(params);
                }
                greeting.setTranslationX(clockRow.getLeft() + clock.getLeft());
            };
            clock.addOnLayoutChangeListener(centerGreeting);
            clockRow.addOnLayoutChangeListener(centerGreeting);
        }
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        stats = findViewById(R.id.stats);
        raceCalendar = cfg.raceCalendar;
        racePanel = findViewById(R.id.racePanel);
        raceKicker = findViewById(R.id.raceKicker);
        raceHeadline = findViewById(R.id.raceHeadline);
        raceSessions = findViewById(R.id.raceSessions);
        raceBar = findViewById(R.id.raceBar);
        racePager = findViewById(R.id.racePager);
        gameDay = GameDay.create(this, cfg.gameDay, handler, this::updatePanel);
        // The rotation runs on the clock that never jumps: setting the TV's time must not stall or stretch a turn.
        rotationStart = SystemClock.elapsedRealtime();
        showStats = cfg.showStats;
        inputsConnectedOnly = cfg.inputsConnectedOnly;
        inputsCecStateOnly = cfg.inputsCecStateOnly;
        shamsi = cfg.shamsiDate;
        lunarDate = findViewById(R.id.lunarDate);
        lunar = cfg.lunarDate;
        lunarOffset = Lunar.offsetFor(cfg.lunarUtcOffset, Locale.getDefault());
        if (lunar) lunarDate.setVisibility(View.VISIBLE);
        greetingName = cfg.greetingName;
        if (cfg.dateBelowClock) moveDateBelowClock();
        if (cfg.dateBelowClock && cfg.iconOnlyButtons && !cfg.topMenu
                && !getResources().getBoolean(R.bool.rtl_menu_bar)) {
            alignDateBelowIconsWithClock();
        }
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            lunarDate.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        vpnPill = cfg.vpnPill && !cfg.topMenu;
        btnVpn = findViewById(R.id.btnVpn);
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);

        grid = findViewById(R.id.grid);
        rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);
        // Mirror only the app grid; the header and other locales keep their own layouts.
        // single_row: one span laid out sideways, so the same adapter, glass and dock band serve a scrolling row.
        grid.setLayoutManager(new GridLayoutManager(this, singleRow ? 1 : COLUMNS,
                singleRow ? RecyclerView.HORIZONTAL : RecyclerView.VERTICAL, false) {
            @Override
            protected boolean isLayoutRTL() {
                return rtlAppGrid;
            }

            /**
             * Every "bring this tile on screen" scroll lands here, both RecyclerView's own on focus and
             * onTileFocused's. The stock version treats the grid's top padding as the visible top, but that
             * padding only pushes the dock down the screen: grid rows scroll up into it. With the stock rule,
             * Up into a row above the padding dragged that row down to the padding line, cut it off at the
             * bottom of the screen and could scroll the focused tile out entirely, dropping focus into the
             * top bar. Grid tiles now scroll just enough to show the rect between the grid's top edge and its
             * bottom; dock tiles are left to onTileFocused, which parks the dock row on the padding line.
             */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                if (singleRow) {
                    // Sideways: scroll just enough to bring the tile's cell inside the padding. Cells are a
                    // column wide, so the row moves a whole tile at a time and the tiles stay on the columns.
                    // The whole cell, not the card rect passed in: the card is narrower and centred in it.
                    int left = child.getLeft();
                    int right = child.getRight();
                    int dx;
                    if (left < getPaddingLeft()) dx = left - getPaddingLeft();
                    else if (right > getWidth() - getPaddingRight()) dx = right - (getWidth() - getPaddingRight());
                    else dx = 0;
                    if (dx == 0) return false;
                    if (immediate || reducedMotion || !animations) parent.scrollBy(dx, 0);
                    else parent.smoothScrollBy(dx, 0);
                    return true;
                }
                if (dockRow && getPosition(child) < COLUMNS) return false;
                int top = child.getTop() + rect.top;
                int bottom = top + rect.height();
                // A row already fully on screen stays put, even inside the bottom padding (the last row
                // rows_below_dock leaves visible ends there; nudging it up was the bug). A row that has to scroll
                // in still stops at the padding line, clear of the screen edge, as before.
                int limitBottom = getHeight() - getPaddingBottom();
                int dy;
                if (top < 0) dy = top;
                else if (bottom <= getHeight()) dy = 0;
                else dy = Math.min(top, bottom - limitBottom);
                if (dy == 0) return false;
                if (immediate || reducedMotion || !animations) parent.scrollBy(0, dy);
                else parent.smoothScrollBy(0, dy, Motion.easeOutExpo(), 250);
                return true;
            }
        });
        ((GridLayoutManager) grid.getLayoutManager()).setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return !singleRow && adapter != null && adapter.isLibraryAt(position) ? COLUMNS : 1; }
        });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        // Tiles that scroll out while reordering come back from the view cache without a rebind; set their
        // dimming again whenever one attaches, so alpha matches the current mode.
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View v) {
                v.setAlpha(tileAlpha(grid.getChildAdapterPosition(v)));
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View v) {
            }
        });
        if (dockRow) {
            dockBackdrop = cfg.dockGlass;
            dockDecoration = new DockDecoration(COLUMNS, density, cinematic, cfg.alwaysShowNames,
                    cfg.dockOpacity, cfg.dockGlass, cfg.dockEvenPadding, cfg.dockBand, singleRow, cfg.dockBandAll);
            if (cfg.dockGlass) {
                dockDecoration.setBackdropSources(root, wallpaperPlayer, heroArt, this::pillAmbientColor);
                dockDecoration.attachBackdropHost(grid);
            }
            grid.addItemDecoration(dockDecoration);
        }
        applyHomeTypography();
        wallpaperPlayer.setOnWallpaperChanged(() -> {
            markBackdropLayerAnimating(120L);
            cachedBackdropBlur = null;
            if (backdropBlurred && homeBackdropBlurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && root != null) {
                cachedBackdropBlur = ExpressiveGlass.createBackdropBlurEffect(
                        density, ExpressiveGlass.SHADE_BLUR_RADIUS_DP, true, Shader.TileMode.CLAMP);
                if (backdropBlurVeilHost != null && backdropBlurVeilHost.getVisibility() == View.VISIBLE
                        && backdropBlurVeil != null) {
                    ExpressiveGlass.RefractionBackdrop wp = ExpressiveGlass.homeWallpaperRefraction();
                    if (wp != null && !wp.sharp.isRecycled()) {
                        backdropBlurVeil.setImageBitmap(wp.sharp);
                    }
                    backdropBlurVeil.setRenderEffect(cachedBackdropBlur);
                } else {
                    root.setRenderEffect(cachedBackdropBlur);
                }
            }
            applyHomeTypography();
            if (dockDecoration != null) dockDecoration.invalidateBackdrop();
            if (grid != null) grid.invalidate();
            invalidateTopButtons();
            if (backdropBlurred && !homeBackdropBlurEnabled) {
                root.post(() -> {
                    if (!isDestroyed() && backdropBlurred && !homeBackdropBlurEnabled) {
                        ExpressiveGlass.captureHomeWindowForRefraction(getWindow(), MainActivity::refreshLiveLiquidPanels);
                    }
                });
            }
        });
        if (cfg.fadeRows) TopFade.attach(grid, 48 * density);
        // Reduced motion: the tiles are simply there, with no entrance (reloadApps skips it there too).
        if (animations && !reducedMotion) {
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        }
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        dockApps = cfg.dockApps;
        fillDockLeavesSlots = !cfg.fillDock;
        Order savedOrder = Order.load(Order.file(this));
        adapter.setSavedOrder(Order.ranks(savedOrder, dockApps), Order.docked(savedOrder, dockApps));
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the columns and the rows that must fit (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int tileW = AppAdapter.tileWidthPx(inner, h, COLUMNS, rowsBelowDock, cfg.dockTitle, cfg.dockRow, density);
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileW), density);
            int dockRow = itemH + (int) (AppAdapter.DOCK_EXTRA_DP * density);   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            // Keep the hero below the header when the library is enabled on a short screen,
            // even when the theme requests an extra visible row below the dock.
            int shelfClearance = cfg.appLibrary ? hero.getMeasuredHeight() + (int) (40 * density) : 0;
            int want = Math.max(shelfClearance, h - dockRow - gridRows - (int) (AppAdapter.BOTTOM_ROOM_DP * density));
            // Where the rows cannot fit even at the smallest tile (3 rows with the title on), the title keeps its
            // room above the dock and the last rows run off the bottom instead of the title drawing over the clock.
            if (MainActivity.this.dockRow && cfg.dockTitle && rowsBelowDock > 0) want = Math.max(want, (int) (AppAdapter.TITLE_ROOM_DP * density));
            // No dock to push down: the grid starts at the top, with room for the first row's zoom and glass.
            if (!MainActivity.this.dockRow) want = (int) (AppAdapter.TOP_ROOM_DP * density);
            // A single row never shows the names under its tiles (the title above the row names the app), so the
            // 7dp gap and 22dp label item_app.xml keeps for them would only lift the row: sit it that much lower.
            if (singleRow && !cfg.alwaysShowNames) want += (int) (29 * density);
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
                // A vertical grid parks the dock on the new padding line by scrolling (onTileFocused); a sideways
                // row cannot scroll vertically, and the layout request made from inside this layout pass is
                // dropped, so lay the row out again on the next frame. The glass and the title were placed for the
                // old position, so fit them again once it has moved.
                if (singleRow) grid.post(() -> {
                    grid.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            int pos = Math.max(0, Math.min(lastFocused, adapter.appCount() - 1));
                            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
                            View card = vh == null ? null : vh.itemView.findViewById(R.id.card);
                            if (card != null && card.isFocused()) onTileFocused(pos, card);
                        }
                    });
                    grid.requestLayout();
                });
            }
            // Cold start or column count > 4: the first focus can run before the grid has its final width or
            // padding, leaving the glass at the XML design size until a key is pressed.
            grid.post(this::refitGlass);
        });

        // The wallpaper caption sits on the screen, not in the grid: fade it while the grid is scrolled away from
        // the dock, so its text never lies over the tiles.
        final int captionScrollSlop = Math.round(12 * density);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (wallpaperPlayer == null) return;
                wallpaperPlayer.scrolledAway(rv.canScrollVertically(-1)
                        && rv.computeVerticalScrollOffset() > captionScrollSlop);
            }
        });

        // Keep the glass glued to its tile while the grid scrolls (skip when glass is on a top-bar pill).
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown || glassOnTopBar) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
                // single_row scrolls sideways: the same bookkeeping on the other axis.
                if (dx != 0) {
                    glassTargetX -= dx;
                    glassScrollShiftX -= dx;
                    glass.setTranslationX(glass.getTranslationX() - dx);
                }
            }
        });

        // game_day: the panel above the dock rides up with the cards instead of being covered by them, and fades
        // out by the time it reaches the clock. The grid's own offset is read, so a jump (Home, a restored focus)
        // lands right too.
        if (gameDay != null) {
            View clockBlock = findViewById(R.id.leftBlock);
            grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    int shift = rv.canScrollVertically(-1) ? rv.computeVerticalScrollOffset() : 0;
                    racePanel.setTranslationY(-shift);
                    int room = Math.max(1, racePanel.getTop() - clockBlock.getBottom());
                    racePanel.setAlpha(Math.max(0f, 1f - shift / (float) room));
                }
            });
        }

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentX") {
            @Override
            public float getValue(View v) {
                return v.getTranslationX() - glassScrollShiftX;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationX(value + glassScrollShiftX);
            }
        });
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                Motion.reduce(s);
            } else if (!focusOvershoot) {
                // Critically damped: glides straight onto the tile without swinging past it.
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 520f : 440f).setDampingRatio(1f));
            } else {
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 285f : 260f)
                        .setDampingRatio(advancedEffects ? 0.66f : 0.58f));
            }
        }
        springX.addUpdateListener((anim, value, velocity) -> glass.setVelocityX(velocity));
        springX.addEndListener((anim, canceled, value, velocity) -> glass.setVelocityX(0f));
        springY.addUpdateListener((anim, value, velocity) -> glass.setVelocityY(velocity));
        springY.addEndListener((anim, canceled, value, velocity) -> glass.setVelocityY(0f));

        btnInputs = findViewById(R.id.btnInputs);
        btnMedia = findViewById(R.id.btnMedia);
        mediaAppLabels = cfg.appLabels;
        if (btnMedia != null) {
            vinylDisc = new VinylDiscDrawable(density, reducedMotion);
            btnMedia.setCompoundDrawableTintList(null);
            btnMedia.setCompoundDrawablesRelativeWithIntrinsicBounds(vinylDisc, null, null, null);
            btnMedia.setOnClickListener(v -> showMediaMiniPlayer());
            btnMedia.setOnLongClickListener(v -> {
                if (mediaTracker != null) {
                    List<android.media.session.MediaController> list = mediaTracker.controllers();
                    if (!list.isEmpty()) mediaTracker.togglePlayPause(list.get(0));
                }
                return true;
            });
            mediaTracker = new MediaSessionTracker(this, this::onMediaSessionsUpdated);
        }
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnVpn.setOnClickListener(v -> openVpnPill());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        launcherSettingsButton = cfg.launcherSettingsButton;
        if (launcherSettingsButton) {
            ((TextView) btnSettings).setText(R.string.launcher_settings);
            ((TextView) btnSettings).setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_home_settings_pill, 0, 0, 0);
        }
        btnSettings.setOnClickListener(v -> {
            if (launcherSettingsButton) openLauncherSettingsAnimated();
            else QuickSettingsActivity.launch(this);
        });
        btnReorder = findViewById(R.id.btnReorder);
        btnReorder.setOnClickListener(v -> toggleReorder());
        btnReorder.setVisibility(cfg.reorderButton ? View.VISIBLE : View.GONE);
        if (!cfg.showNetworkPill) btnWifi.setVisibility(View.GONE);
        showInputsPill = cfg.showInputsPill;
        showInputsMenuRow = cfg.showInputsPill;
        btnLibraryTop = findViewById(R.id.btnLibraryTop);
        btnLibraryTop.setOnClickListener(v -> onLibraryRequested());
        btnLibraryTop.setVisibility(cfg.libraryButton && cfg.appLibrary ? View.VISIBLE : View.GONE);
        btnRecents = findViewById(R.id.btnRecents);
        if (btnRecents != null) {
            btnRecents.setOnClickListener(v -> showRecents());
        }
        addTopBarApps(cfg.topBarApps, cfg.launchActions);
        iconOnlyButtons = cfg.iconOnlyButtons;
        topMenu = cfg.topMenu;
        wifiIconOnly = iconOnlyButtons && cfg.wifiLabelWidth <= 0;
        if (cfg.wifiStatusColor) {
            wifiPlainTint = btnWifi.getCompoundDrawableTintList();
            wifiOnlineTint = getColorStateList(R.color.pill_icon_online);
            if (cfg.wifiStatusWarnings) {
                wifiNoInternetTint = getColorStateList(R.color.pill_icon_no_internet);
                wifiOfflineTint = getColorStateList(R.color.pill_icon_offline);
            }
        }
        LinearLayout topButtonsBar = findViewById(R.id.topButtons);
        if (cfg.topMenu) {
            // One gear: Inputs and Wi-Fi move into its dropdown. The Wi-Fi pill stays hidden but keeps its
            // label up to date, which the menu's Wi-Fi row shows.
            showInputsPill = false;
            btnInputs.setVisibility(View.GONE);
            btnWifi.setVisibility(View.GONE);
            ((TextView) btnSettings).setText(R.string.settings);
            ((TextView) btnSettings).setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_settings, 0, 0, 0);
            if (!iconOnlyButtons) makeIconOnly((TextView) btnSettings);
            btnSettings.setOnClickListener(v -> showTopMenu());
            // The lone gear sits at the header's middle, not on its bottom line like the row of pills.
            RelativeLayout.LayoutParams tlp = (RelativeLayout.LayoutParams) topButtonsBar.getLayoutParams();
            tlp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
            tlp.bottomMargin = 0;
            topButtonsBar.setLayoutParams(tlp);
            topButtonsBar.setGravity(Gravity.CENTER_VERTICAL);
        }
        if (iconOnlyButtons) {
            if (cfg.wifiLabelWidth > 0) btnWifi.setMaxWidth(Math.round(cfg.wifiLabelWidth * density));
            else makeIconOnly(btnWifi);
            makeIconOnly((TextView) btnSettings);
        }
        if (cfg.iconOnlyInputs) makeIconOnly((TextView) btnInputs);
        // Apply the same live frosted-glass blurred backdrop as the dock to EVERY top-right button.
        for (int i = 0; i < topButtonsBar.getChildCount(); i++) {
            View pill = topButtonsBar.getChildAt(i);
            pill.setBackground(new FrostedPillDrawable(pill, root, wallpaperPlayer, heroArt, this::pillAmbientColor, density, 21f * density));
        }
        topButtonsBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            invalidateTopButtons();
            if (glassOnTopBar && glassShown) {
                View cur = getCurrentFocus();
                if (cur != null && FocusRingController.isDescendantOf(cur, topButtonsBar)) {
                    topButtonsBar.post(() -> {
                        if (!isDestroyed() && glassOnTopBar && cur.hasFocus()) {
                            focusGlassOnControl(cur, false);
                        }
                    });
                }
            }
        });
        iconOnlyVpn = vpnPill && cfg.iconOnlyVpn;
        if (iconOnlyVpn) {
            vpnPlainBackground = null;
            vpnPlainInk = btnVpn.getCompoundDrawableTintList();
            makeIconOnly(btnVpn);
        }
        updateTopBarTintForGlassStyle();
        btnSettings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        boolean rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile Up came from, not wherever the focus finder lands.
                focusTile(singleRow ? lastFocused : lastFocused % COLUMNS);
                return true;
            }
            int firstButton = btnInputs.getVisibility() == View.VISIBLE ? R.id.btnInputs : firstTopButton().getId();
            int leftButton = rtlMenuBar ? R.id.btnSettings : firstButton;
            int rightButton = rtlMenuBar ? firstButton : R.id.btnSettings;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == leftButton;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == rightButton;
        };
        btnInputs.setOnKeyListener(pillKeys);
        if (btnMedia != null) btnMedia.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnVpn.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        btnReorder.setOnKeyListener(pillKeys);
        if (btnRecents != null) btnRecents.setOnKeyListener(pillKeys);
        btnLibraryTop.setOnKeyListener(pillKeys);
        for (View button : topAppButtons) button.setOnKeyListener(pillKeys);
        if (advancedEffects) {
            List<View> pills = new ArrayList<>(Arrays.asList(btnInputs, btnWifi, btnVpn, btnReorder, btnLibraryTop, btnSettings));
            if (btnMedia != null) pills.add(btnMedia);
            if (btnRecents != null) pills.add(btnRecents);
            pills.addAll(topAppButtons);
            for (View button : pills) {
                boolean round = button == btnMedia || button == btnRecents || button == btnReorder
                        || button == btnLibraryTop || iconOnlyButtons || topAppButtons.contains(button);
                Motion.attachPillFocus(button, round ? 1.10f : 1.06f, 2f, density, reducedMotion);
            }
        }
        if (savedInstanceState == null && animations && !reducedMotion) {
            for (View headerPart : new View[]{clockBlock, topButtonsBar}) {
                if (headerPart == null) continue;
                headerPart.setAlpha(0f);
                headerPart.setTranslationY(-10f * density);
                headerPart.animate().alpha(1f).translationY(0f)
                        .setDuration(360L).setInterpolator(Motion.easeOutExpo())
                        .setUpdateListener(headerPart == topButtonsBar ? a -> invalidateTopButtons() : null)
                        .start();
            }
        }
        if (festive) applyFestiveSkin((TextView) btnInputs, (TextView) btnSettings);
        root.post(() -> ExpressiveGlass.warmUpResources(density));

        // Hide the glass when focus leaves the tiles.
        // Taken off in onDestroy: recreate() keeps the window, and a listener left on it held every earlier Home.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(glassFocusListener);

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        // Registered for the activity's whole life, not only while it is started: an app installed or updated while
        // another app is in front must still mark the tiles stale, or Home would have to rebuild on every return.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(addonDismissReceiver, new IntentFilter(ExpressiveGlass.ACTION_ADDON_DISMISSING),
                    Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
            registerReceiver(addonDismissReceiver, new IntentFilter(ExpressiveGlass.ACTION_ADDON_DISMISSING));
        }
        addonDismissReceiverRegistered = true;
        if (openOnStartOnWake) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wakeReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(wakeReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
            }
        }

        // From the application, not this activity: on Android 12 a ConnectivityManager keeps the context it was
        // made with (its static sInstance, and the callback Messenger the system holds), so every recreate() left
        // the previous Home and its bitmaps behind.
        ConnectivityManager cm = getApplicationContext().getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
        // Not after recreate(): the launch intent survives it and would open the settings again.
        if (savedInstanceState == null && !openLauncherSettingsIfAsked(getIntent())
                && !openLibraryIfAsked(getIntent()) && !openRecentsIfAsked(getIntent())
                && !openScreensaverIfAsked(getIntent()) && !openControlCenterIfAsked(getIntent())) {
            openInputsIfAsked(getIntent());
        }
    }

    /**
     * handle_view_inputs: switch InputsRequestActivity, the VIEW_INPUTS handler, on or off. It is disabled in the
     * manifest, so a TV with its own input picker never sees it; only the sets that need it turn it on. Written
     * only when the state differs, so an ordinary start does not touch the package manager.
     */
    private void applyViewInputsHandler(boolean on) {
        ComponentName handler = new ComponentName(this, InputsRequestActivity.class);
        PackageManager pm = getPackageManager();
        int want = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        if (pm.getComponentEnabledSetting(handler) != want) {
            pm.setComponentEnabledSetting(handler, want, PackageManager.DONT_KILL_APP);
        }
    }

    /**
     * VIEW_INPUTS, forwarded by InputsRequestActivity while handle_view_inputs is on: open the Inputs menu once
     * the pill is on screen. The intent is replaced so a later recreate() does not
     * open it again. Nothing to do when the TV has no inputs to list (the pill is hidden then).
     * top_menu hides the pill, so the list hangs under the gear there, as in showInputs().
     */
    private boolean openInputsIfAsked(Intent intent) {
        if (intent == null || !ACTION_VIEW_INPUTS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        View anchor = topMenu ? findViewById(R.id.btnSettings) : btnInputs;
        if (anchor.getVisibility() != View.VISIBLE) return true;
        if (anchor.isLaidOut()) {
            // Android 9 delivers a new intent to a paused activity with a brief resume and pause around it, and
            // onPause() closes popups, so open the menu once that has run (HiSmartTV A4, Android TV 9).
            anchor.post(() -> {
                if (isFinishing() || anchor.getVisibility() != View.VISIBLE) return;
                anchor.requestFocus();
                showInputs();
            });
            return true;
        }
        // Cold start: the popup anchors to the pill (or the gear), so wait for the first layout.
        anchor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                anchor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                anchor.post(() -> {
                    if (isFinishing() || anchor.getVisibility() != View.VISIBLE) return;
                    anchor.requestFocus();
                    showInputs();
                });
            }
        });
        return true;
    }

    /**
     * ACTION_APP_LIBRARY, sent by the "library" action word: open the App library once Home is on screen. The intent
     * is replaced so a later recreate() does not open it again. With app_library off there is no library, so the
     * press only brings Home forward.
     */
    private boolean openLibraryIfAsked(Intent intent) {
        if (intent == null || !ACTION_APP_LIBRARY.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        if (!appLibraryEnabled) return true;
        getWindow().getDecorView().post(() -> {
            if (!isFinishing() && !isDestroyed()) onLibraryRequested();
        });
        return true;
    }

    private boolean openRecentsIfAsked(Intent intent) {
        if (intent == null || !ACTION_RECENTS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        getWindow().getDecorView().post(() -> {
            if (!isFinishing() && !isDestroyed()) showRecents();
        });
        return true;
    }

    private boolean openScreensaverIfAsked(Intent intent) {
        if (intent == null || !ACTION_SCREENSAVER.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        getWindow().getDecorView().post(() -> {
            if (!isFinishing() && !isDestroyed()) openScreensaverAnimated();
        });
        return true;
    }

    private boolean openControlCenterIfAsked(Intent intent) {
        if (intent == null || !ACTION_CONTROL_CENTER.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        getWindow().getDecorView().post(() -> {
            if (!isFinishing() && !isDestroyed()) QuickSettingsActivity.launch(this);
        });
        return true;
    }

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        openLauncherSettingsAnimated();
        return true;
    }

    @SuppressWarnings("deprecation")
    private void openScreensaverAnimated() {
        startActivity(new Intent(this, ClockActivity.class));
        overridePendingTransition(0, 0);
    }

    @SuppressWarnings("deprecation")
    private void openLauncherSettingsAnimated() {
        settingsLaunchPendingUntilMs = SystemClock.uptimeMillis() + 1500L;
        captureLiveHomeBackdropThen(() -> {
            if (isFinishing() || isDestroyed()) {
                settingsLaunchPendingUntilMs = 0L;
                return;
            }
            prepareSettingsBackdropBlur();
            try {
                startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
                overridePendingTransition(0, 0);
            } catch (ActivityNotFoundException | SecurityException e) {
                settingsLaunchPendingUntilMs = 0L;
                setSettingsOpen(false);
            }
        });
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openLauncherSettingsIfAsked(intent)) return;
        if (openLibraryIfAsked(intent)) return;
        if (openRecentsIfAsked(intent)) return;
        if (openScreensaverIfAsked(intent)) return;
        if (openControlCenterIfAsked(intent)) return;
        if (openInputsIfAsked(intent)) return;
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        if (mediaMiniPlayer != null) mediaMiniPlayer.close();
        if (openFolder != null) openFolder.close();
        if (gameDay != null && !stopped) gameDay.read();
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
        if (stopped) {
            tileRefresh.onHomeAgain();
        } else {
            // Still on screen (the quick panel was over it, or Home was already in front): the tiles are current,
            // so only go back to the first one; rebuilding would replay the entrance animation for nothing.
            grid.post(() -> focusTile(0));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        started = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timeReceiver, timeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(timeReceiver, timeFilter);
        }
        updateClockVisibility();
        clockRefresh.start();   // with timeReceiver: while Home is visible, paused or not
        tileRefresh.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        inFront = true;
        // A festival that started or ended while Home was in the background: rebuild with or without it.
        if (festive != festival.active(LocalDate.now())) {
            recreate();
            return;
        }
        // Something changed in Launcher settings: rebuild with it, however the settings were left. Only Back
        // delivered their RESULT_OK; Home (the remote's button) clears them off this task and the result comes
        // back cancelled, so a new wallpaper or layout waited for the next process start.
        if (Config.settingsChanged(settingsRevision, settingsRevision())) {
            recreate();
            return;
        }
        restartIdleClock();
        promptsPaused = false;
        if (grid.getAdapter() == null) grid.setAdapter(adapter);   // dropped by onTrimMemory
        switch (tileRefresh.onResume(packagesChangedSinceRebuild())) {
            case REBUILD:
                tilesReady = false;
                reloadApps(true);
                break;
            case REFOCUS:
                // The same tiles: only the focus goes back, as after a rebuild, without replaying the entrance.
                tilesReady = false;
                reloadApps(true, false, false);
                break;
            default:
                break;
        }
        if (openLiveTvAfterBoot()) return;
        updateGreeting();
        updatePanel();
        // Back from an app opened from its tile, focus is still on that tile, so the cinematic "Last opened" line kept
        // the time from before this launch ("2 days ago" right after opening it). Refresh it.
        if (cinematic && heroMeta != null && lastFocused >= 0 && lastFocused < adapter.appCount()) updateHeroMeta(lastFocused);
        // The network and Inputs pills keep their state while Home is in the background, and callbacks update them
        // when the network or the inputs change, so this refresh only catches anything missed. Its binder calls
        // (about 7 ms on a TCL C755, most of it WifiManager.getConnectionInfo) would hold up the first frame back
        // on Home, so after the first resume it runs once that frame is drawn.
        if (pillsShown) {
            refreshPillsAfterNextDraw();
        } else {
            updateWifiLabel();
            updateInputsButton();
            pillsShown = true;
        }
        if (showStats) startStats();
        if (gameDay != null) gameDay.start();
        sky.update();
        wallpaperPlayer.start();
        // Keep the settled homeBackdropLayer hardware cache intact on modal resume so unblurring
        // Home draws 1 opaque backdrop texture blit instead of 3 full-screen layers (6.2M px).
        if (homeBackdropLayer != null && homeBackdropLayer.getLayerType() != View.LAYER_TYPE_HARDWARE) {
            if (backdropBlurred || hasBackdropModal()) {
                setBackdropLayerCached(true);
            } else {
                markBackdropLayerAnimating(200L);
            }
        }
        if (vinylDisc != null) vinylDisc.setLifecycleActive(!backdropBlurred);
        if (mediaTracker != null) mediaTracker.start();
        ExpressiveGlass.setHomeLiquidOpticsEnabled(FeaturePrefs.isHomeLiquidOpticsEnabled(this));
        homeBackdropBlurEnabled = FeaturePrefs.isHomeBackdropBlurEnabled(this);
        ExpressiveGlass.setHomeBackdropBlurEnabled(homeBackdropBlurEnabled);
        refreshAutoGlassStyleIfNeeded();
        if (wallpaperPlayer != null) wallpaperPlayer.refreshSharpnessIfNeeded(!homeBackdropBlurEnabled);
        applyHomeTypography();
        if (!OverlayActivity.isLive() && !OverlayActivity.isLaunchInFlight()) overlayOpen = false;
        if (!QuickSettingsActivity.isLive() && !QuickSettingsActivity.isLaunchInFlight()) controlCenterOpen = false;
        if (!LauncherSettingsActivity.isLive() && !isSettingsLaunchInFlight()) settingsOpen = false;
        if (SystemClock.uptimeMillis() >= addonLaunchPendingUntilMs) addonModalOpen = false;
        if (SystemClock.uptimeMillis() >= folderPromptPendingUntilMs) folderPromptPendingUntilMs = 0L;
        if (hasBackdropModal()) updateBackdropBlur();
        else if (backdropBlurred) setBackdropBlur(false);
        handler.postDelayed(minuteTick, MINUTE_MS);
        if (festive && lamp != null) {
            lamp.start();
            LocalDate today = LocalDate.now();
            if (!reducedMotion && !today.equals(lastPetalShower)) {
                lastPetalShower = today;
                handler.removeCallbacks(petalShowerTask);
                handler.postDelayed(petalShowerTask, 450);
            }
        }
    }

    /** Launcher settings' change count (Config.SETTINGS_REVISION_KEY); onResume rebuilds Home when it moved. */
    private long settingsRevision() {
        return getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getLong(Config.SETTINGS_REVISION_KEY, 0);
    }

    /**
     * Only "Reorder tiles" in Launcher settings still answers with a result: Home rebuilds (the saved choices are
     * reloaded) and then enters reorder mode. Every other change is picked up in onResume through the change count.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST) {
            settingsLaunchPendingUntilMs = 0L;
            settingsOpen = false;
        }
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK
                && data != null && data.getBooleanExtra(LauncherSettingsActivity.EXTRA_REORDER, false)) {
            getIntent().putExtra(LauncherSettingsActivity.EXTRA_REORDER, true);
            // The old activity must not consume the pending action, or rebuild a second time in onResume.
            settingsRecreationPending = true;
            settingsRevision = settingsRevision();
            recreate();
        }
    }

    private void startPendingSettingsReorder() {
        if (settingsRecreationPending || !tilesReady || !hasWindowFocus() || isFinishing()
                || !getIntent().getBooleanExtra(LauncherSettingsActivity.EXTRA_REORDER, false)) return;
        getIntent().removeExtra(LauncherSettingsActivity.EXTRA_REORDER);
        toggleReorder();
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        reloadApps(animate, true);
    }

    /** @param rebuild false keeps the tiles as they are and only puts focus back (TileRefresh.Action.REFOCUS). */
    private void reloadApps(boolean animate, boolean rebuild) {
        reloadApps(animate, rebuild, true);
    }

    /**
     * @param entrance false puts focus back as an animated reload would, without replaying the tiles' entrance:
     *                 on a return to the same tiles it would only mark a change that didn't happen.
     */
    private void reloadApps(boolean animate, boolean rebuild, boolean entrance) {
        cancelPendingFocusScroll();
        RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
        final boolean restoreLibrary = adapter.isLibraryAt(adapter.appCount()) && footer != null
                && footer.itemView.findViewById(R.id.btnLibrary) != null
                && footer.itemView.findViewById(R.id.btnLibrary).hasFocus();
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = !overlayOpen && !controlCenterOpen && !settingsOpen && !addonModalOpen
                && openFolder == null && folderPrompt == null
                && mediaMiniPlayer == null && (animate || grid.hasFocus());
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            hero.setAlpha(0f);
            ambient.setAlpha(0f);
            heroArt.animate().cancel();
            heroArt.setAlpha(0f);
        }
        if (rebuild) reloadTiles();
        if (animate && entrance && animations && !reducedMotion) {
            grid.scheduleLayoutAnimation();
            // A rebuild redraws the grid anyway; without one the entrance needs a draw to start.
            if (!rebuild) grid.invalidate();
        }

        if (restoreFocus) {
            // Keep a focused footer at the end even when the app count changes.
            if (restoreLibrary) grid.post(this::focusLibrary);
            else restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.appCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            startPendingSettingsReorder();
            showCardPrompt();
        }
    }

    /** True when PackageManager reports apps changed since the last rebuild (one binder call). */
    private boolean packagesChangedSinceRebuild() {
        ChangedPackages changed = getPackageManager().getChangedPackages(packageSequence);
        return changed != null && !changed.getPackageNames().isEmpty();
    }

    private int currentPackageSequence(int since) {
        ChangedPackages changed = getPackageManager().getChangedPackages(since);
        return changed != null ? changed.getSequenceNumber() : since;
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        if (reordering) endReorder();   // an app installed or removed while reordering: keep the order so far
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        // An updated app may bring new artwork; the backdrop is cheap to make again.
        heroArtGeneration++;
        heroArtCache.clear();
        heroArtInFlight.clear();
        heroArtKey = null;
        // One LEANBACK query for both the tiles and the card checks.
        final List<ResolveInfo> leanback = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps,
                () -> Cards.load(Cards.storeFile(this)), leanback), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none), leanback);
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
        packageSequence = currentPackageSequence(packageSequence);
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final boolean returnToLibrary = libraryFocusPending;
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            if (returnToLibrary || libraryFocusPending) focusLibrary();
            else focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            startPendingSettingsReorder();
            showCardPrompt();
        });
    }

    /** Any key on Home restarts the countdown to the idle clock. */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        restartIdleClock();
    }

    private void restartIdleClock() {
        root.removeCallbacks(showIdleClock);
        if (resumed && idleClockMs > 0) root.postDelayed(showIdleClock, idleClockMs);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        inFront = false;
        // No UP reaches a paused Home, so a vendor-key hold it saw go down would still open its app later.
        // Only Home's own share is dropped: a key service that saw the same DOWN keeps its hold.
        RemoteKeys.release(this);
        cancelHoldOk();
        root.removeCallbacks(showIdleClock);
        endReorder();   // Home or another app while reordering keeps the order so far
        wallpaperPlayer.stop();
        if (vinylDisc != null) vinylDisc.setLifecycleActive(false);
        if (mediaMiniPlayer != null) mediaMiniPlayer.close();
        if (mediaTracker != null) mediaTracker.stop();
        stopAmbientAnimation();
        AppAdapter.stopAllFocusMotion();
        if (dockDecoration != null) dockDecoration.cancelRipple();
        cancelHeroDotAnimations(true);
        if (springX != null) springX.cancel();
        if (springY != null) springY.cancel();
        if (glass != null) {
            glass.resetMotion();
            glass.animate().cancel();
        }
        if (btnMedia != null) btnMedia.animate().cancel();
        // Dismiss in-window prompts/popups BEFORE clearing backdrop blur so their OnDismissListeners
        // never re-trigger setBackdropBlur(true) during onPause().
        if (cardPrompt != null) cardPrompt.dismiss();
        if (folderPrompt != null) folderPrompt.dismiss();
        if (openFolder != null) openFolder.close();
        if (openPopup != null) openPopup.dismiss();
        if (addonModalOpen) {
            // Once Home actually pauses for the launched glass add-on, clear the launch grace window so
            // returning from the add-on later immediately clears addonModalOpen in onResume().
            addonLaunchPendingUntilMs = 0L;
        }
        if (!controlCenterOpen && !overlayOpen && !addonModalOpen && !settingsOpen) {
            handler.removeCallbacks(clearBlurDeferred);
            handler.removeCallbacks(blurFadeFallback);
            if (root != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                root.setRenderEffect(null);
            }
            if (backdropBlurVeilHost != null) {
                backdropBlurVeilHost.animate().cancel();
                backdropBlurVeilHost.setAlpha(0f);
                backdropBlurVeilHost.setVisibility(View.GONE);
                backdropBlurVeilHost.setLayerType(View.LAYER_TYPE_NONE, null);
            }
            if (backdropBlurVeil != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) backdropBlurVeil.setRenderEffect(null);
                backdropBlurVeil.setImageDrawable(null);
            }
            backdropBlurred = false;
            ExpressiveGlass.clearHomeLiveBackdrop();
        }
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(openWhenOnline);
        handler.removeCallbacks(petalShowerTask);
        startPending = null;   // leaving Home before the network came back: the boot's app stays closed
        stopStats();
        if (gameDay != null) gameDay.stop();
        handler.removeCallbacks(turnTick);
        if (lamp != null) lamp.stop();
        cancelPendingFocusScroll();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        unregisterReceiver(timeReceiver);
        clockRefresh.stop();
        handler.removeCallbacks(clockCheck);
        handler.removeCallbacks(clearBlurDeferred);
        setBackdropLayerCached(false);
        // When Home is fully stopped (covered by an opaque Activity), release the 1080p blur FBO to save RAM.
        if (backdropBlurVeilHost != null && backdropBlurVeilHost.getVisibility() == View.VISIBLE) {
            backdropBlurVeilHost.animate().cancel();
            backdropBlurVeilHost.setAlpha(0f);
            backdropBlurVeilHost.setVisibility(View.GONE);
            backdropBlurVeilHost.setLayerType(View.LAYER_TYPE_NONE, null);
            if (backdropBlurVeil != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) backdropBlurVeil.setRenderEffect(null);
                backdropBlurVeil.setImageDrawable(null);
            }
            backdropBlurred = false;
        }
        if (!hasBackdropModal()) setBackdropBlur(false);
        stopped = true;
        tileRefresh.onStop();
    }

    /**
     * Another app needs the memory while Home is in the background: let go of every tile's artwork, and
     * rebuild it when Home comes back. Not at UI_HIDDEN, the ordinary trip to an app, where keeping the
     * tiles is what makes the return instant.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // RUNNING_LOW and CRITICAL also arrive while Home is visible when an accessibility service keeps the
        // process in the foreground; never blank the screen the owner is looking at.
        if (!MemoryTrimPolicy.shouldRelease(level, started)) return;
        adapter.clear();
        // Detach the adapter too: with Home stopped no layout pass runs, so the tile views (and the bitmaps
        // they hold) would stay attached until Home comes back. setAdapter(null) recycles them now.
        grid.setAdapter(null);
        grid.getRecycledViewPool().clear();
        heroArtGeneration++;
        heroArtCache.clear();
        heroArtInFlight.clear();
        heroArtKey = null;
        if (heroArt != null) heroArt.setImageDrawable(null);
        if (vinylDisc != null) vinylDisc.setAccentAndArt(0, null);
        mediaAccentCache.clear();
        AppLibraryDialog.clearIconCache();
        FrostedPillDrawable.clearSharedShaders();
        GlassView.clearGlowCache();
        if (dockDecoration != null) dockDecoration.clearCache();
        setBackdropLayerCached(false);
        wallpaperPlayer.releaseForMemoryPressure();
        tileRefresh.invalidate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            startPendingSettingsReorder();
            if (libraryFocusPending && !overlayOpen) focusLibrary();
            showCardPrompt();
        } else {
            cancelHoldOk();   // a dialog or another window took the remote while OK was held
        }
    }

    /** Glides the shared living liquid-glass ring onto top-bar pills and footer controls when focus leaves tiles. */
    private final ViewTreeObserver.OnGlobalFocusChangeListener glassFocusListener = (oldF, newF) -> {
        if (newF != null && newF.getId() == R.id.card) return;
        if (newF == null || newF == glass || newF == root || newF == grid
                || newF == homeBackdropLayer || newF instanceof RecyclerView) {
            hideGlass();
            return;
        }
        if (FocusRingController.isDescendantOf(newF, root)) {
            focusGlassOnControl(newF);
        } else {
            hideGlass();
        }
    };

    private void focusGlassOnControl(View target) {
        focusGlassOnControl(target, true);
    }

    private void focusGlassOnControl(View target, boolean animate) {
        if (target.getWidth() <= 0 || target.getHeight() <= 0) {
            target.post(() -> {
                if (target.isFocused() && target.getWidth() > 0) focusGlassOnControl(target);
            });
            return;
        }
        Rect r = tileRect;
        if (!FocusRingController.locateInHost(root, target, r)) {
            hideGlass();
            return;
        }
        View topButtons = findViewById(R.id.topButtons);
        if (topButtons != null && FocusRingController.isDescendantOf(target, topButtons)) {
            r.offset(Math.round(topButtons.getTranslationX()), Math.round(topButtons.getTranslationY()));
        }
        boolean inGrid = FocusRingController.isDescendantOf(target, grid);
        glassOnTopBar = !inGrid;
        glass.setLightSurface(ExpressiveGlass.isLightGlass() && !inGrid);
        boolean round = target == btnMedia || target == btnRecents || target == btnReorder
                || target == btnLibraryTop || iconOnlyButtons || topAppButtons.contains(target);
        float scale = (!animations || !advancedEffects || inGrid) ? 1f : (round ? 1.10f : 1.06f);
        float liftPx = (animations && advancedEffects && !reducedMotion && !inGrid) ? 2f * density : 0f;
        float pad = GlassView.insetPx(density);
        float targetW = r.width() * scale;
        float targetH = r.height() * scale;
        int gw = Math.round(targetW + 2f * pad);
        int gh = Math.round(targetH + 2f * pad);
        float radiusPx = Math.min(targetW, targetH) * 0.5f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != gw || lp.height != gh) {
            lp.width = gw;
            lp.height = gh;
            glass.setLayoutParams(lp);
        }
        glass.setRadius(radiusPx);
        glassCardW = -1;

        int accent = 0;
        if (reordering) accent = REORDER_ACCENT;
        else if (target == btnWifi) accent = wifiFocusAccent;
        else if (target == btnVpn) accent = vpnPillState == VpnBadge.PILL_UP ? VpnBadge.GREEN : VpnBadge.RED;
        else if (target == btnMedia) accent = mediaFocusAccent;
        else if (festive) accent = festival.accentColor();
        if (accent != glassAccent || glassSecondaryAccent != 0) {
            glassAccent = accent;
            glassSecondaryAccent = 0;
            glass.setDualAccent(accent, 0);
        }

        float x = r.exactCenterX() - gw / 2f;
        float y = r.exactCenterY() - gh / 2f - liftPx;
        glassTargetX = x;
        glassTargetY = y;
        glass.animate().cancel();
        if (!glassShown || !animate || !animations) {
            springX.cancel();
            springY.cancel();
            glass.resetMotion();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            if (!glassShown && animations && !reducedMotion) glass.animate().alpha(1f).setDuration(160).start();
            else glass.setAlpha(1f);
            glassShown = true;
        } else {
            glass.setAlpha(1f);
            glass.onJump(x - glass.getTranslationX(), y - glass.getTranslationY());
            springX.animateToFinalPosition(x - glassScrollShiftX);
            springY.animateToFinalPosition(y - glassScrollShift);
        }
    }

    @Override
    protected void onDestroy() {
        if (currentInstance == this) currentInstance = null;
        cancelHeroDotAnimations(false);
        handler.removeCallbacksAndMessages(null);
        if (root != null) root.removeCallbacks(showIdleClock);
        if (springX != null) springX.cancel();
        if (springY != null) springY.cancel();
        if (glass != null) glass.resetMotion();
        super.onDestroy();
        ViewTreeObserver tree = getWindow().getDecorView().getViewTreeObserver();
        if (tree.isAlive()) tree.removeOnGlobalFocusChangeListener(glassFocusListener);
        RemoteKeys.release(this);
        cancelHoldOk();
        if (gameDay != null) gameDay.shutdown();
        if (mediaTracker != null) mediaTracker.stop();
        unregisterReceiver(packageReceiver);
        if (addonDismissReceiverRegistered) {
            addonDismissReceiverRegistered = false;
            try {
                unregisterReceiver(addonDismissReceiver);
            } catch (RuntimeException ignored) {
            }
        }
        if (openOnStartOnWake) unregisterReceiver(wakeReceiver);
        wallpaperPlayer.close();
        ConnectivityManager cm = getApplicationContext().getSystemService(ConnectivityManager.class);   // as registered
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    /**
     * A Settings button sent as a keycode (settings_key_keycodes, e.g. 83 on TCL Google TV remotes) opens the same
     * thing HomeKeyService would. While that service runs it consumes the key first; without it (not enabled,
     * or switched off by a force-stop) the stock launcher that used to handle the key is gone, and the button did
     * nothing even on Home.
     */
    private boolean handleSettingsKey(KeyEvent event) {
        for (int code : settingsKeycodes) {
            if (event.getKeyCode() != code) continue;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                if (settingsKeyPanel) {
                    QuickSettingsActivity.launch(this);
                } else {
                    Intent settings = systemIntent(Settings.ACTION_SETTINGS);
                    // settings_key_clear_task, as in HomeKeyService: Back from Settings goes Home, not to a vendor page.
                    if (settingsKeyClearTask) settings.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    open(settings);
                }
            }
            return true;
        }
        return false;
    }

    // ---- reorder ---------------------------------------------------------------------------------

    /** Blue the glass turns while reorder mode is on. */
    private static final int REORDER_ACCENT = 0xFF6EC1FF;

    /**
     * Reorder mode (top-bar button). While it is on, every key comes here first:
     * - nothing carried: arrows move focus as usual; OK on a tile picks it up instead of opening the app;
     *   Back ends the mode and saves the order.
     * - a tile carried: arrows move it; Back (or OK) puts it down and the mode stays on for the next one.
     * Other keys (volume, Home) pass through; leaving the launcher ends the mode and saves.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        UiClickSound.onKeyEvent(this, event);
        // A real key, not onUserInteraction: that also runs when the firmware starts Live TV over Home at power-on.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            HomeKeyService.keyOnHome();
            AppAdapter.noteNavKey(event.getKeyCode());
        }
        if (homeIgnoreChannelKeys && RemoteKeys.isChannelKey(event.getKeyCode())) return true;
        // Vendor keys (TCL Netflix button, 4062) never reach the key services, only the focused window, so their
        // "hold:" entries run here too or nowhere. Only while resumed: onPause releases Home's holds, and a DOWN
        // taken after that would arm one nothing releases.
        if (resumed && event.getKeyCode() >= 1000 && RemoteKeys.handle(this, event, homeAppKeys.get(event.getKeyCode()),
                homeDoubleAppKeys.get(event.getKeyCode()),
                homeHoldAppKeys.get(event.getKeyCode()))) return true;
        if (handleSettingsKey(event)) return true;
        if (holdOk(event)) return true;
        if (tileMenuEnabled && !reordering && openPopup == null && openFolder == null
                && cardPrompt == null && folderPrompt == null
                && event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            int position = focusedTilePosition();
            if (position >= 0 && position < adapter.appCount()
                    && (!getPackageName().equals(adapter.packageAt(position)) || adapter.isFolderAt(position))) {
                if (RemoteKeys.menuReleased(event.getAction(), event.getFlags())) {
                    onTileLongPress(position, getCurrentFocus());
                }
                return true;
            }
        }
        if (!reordering) return super.dispatchKeyEvent(event);
        if (dockEditing) return dispatchDockEdit(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (movingKey != null) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) moveBy(code);
                    return true;
                default:
                    if (ok || back) {
                        if (!down) drop();
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
            }
        }
        if (back) {
            if (!down) endReorder();
            return true;
        }
        int pos = focusedTilePosition();
        View focus = getCurrentFocus();
        if (ok && focus != null && focus.getId() == R.id.btnLibrary) {
            // The App library pill sits in the grid but is no tile: OK ends the mode (saving the order) and opens it.
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                endReorder();
                onLibraryRequested();
            }
            return true;
        }
        if (ok && grid.hasFocus()) {
            // RecyclerView can briefly report NO_POSITION during a move or rebind. Still consume
            // OK on the grid: falling through would let the focused card open its app in reorder mode.
            if (!down && pos >= 0) pickUp(pos);
            return true;
        }
        return super.dispatchKeyEvent(event);   // arrows navigate; OK on a top-bar pill works as usual
    }

    /** Adapter position of the focused tile, or -1 when focus is not on a tile. */
    private int focusedTilePosition() {
        View f = getCurrentFocus();
        if (f == null || f.getId() != R.id.card || !(f.getParent() instanceof View)) return -1;
        return grid.getChildAdapterPosition((View) f.getParent());
    }

    /** The reorder button: turns the mode on (focus goes back to the last tile), or off again. */
    private void toggleReorder() {
        if (reordering) {
            endReorder();
            return;
        }
        if (!beginReorder()) return;
        focusTile(Math.max(0, Math.min(lastFocused, adapter.movableCount() - 1)));
        Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
    }

    /** The start shared by reorder and edit-dock mode. False when there is nothing to move. */
    private boolean beginReorder() {
        if (adapter.movableCount() < 2) return false;
        // Before the reload below: once it has dropped the empty slots, the dock row also shows grid apps.
        dockInSession = new HashSet<>();
        dockedAtStart = adapter.dockFilled();
        for (int i = 0; i < Math.min(dockedAtStart, adapter.movableCount()); i++) dockInSession.add(adapter.keyAt(i));
        adapter.setReordering(true);
        if (adapter.hasEmptySlots()) reloadTiles();   // every position must hold a tile that can move
        reordering = true;
        orderAtStart = adapter.movableKeys();
        glassAccent = REORDER_ACCENT;
        glassSecondaryAccent = 0;
        glass.setAccent(REORDER_ACCENT);
        btnReorder.setActivated(true);
        return true;
    }

    /** Edit dock (Settings menu): focus goes to the first dock place, which OK then chooses. */
    private void startDockEdit() {
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        dockEditSlot = -1;
        focusTile(0);
        Toast.makeText(this, R.string.dock_edit_hint, Toast.LENGTH_LONG).show();
    }

    /**
     * Keys in edit-dock mode. First OK on a dock tile chooses that place (the other dock tiles dim) and focus moves
     * to the grid; then OK on any tile swaps that app into the place, saves the order and ends the mode. Back goes
     * one step back: from choosing the app to choosing the place, and from there out of the mode.
     */
    private boolean dispatchDockEdit(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (back) {
            if (!down) {
                if (dockEditAppKey != null) {
                    endReorder();   // "Add to dock" cancelled before a place was chosen
                } else if (dockEditSlot >= 0) {
                    int slot = dockEditSlot;
                    dockEditSlot = -1;
                    dimOthers();
                    focusTile(slot);
                } else {
                    endReorder();
                }
            }
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && pos >= 0) {
            if (!down) {
                int n = adapter.movableCount();
                if (dockEditAppKey != null) {
                    if (pos < Math.min(COLUMNS, n)) {
                        int from = adapter.positionOf(dockEditAppKey);
                        if (from >= 0) swapIntoDock(pos, from);
                        else endReorder();
                    }
                } else if (dockEditSlot < 0) {
                    if (pos < Math.min(COLUMNS, n)) {
                        dockEditSlot = pos;
                        dimOthers();
                        focusTile(Math.min(COLUMNS, n - 1));
                        Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
                    }
                } else if (pos < n) {
                    swapIntoDock(dockEditSlot, pos);
                }
            }
            return true;   // never opens the app while editing
        }
        return super.dispatchKeyEvent(event);   // arrows navigate as usual
    }

    /**
     * Edit-dock mode started from the tile menu. A dock app: its place is already chosen, so focus goes to the grid
     * for the app to put there. A grid app: it is already chosen, so focus goes to the dock for the place.
     */
    private void startDockEditFor(String key, boolean inDock) {
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        int pos = adapter.positionOf(key);   // beginReorder can rebuild the tiles
        int n = adapter.movableCount();
        if (pos < 0) {
            endReorder();
        } else if (inDock) {
            dockEditSlot = pos;
            dimOthers();
            focusTile(Math.min(COLUMNS, n - 1));
            Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
        } else {
            dockEditAppKey = key;
            focusTile(0);
            Toast.makeText(this, R.string.dock_edit_pick_place, Toast.LENGTH_LONG).show();
        }
    }

    /** Puts the app at pos into dock place slot, and the app that was there where the chosen one was; then saves. */
    private void swapIntoDock(int slot, int pos) {
        String slotKey = adapter.keyAt(slot);
        if (pos != slot) {
            keepScroll(() -> {
                adapter.move(pos, slot);
                adapter.move(adapter.positionOf(slotKey), pos);
            });
            if (pos >= COLUMNS) dockInSession.remove(slotKey);
        }
        // Outside the check: with no empty slots shown, the chosen app may already sit in the free place.
        dockInSession.add(adapter.keyAt(slot));
        endReorder();
        refocusAfterLayout(slot);
    }

    private void pickUp(int pos) {
        if (pos >= adapter.movableCount()) return;   // the "Default home" tile stays put
        movingKey = adapter.keyAt(pos);
        dimOthers();
    }

    /** Puts the carried tile down where it is; the mode stays on. */
    private void drop() {
        movingKey = null;
        dimOthers();
    }

    /** Applies tileAlpha to every attached tile (carried tile bright; the rest dim while reordering). */
    private void dimOthers() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            item.setAlpha(tileAlpha(grid.getChildAdapterPosition(item)));
        }
    }

    /** A tile's alpha: the carried tile stays bright; every other tile is dim while reordering, including after a
     * drop, until Back ends the mode. In edit-dock mode, dock tiles other than the chosen place are dimmed. */
    private float tileAlpha(int pos) {
        if (movingKey != null) {
            return pos != RecyclerView.NO_POSITION && movingKey.equals(adapter.keyAt(pos)) ? 1f : 0.45f;
        }
        return GridNavigation.idleAlpha(pos, COLUMNS, dockEditing, dockEditSlot, reordering);
    }

    /** Ends the mode and saves the order if it changed since the mode started. */
    private void endReorder() {
        if (!reordering) return;
        dockEditing = false;
        dockEditSlot = -1;
        dockEditAppKey = null;
        drop();
        reordering = false;
        dimOthers();   // drop() ran while reordering was still on; restore full brightness now the mode is off
        adapter.setReordering(false);
        glassAccent = festive ? festival.accentColor() : 0;
        glassSecondaryAccent = 0;
        glass.setAccent(glassAccent);
        btnReorder.setActivated(false);
        List<String> keys = adapter.movableKeys();
        List<String> before = orderAtStart;
        orderAtStart = null;
        // fill_dock false: the dock is what the owner put there, saved with its size, so the empty slots that
        // come back after the mode go after it (#499). With fill_dock true every dock slot holds an app anyway.
        int docked = -1;
        if (fillDockLeavesSlots) {
            docked = DockSlots.dockCount(keys, COLUMNS, dockInSession);
            keys = DockSlots.dockFirst(keys, COLUMNS, dockInSession);
        }
        dockInSession = null;
        if (!keys.equals(before) || (fillDockLeavesSlots && docked != dockedAtStart)) {
            Order order = new Order(Order.dockKey(dockApps), keys, docked);
            if (Order.save(Order.file(this), order)) {
                adapter.setSavedOrder(Order.ranks(order, dockApps), Order.docked(order, dockApps));
            } else {
                Toast.makeText(this, R.string.reorder_not_saved, Toast.LENGTH_SHORT).show();
            }
        }
        if (fillDockLeavesSlots) reloadTiles();   // the empty dock slots left out while reordering come back
    }

    /**
     * Moves the carried tile one step. Left and Right wrap across rows, like a list; Up and Down move a whole row,
     * and Down into a short last row lands on its last tile. Nothing moves past either end or up into the top bar.
     */
    private void moveBy(int keyCode) {
        int from = adapter.positionOf(movingKey);
        int n = adapter.movableCount();
        if (from < 0 || from >= n) {
            drop();
            return;
        }
        // A single row has no rows to move between: Up and Down leave the carried tile where it is.
        if (singleRow && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) return;
        int to;
        // rtl_app_grid (Hebrew) mirrors the grid: position 0 is on the right, so Left moves towards the end.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = rtlAppGrid ? from + 1 : from - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = rtlAppGrid ? from - 1 : from + 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) to = from - COLUMNS;
        else if (from + COLUMNS < n) to = from + COLUMNS;
        else to = from / COLUMNS < (n - 1) / COLUMNS ? n - 1 : from;
        if (to < 0 || to >= n || to == from) return;
        keepScroll(() -> adapter.move(from, to));
        // The owner carries this tile: where it lands decides whether it is in the dock, not dock_apps (#499).
        if (to < COLUMNS) dockInSession.add(movingKey);
        else dockInSession.remove(movingKey);
        refocusAfterLayout(to);
    }

    /**
     * Applies an adapter change without the grid jumping. After a move the layout manager anchors on the focused
     * tile, keeping it at its old place on screen and shifting every other row; pinning the first visible row at its
     * current offset as the next layout's anchor keeps the scroll where it was. onTileFocused still scrolls
     * afterwards if the moved tile itself ends up off screen.
     */
    private void keepScroll(Runnable change) {
        GridLayoutManager lm = (GridLayoutManager) grid.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition();
        View firstView = first != RecyclerView.NO_POSITION ? lm.findViewByPosition(first) : null;
        // single_row scrolls sideways, so its first visible tile is pinned by its left edge instead; mirrored by
        // rtl_app_grid, the row is laid out from the right, and the offset counts from the right edge.
        int offset = firstView == null ? 0
                : singleRow && rtlAppGrid ? grid.getWidth() - grid.getPaddingRight() - lm.getDecoratedRight(firstView)
                : singleRow ? lm.getDecoratedLeft(firstView) - grid.getPaddingLeft()
                : lm.getDecoratedTop(firstView) - grid.getPaddingTop();
        change.run();
        if (firstView != null) lm.scrollToPositionWithOffset(first, offset);
    }

    /**
     * Once the grid has applied the change, put the glass (and focus, if the move dropped it) on the tile at pos.
     * The grid has a fixed size, so it applies adapter changes on the next animation frame rather than in a layout
     * pass; until then it cannot find the tile by position. Wait frame by frame (at most 10) for that. No focus
     * change fires for a moved tile, so focusTile's already-focused path calls onTileFocused itself.
     */
    private void refocusAfterLayout(int pos) {
        grid.postOnAnimation(new Runnable() {
            int frames;

            @Override
            public void run() {
                if ((grid.hasPendingAdapterUpdates() || grid.isLayoutRequested()) && ++frames < 10) {
                    grid.postOnAnimation(this);
                    return;
                }
                focusTile(pos);
                dimOthers();   // views that came into view or were rebound during the move
            }
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Home screen: Back goes nowhere, unless back_opens_clock is on.
        if (backOpensClock) openScreensaverAnimated();
    }

    // ---- glass highlight -------------------------------------------------------------------------

    private static volatile MainActivity currentInstance;
    private static volatile boolean controlCenterOpen;
    private static volatile boolean overlayOpen;
    private static volatile boolean settingsOpen;
    private static volatile boolean addonModalOpen;
    private static volatile long settingsLaunchPendingUntilMs;
    private static volatile long addonLaunchPendingUntilMs;
    private static volatile long folderPromptPendingUntilMs;

    static void markSettingsLaunchPending() {
        settingsLaunchPendingUntilMs = SystemClock.uptimeMillis() + 1200L;
    }

    static void clearSettingsLaunchPending() {
        settingsLaunchPendingUntilMs = 0L;
    }

    static boolean isSettingsLaunchInFlight() {
        return SystemClock.uptimeMillis() < settingsLaunchPendingUntilMs;
    }

    static void markFolderPromptPending() {
        folderPromptPendingUntilMs = SystemClock.uptimeMillis() + 450L;
    }
    private final Runnable petalShowerTask = this::playPetalShower;
    private final java.util.Map<String, Integer> mediaAccentCache = new java.util.HashMap<>();
    private static final ArgbEvaluator ARGB_EVALUATOR = new ArgbEvaluator();
    private HeroDotsView heroDotsView;

    static void setSettingsOpen(boolean open) {
        settingsOpen = open;
        MainActivity inst = currentInstance;
        if (inst != null && !inst.isDestroyed()) {
            inst.runOnUiThread(inst::updateBackdropBlur);
        }
    }

    static void prepareSettingsBackdropBlur() {
        settingsOpen = true;
        MainActivity inst = currentInstance;
        if (inst != null && !inst.isDestroyed()) {
            inst.runOnUiThread(inst::prepareBackdropBlurForPopup);
        }
    }

    static void fadeOutSettingsBlurNow() {
        settingsOpen = false;
        MainActivity inst = currentInstance;
        if (inst != null && !inst.isDestroyed()) {
            inst.runOnUiThread(() -> {
                if (!inst.hasBackdropModal()) inst.setBackdropBlur(false);
            });
        }
    }

    /** Modular CleanYourTV add-on packages styled as translucent frosted-glass modals over live blurred Home. */
    static boolean isGlassAddon(String pkg) {
        if (pkg == null) return false;
        switch (pkg) {
            case "io.github.maxhayim.brightness":
            case "io.github.luyzfernando08.sleeptimer":
            case "com.gathin.btconnect":
            case "io.github.maxhayim.headphones":
            case "io.github.maxhayim.quiethours":
            case "io.github.sumyabazar1.weather":
            case "io.github.savioronezero.worldclock":
            case "com.lionlev.tv.capacity":
                return true;
            default:
                return false;
        }
    }

    static void setAddonModalOpen(boolean open) {
        addonModalOpen = open;
        addonLaunchPendingUntilMs = open ? (SystemClock.uptimeMillis() + 1500L) : 0L;
        MainActivity inst = currentInstance;
        if (inst != null && !inst.isDestroyed()) {
            inst.runOnUiThread(inst::updateBackdropBlur);
        }
    }

    static void fadeOutAddonBlurNow() {
        addonModalOpen = false;
        addonLaunchPendingUntilMs = 0L;
        MainActivity inst = currentInstance;
        if (inst != null && !inst.isDestroyed()) {
            inst.runOnUiThread(() -> {
                if (!inst.hasBackdropModal()) inst.setBackdropBlur(false);
            });
        }
    }

    /** Captures the unblurred Home window (wallpaper + dock icons) for glass add-on refraction before launch. */
    static void captureLiveHomeBackdropThen(Runnable onReady) {
        MainActivity inst = currentInstance;
        if (inst == null || inst.isDestroyed() || inst.getWindow() == null || inst.backdropBlurred
                || (inst.backdropBlurVeilHost != null && inst.backdropBlurVeilHost.getVisibility() == View.VISIBLE
                && ExpressiveGlass.hasFreshHomeLiveBackdrop())) {
            onReady.run();
            return;
        }
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable finish = () -> {
            if (done.compareAndSet(false, true)) onReady.run();
        };
        inst.handler.postDelayed(finish, 360L);
        ExpressiveGlass.captureHomeWindowForRefraction(inst.getWindow(), () -> {
            inst.handler.removeCallbacks(finish);
            finish.run();
        });
    }

    /** Attaches the matching JPEG backdrop, glass material style, seed & over-home flag to glass add-on launch Intents. */
    static void attachAddonBackdropExtras(Intent intent, boolean overHome) {
        if (intent == null) return;
        intent.putExtra(ExpressiveGlass.EXTRA_OVER_HOME, overHome);
        intent.putExtra(ExpressiveGlass.EXTRA_OVER_SHARP_BACKDROP,
                overHome && !ExpressiveGlass.isHomeBackdropBlurEnabled());
        intent.putExtra(ExpressiveGlass.EXTRA_GLASS_STYLE, ExpressiveGlass.glassStyle());
        intent.putExtra(ExpressiveGlass.EXTRA_WALLPAPER_SEED, ExpressiveGlass.dynamicSeedRgb());
        byte[] jpeg = ExpressiveGlass.encodeActiveBackdropJpeg(overHome);
        if (jpeg != null && jpeg.length > 0) {
            intent.putExtra(ExpressiveGlass.EXTRA_BACKDROP_JPEG, jpeg);
        }
    }

    /** Live Glass Material style switch from LauncherSettingsActivity: re-themes Home, Dock, pills & open Settings immediately. */
    static void applyGlassMaterialPreference(String key) {
        MainActivity inst = currentInstance;
        if (inst == null || inst.isDestroyed()) {
            ExpressiveGlass.setGlassStyle(ExpressiveGlass.styleFromKey(key));
            return;
        }
        inst.runOnUiThread(() -> {
            if (inst.isDestroyed()) return;
            FeaturePrefs.setGlassMaterial(inst, key);
            int nextStyle = FeaturePrefs.resolveGlassStyle(inst);
            ExpressiveGlass.setGlassStyle(nextStyle);
            inst.onGlassStyleChangedLive();
        });
    }

    private void refreshAutoGlassStyleIfNeeded() {
        int resolved = FeaturePrefs.resolveGlassStyle(this);
        if (resolved != ExpressiveGlass.glassStyle()) {
            ExpressiveGlass.setGlassStyle(resolved);
            onGlassStyleChangedLive();
        }
    }

    private void onGlassStyleChangedLive() {
        cachedBackdropBlur = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && backdropBlurVeilHost != null && backdropBlurVeilHost.getVisibility() == View.VISIBLE
                && backdropBlurVeil != null && homeBackdropBlurEnabled) {
            cachedBackdropBlur = ExpressiveGlass.createBackdropBlurEffect(
                    density, ExpressiveGlass.SHADE_BLUR_RADIUS_DP, true, Shader.TileMode.CLAMP);
            backdropBlurVeil.setRenderEffect(cachedBackdropBlur);
        }
        if (dockDecoration != null) dockDecoration.invalidateBackdrop();
        if (grid != null) grid.invalidate();
        if (adapter != null) adapter.refreshFolderBanners();
        updateTopBarTintForGlassStyle();
        updateWifiLabel();
        if (vpnPill) {
            boolean vpnUp = vpnPillState == VpnBadge.PILL_UP;
            vpnPillState = Integer.MIN_VALUE;
            updateVpnPill(vpnUp);
        }
        if (glass != null) {
            boolean onLightHomeChrome = ExpressiveGlass.isLightGlass()
                    && (glassOnTopBar
                    || (dockRow && (singleRow || lastFocused < COLUMNS))
                    || (adapter != null && lastFocused >= 0 && adapter.isFolderAt(lastFocused)));
            glass.setLightSurface(onLightHomeChrome);
        }
        applyHomeTypography();
        LauncherSettingsActivity.refreshGlassStyleIfOpen();
        refreshLiveLiquidPanels();
        if (!homeBackdropBlurEnabled && backdropBlurred && root != null) {
            root.post(() -> {
                if (!isDestroyed() && backdropBlurred && !homeBackdropBlurEnabled) {
                    ExpressiveGlass.captureHomeWindowForRefraction(getWindow(), MainActivity::refreshLiveLiquidPanels);
                }
            });
        }
    }

    private void updateTopBarTintForGlassStyle() {
        LinearLayout topButtonsBar = findViewById(R.id.topButtons);
        if (topButtonsBar == null) return;
        ColorStateList pillInk = ExpressiveGlass.adaptivePillTextColors();
        for (int i = 0, n = topButtonsBar.getChildCount(); i < n; i++) {
            View child = topButtonsBar.getChildAt(i);
            if (child == btnMedia || child == btnVpn || topAppButtons.contains(child)) continue;
            if (child == btnWifi) {
                btnWifi.setTextColor(pillInk);
                if (!festive && wifiOnlineTint == null) {
                    btnWifi.setCompoundDrawableTintList(pillInk);
                }
                continue;
            }
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                tv.setTextColor(pillInk);
                if (!festive || (child != btnInputs && child.getId() != R.id.btnSettings)) {
                    tv.setCompoundDrawableTintList(pillInk);
                }
            }
        }
        invalidateTopButtons();
    }

    static void refreshLiveLiquidPanels() {
        MainActivity inst = currentInstance;
        if (inst != null && !inst.isDestroyed()) {
            if (inst.openPopup != null && inst.openPopup.getContentView() != null) {
                ExpressiveGlass.invalidateLiquidPanels(inst.openPopup.getContentView());
            }
            if (inst.folderPrompt != null && inst.folderPrompt.getWindow() != null) {
                ExpressiveGlass.invalidateLiquidPanels(inst.folderPrompt.getWindow().getDecorView());
            }
            if (inst.cardPrompt != null && inst.cardPrompt.getWindow() != null) {
                ExpressiveGlass.invalidateLiquidPanels(inst.cardPrompt.getWindow().getDecorView());
            }
        }
        OverlayActivity.refreshBackdropIfOpen();
        QuickSettingsActivity.refreshBackdropIfOpen();
        LauncherSettingsActivity.refreshBackdropIfOpen();
    }

    /** Live toggle from LauncherSettingsActivity: fades full-screen Home blur in/out immediately without closing Settings. */
    static void applyHomeBackdropBlurPreference(boolean enabled) {
        MainActivity inst = currentInstance;
        if (inst == null || inst.isDestroyed()) {
            ExpressiveGlass.setHomeBackdropBlurEnabled(enabled);
            return;
        }
        inst.runOnUiThread(() -> {
            if (inst.isDestroyed()) {
                ExpressiveGlass.setHomeBackdropBlurEnabled(enabled);
                return;
            }
            inst.homeBackdropBlurEnabled = enabled;
            if (inst.wallpaperPlayer != null) {
                inst.wallpaperPlayer.refreshSharpnessIfNeeded(!enabled);
            }
            if (!inst.backdropBlurred) {
                ExpressiveGlass.setHomeBackdropBlurEnabled(enabled);
                refreshLiveLiquidPanels();
                return;
            }
            if (enabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && inst.root != null) {
                    if (inst.cachedBackdropBlur == null) {
                        inst.cachedBackdropBlur = ExpressiveGlass.createBackdropBlurEffect(
                                inst.density, ExpressiveGlass.SHADE_BLUR_RADIUS_DP, true, Shader.TileMode.CLAMP);
                    }
                    Runnable finishEnable = () -> {
                        if (inst.homeBackdropBlurEnabled) {
                            ExpressiveGlass.setHomeBackdropBlurEnabled(true);
                            refreshLiveLiquidPanels();
                        }
                    };
                    if (ExpressiveGlass.hasFreshHomeLiveBackdrop()) {
                        inst.showBackdropBlurSmooth(false);
                        if (inst.reducedMotion || !inst.animations) finishEnable.run();
                        else inst.handler.postDelayed(finishEnable, 115L);
                    } else {
                        ExpressiveGlass.captureHomeWindowForRefraction(inst.getWindow(), () -> {
                            if (!inst.isDestroyed() && inst.backdropBlurred && inst.homeBackdropBlurEnabled) {
                                inst.showBackdropBlurSmooth(false);
                                if (inst.reducedMotion || !inst.animations) finishEnable.run();
                                else inst.handler.postDelayed(finishEnable, 115L);
                            } else {
                                finishEnable.run();
                            }
                        });
                    }
                } else {
                    ExpressiveGlass.setHomeBackdropBlurEnabled(true);
                    refreshLiveLiquidPanels();
                }
            } else {
                // Switch floating panels to per-panel frosted interior blur FIRST so dissolving the outer veil never flashes clear.
                ExpressiveGlass.setHomeBackdropBlurEnabled(false);
                refreshLiveLiquidPanels();
                inst.fadeOutBackdropBlurVeil(false, () -> {
                    if (!inst.isDestroyed() && inst.backdropBlurred && !inst.homeBackdropBlurEnabled) {
                        // Recapture live Home at crisp 960x540 (prefilterSharp=false) so open Settings upgrades immediately.
                        ExpressiveGlass.captureHomeWindowForRefraction(inst.getWindow(), MainActivity::refreshLiveLiquidPanels);
                    }
                });
            }
        });
    }

    static boolean isHomeVisible() {
        MainActivity inst = currentInstance;
        return inst != null && inst.started && !inst.isDestroyed();
    }

    static boolean hasLiveAdapter() {
        MainActivity inst = currentInstance;
        return inst != null && !inst.isDestroyed() && inst.adapter != null;
    }

    static boolean isPackageOnLiveHome(String pkg) {
        MainActivity inst = currentInstance;
        return inst != null && !inst.isDestroyed() && inst.adapter != null && inst.adapter.containsPackage(pkg);
    }

    static void notifyLibraryChanged() {
        MainActivity inst = currentInstance;
        if (inst == null || inst.isDestroyed()) return;
        if (inst.started) {
            if (Looper.myLooper() == Looper.getMainLooper()) inst.reloadApps(false);
            else inst.runOnUiThread(() -> inst.reloadApps(false));
        } else {
            inst.tileRefresh.invalidate();
        }
    }

    static void setControlCenterOpen(boolean open) {
        controlCenterOpen = open;
        MainActivity inst = currentInstance;
        if (inst != null && !inst.isDestroyed()) {
            inst.runOnUiThread(inst::updateBackdropBlur);
        }
    }

    static void prepareControlCenterBackdropBlur() {
        controlCenterOpen = true;
        MainActivity inst = currentInstance;
        if (inst != null && !inst.isDestroyed()) {
            inst.runOnUiThread(inst::prepareBackdropBlurForPopup);
        }
    }

    static void fadeOutControlCenterBlurNow() {
        controlCenterOpen = false;
        MainActivity inst = currentInstance;
        if (inst != null && !inst.isDestroyed()) {
            inst.runOnUiThread(() -> {
                if (!inst.hasBackdropModal()) inst.setBackdropBlur(false);
            });
        }
    }

    /** Immediately halts all idle Home animations & sway right before starting an external app so 100% GPU goes to launch. */
    static void prepareForAppLaunch() {
        AppAdapter.stopAllFocusMotion();
        MainActivity inst = currentInstance;
        if (inst == null || inst.isDestroyed()) return;
        Runnable freeze = () -> {
            if (inst.isDestroyed()) return;
            if (inst.dockDecoration != null) inst.dockDecoration.cancelRipple();
            inst.stopAmbientAnimation();
            if (inst.springX != null) inst.springX.cancel();
            if (inst.springY != null) inst.springY.cancel();
            if (inst.hero != null) inst.hero.animate().cancel();
            if (inst.heroArt != null) inst.heroArt.animate().cancel();
            if (inst.ambient != null) inst.ambient.animate().cancel();
            if (inst.vinylDisc != null) inst.vinylDisc.setLifecycleActive(false);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) freeze.run();
        else inst.runOnUiThread(freeze);
    }

    static void setOverlayOpen(boolean open) {
        overlayOpen = open;
        MainActivity inst = currentInstance;
        if (inst != null && !inst.isDestroyed()) {
            inst.runOnUiThread(inst::updateBackdropBlur);
        }
    }

    static void prepareOverlayBackdropBlur() {
        overlayOpen = true;
        MainActivity inst = currentInstance;
        if (inst != null && !inst.isDestroyed()) {
            inst.runOnUiThread(inst::prepareBackdropBlurForPopup);
        }
    }

    static void fadeOutOverlayBlurNow() {
        overlayOpen = false;
        MainActivity inst = currentInstance;
        if (inst != null && !inst.isDestroyed()) {
            inst.runOnUiThread(() -> {
                if (!inst.hasBackdropModal()) inst.setBackdropBlur(false);
            });
        }
    }

    static void startBackdropBlurFadeInIfOpen() {
        MainActivity inst = currentInstance;
        if (inst != null && !inst.isDestroyed()) {
            inst.runOnUiThread(inst::startBackdropBlurFadeIn);
        }
    }

    /** Brief handoff grace period so chained modals (Folder <-> Add/Rename, Tile Menu -> New Folder) never flash unblurred. */
    private static final long BLUR_CLEAR_HANDOFF_MS = 90L;
    private final Runnable clearBlurDeferred = () -> {
        if (!hasBackdropModal()) setBackdropBlur(false);
    };
    private final Runnable blurFadeFallback = this::startBackdropBlurFadeIn;
    private final Runnable cacheBackdropLayerRunnable = () -> setBackdropLayerCached(true);

    /** Reparents the full-screen background layers (sky, heroArt, ambient, heroScrim) into one cacheable container. */
    private FrameLayout wrapHomeBackdropLayer(FrameLayout parent) {
        FrameLayout layer = new FrameLayout(this);
        layer.setClickable(false);
        layer.setFocusable(false);
        layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int[] ids = {R.id.sky, R.id.heroArt, R.id.ambient, R.id.heroScrim};
        for (int id : ids) {
            View child = parent.findViewById(id);
            if (child != null && child.getParent() == parent) {
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                parent.removeView(child);
                layer.addView(child, lp);
            }
        }
        parent.addView(layer, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return layer;
    }

    private void setBackdropLayerCached(boolean cached) {
        handler.removeCallbacks(cacheBackdropLayerRunnable);
        if (homeBackdropLayer == null) return;
        int want = (cached && started && !isDestroyed()) ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        if (homeBackdropLayer.getLayerType() != want) {
            homeBackdropLayer.setLayerType(want, null);
        }
    }

    /** Temporarily releases the backdrop layer cache during heroArt/ambient fades and re-caches once settled. */
    private void markBackdropLayerAnimating(long settleDelayMs) {
        if (homeBackdropLayer == null) return;
        handler.removeCallbacks(cacheBackdropLayerRunnable);
        if (homeBackdropLayer.getLayerType() != View.LAYER_TYPE_NONE) {
            homeBackdropLayer.setLayerType(View.LAYER_TYPE_NONE, null);
        }
        if (started && !isDestroyed()) {
            handler.postDelayed(cacheBackdropLayerRunnable, Math.max(80L, settleDelayMs));
        }
    }

    private boolean hasBackdropModal() {
        return controlCenterOpen || overlayOpen || settingsOpen || addonModalOpen || openFolder != null
                || cardPrompt != null || folderPrompt != null
                || openPopup != null || mediaMiniPlayer != null
                || SystemClock.uptimeMillis() < folderPromptPendingUntilMs;
    }

    /** Extends the backdrop-blur hold across chained popups/modals so the blurred veil never blinks out between windows. */
    private void holdBackdropBlurForHandoff() {
        folderPromptPendingUntilMs = SystemClock.uptimeMillis() + 500L;
        handler.removeCallbacks(clearBlurDeferred);
    }

    /**
     * 2-layer hardware-cached backdrop blur host:
     *  - Outer FrameLayout (backdropBlurVeilHost) holds LAYER_TYPE_HARDWARE and animates alpha (0 -> 1 -> 0).
     *  - Inner ImageView (backdropBlurVeil) holds the native 1080p Skia RenderEffect at constant alpha=1.
     * Separating RenderEffect onto the static child guarantees HWUI rasterizes the 38px Gaussian blur + AGSL
     * IGN grain ONCE into the host FBO instead of re-blurring at 30ms/frame on every alpha vsync.
     */
    private FrameLayout ensureBackdropBlurVeil() {
        if (backdropBlurVeilHost != null && backdropBlurVeil != null) return backdropBlurVeilHost;
        View content = findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return null;
        FrameLayout host = new FrameLayout(this);
        host.setClickable(false);
        host.setFocusable(false);
        host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        host.setVisibility(View.GONE);
        host.setAlpha(0f);
        ImageView veil = new ImageView(this);
        veil.setScaleType(ImageView.ScaleType.FIT_XY);
        veil.setClickable(false);
        veil.setFocusable(false);
        veil.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        host.addView(veil, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((FrameLayout) content).addView(host, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        backdropBlurVeilHost = host;
        backdropBlurVeil = veil;
        return host;
    }

    /**
     * Smoothly cross-dissolves native 1080p Skia RenderEffect blur (cached ONCE inside backdropBlurVeilHost's
     * hardware FBO) over Home at the user's native display refresh rate with zero downscaled patterns or banding.
     */
    private void showBackdropBlurSmooth(boolean deferFadeStart) {
        if (!homeBackdropBlurEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || root == null) return;
        ExpressiveGlass.RefractionBackdrop live = ExpressiveGlass.homeLiveRefraction();
        if (live == null || live.sharp.isRecycled()) {
            live = ExpressiveGlass.homeWallpaperRefraction();
        }
        FrameLayout host = (live != null && !live.sharp.isRecycled()) ? ensureBackdropBlurVeil() : null;
        if (host != null && backdropBlurVeil != null && cachedBackdropBlur != null) {
            root.setRenderEffect(null);
            host.animate().cancel();
            backdropBlurVeil.setImageBitmap(live.sharp);
            backdropBlurVeil.setRenderEffect(cachedBackdropBlur);
            backdropBlurVeil.setLayerType(View.LAYER_TYPE_NONE, null);
            host.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            if (host.getVisibility() != View.VISIBLE) {
                // Non-zero invisible alpha on Frame 0 lets HWUI rasterize the 1080p RenderEffect FBO ONCE
                // before Frame 1's synchronized reveal begins (zero mid-open stutter).
                host.setAlpha(deferFadeStart && !reducedMotion && animations ? 0.002f : 0f);
                host.setVisibility(View.VISIBLE);
            } else if (host.getAlpha() > 0.05f) {
                // Already visible (e.g. handoff or mid-fade re-open): bring cleanly back to 1f with zero blink.
                startBackdropBlurFadeIn();
                return;
            }
            if (deferFadeStart && !reducedMotion && animations) {
                handler.removeCallbacks(blurFadeFallback);
                handler.postDelayed(blurFadeFallback, 180L);
                return;
            }
            startBackdropBlurFadeIn();
        } else {
            root.setRenderEffect(cachedBackdropBlur);
        }
    }

    /** Pre-warms the blurred hardware layer on Frame 0 before a popup or modal's postOnAnimation glide begins. */
    void prepareBackdropBlurForPopup() {
        setBackdropBlurInternal(true, true);
    }

    /** Starts the cached-FBO blur fade-in on the exact vsync frame that a popup or modal begins its reveal. */
    void startBackdropBlurFadeIn() {
        handler.removeCallbacks(blurFadeFallback);
        if (!backdropBlurred || !homeBackdropBlurEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        if (backdropBlurVeilHost != null && backdropBlurVeilHost.getVisibility() == View.VISIBLE) {
            backdropBlurVeilHost.animate().cancel();
            if (reducedMotion || !animations) {
                backdropBlurVeilHost.setAlpha(1f);
            } else if (backdropBlurVeilHost.getAlpha() < 0.999f) {
                backdropBlurVeilHost.animate()
                        .alpha(1f)
                        .setDuration(105L)
                        .setInterpolator(Motion.easeOutExpo())
                        .start();
            }
        }
    }

    void setBackdropBlur(boolean blurred) {
        setBackdropBlurInternal(blurred, false);
    }

    private void setBackdropBlurInternal(boolean blurred, boolean deferFadeStart) {
        if (blurred) {
            handler.removeCallbacks(clearBlurDeferred);
        } else {
            handler.removeCallbacks(blurFadeFallback);
        }
        if (root == null) return;
        if (blurred == backdropBlurred) {
            if (blurred) {
                if (backdropBlurVeilHost != null && backdropBlurVeilHost.getVisibility() == View.VISIBLE) {
                    backdropBlurVeilHost.animate().cancel();
                    if (!deferFadeStart || backdropBlurVeilHost.getAlpha() > 0.05f) {
                        startBackdropBlurFadeIn();
                    }
                } else if (!deferFadeStart) {
                    startBackdropBlurFadeIn();
                }
            }
            return;
        }
        backdropBlurred = blurred;
        // Pause spinning vinyl & freeze in-flight Home animations while root is blurred; when unblurring,
        // wait until backdropBlurVeilHost's dismiss fade ends before resuming vinyl so it never fights close.
        if (blurred) {
            if (vinylDisc != null) vinylDisc.setLifecycleActive(false);
            if (springX != null) springX.cancel();
            if (springY != null) springY.cancel();
            if (glass != null) {
                glass.resetMotion();
                glass.animate().cancel();
            }
            stopAmbientAnimation();
            cancelHeroDotAnimations(true);
            if (hero != null) hero.animate().cancel();
            if (heroArt != null) heroArt.animate().cancel();
            if (ambient != null) ambient.animate().cancel();
            if (lamp != null) lamp.stop();
            // Bake wallpaper + heroArt + ambient into 1 opaque GPU blit before the blur veil fades over Home!
            setBackdropLayerCached(true);
        } else {
            if (resumed && festive && lamp != null) lamp.start();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (blurred) {
                if (homeBackdropBlurEnabled) {
                    if (cachedBackdropBlur == null) {
                        // Android 17 Material 3 Expressive blur (AOSP 34dp * 1/sqrt(3) + AGSL vibrancy, dynamic
                        // wallpaper tint & native-1080p IGN anti-banding). Rendered ONCE into Skia's cached FBO.
                        cachedBackdropBlur = ExpressiveGlass.createBackdropBlurEffect(
                                density, ExpressiveGlass.SHADE_BLUR_RADIUS_DP, true, Shader.TileMode.CLAMP);
                    }
                    if (ExpressiveGlass.hasFreshHomeLiveBackdrop()) {
                        showBackdropBlurSmooth(deferFadeStart);
                    } else {
                        // Capture the unblurred Home screen FIRST (~8ms GPU PixelCopy) so Liquid Glass panels
                        // always refract exact live icons/dock/wallpaper instead of racing with root blur.
                        final java.util.concurrent.atomic.AtomicBoolean applied = new java.util.concurrent.atomic.AtomicBoolean(false);
                        Runnable applyNow = () -> {
                            if (!applied.compareAndSet(false, true) || !backdropBlurred || isDestroyed() || root == null) return;
                            showBackdropBlurSmooth(false);
                            refreshLiveLiquidPanels();
                        };
                        handler.postDelayed(applyNow, 65L);
                        ExpressiveGlass.captureHomeWindowForRefraction(getWindow(), () -> {
                            handler.removeCallbacks(applyNow);
                            applyNow.run();
                        });
                    }
                } else {
                    // User disabled full-screen Home backdrop blur: keep Home razor-sharp (0% dim) while still
                    // capturing the live unblurred Home window so Liquid Glass 2.0 rims refract crisp icons & art.
                    root.setRenderEffect(null);
                    if (backdropBlurVeilHost != null && backdropBlurVeilHost.getVisibility() == View.VISIBLE) {
                        fadeOutBackdropBlurVeil(false);
                    }
                    if (!ExpressiveGlass.hasFreshHomeLiveBackdrop()) {
                        final java.util.concurrent.atomic.AtomicBoolean applied = new java.util.concurrent.atomic.AtomicBoolean(false);
                        Runnable applyNow = () -> {
                            if (!applied.compareAndSet(false, true) || !backdropBlurred || isDestroyed() || root == null) return;
                            refreshLiveLiquidPanels();
                        };
                        handler.postDelayed(applyNow, 65L);
                        ExpressiveGlass.captureHomeWindowForRefraction(getWindow(), () -> {
                            handler.removeCallbacks(applyNow);
                            applyNow.run();
                        });
                    } else {
                        refreshLiveLiquidPanels();
                    }
                }
            } else {
                fadeOutBackdropBlurVeil(true);
            }
        } else if (!blurred && vinylDisc != null) {
            vinylDisc.setLifecycleActive(resumed);
        }
    }

    private void fadeOutBackdropBlurVeil(boolean clearLiveAndResumeVinyl) {
        fadeOutBackdropBlurVeil(clearLiveAndResumeVinyl, null);
    }

    private void fadeOutBackdropBlurVeil(boolean clearLiveAndResumeVinyl, Runnable onHidden) {
        if (root != null) root.setRenderEffect(null);
        if (backdropBlurVeilHost != null && backdropBlurVeilHost.getVisibility() == View.VISIBLE) {
            backdropBlurVeilHost.animate().cancel();
            Runnable finish = () -> {
                if ((!backdropBlurred || !homeBackdropBlurEnabled) && backdropBlurVeilHost != null) {
                    backdropBlurVeilHost.setAlpha(0f);
                    backdropBlurVeilHost.setVisibility(View.GONE);
                    backdropBlurVeilHost.setLayerType(View.LAYER_TYPE_NONE, null);
                    if (backdropBlurVeil != null) {
                        backdropBlurVeil.setRenderEffect(null);
                        backdropBlurVeil.setImageDrawable(null);
                    }
                }
                if (clearLiveAndResumeVinyl && !backdropBlurred) {
                    ExpressiveGlass.clearHomeLiveBackdrop();
                    if (vinylDisc != null) vinylDisc.setLifecycleActive(resumed);
                }
                if (onHidden != null) onHidden.run();
            };
            if (reducedMotion || !animations) {
                finish.run();
            } else {
                backdropBlurVeilHost.animate()
                        .alpha(0f)
                        .setDuration(175L)
                        .setInterpolator(Motion.easeInExit())
                        .withEndAction(finish)
                        .start();
            }
        } else {
            if (clearLiveAndResumeVinyl) {
                ExpressiveGlass.clearHomeLiveBackdrop();
                if (vinylDisc != null) vinylDisc.setLifecycleActive(resumed);
            }
            if (onHidden != null) onHidden.run();
        }
    }

    void updateBackdropBlur() {
        if (hasBackdropModal()) {
            handler.removeCallbacks(clearBlurDeferred);
            setBackdropBlur(true);
        } else if (backdropBlurred) {
            handler.removeCallbacks(clearBlurDeferred);
            handler.postDelayed(clearBlurDeferred, BLUR_CLEAR_HANDOFF_MS);
        }
    }

    private void showRecents() {
        if (openFolder != null) openFolder.close();
        if (mediaMiniPlayer != null) mediaMiniPlayer.close();
        if (openPopup != null) openPopup.dismiss();
        OverlayActivity.launch(this, OverlayActivity.MODE_RECENTS);
    }

    private void onMediaSessionsUpdated(List<android.media.session.MediaController> controllers) {
        if (btnMedia == null || vinylDisc == null) return;
        boolean hasAny = controllers != null && !controllers.isEmpty();
        if (!hasAny) {
            boolean hadFocus = btnMedia.hasFocus();
            vinylDisc.setSpinning(false);
            vinylDisc.setAccentAndArt(0, null);
            mediaFocusAccent = 0;
            if (mediaMiniPlayer != null) mediaMiniPlayer.close();
            btnMedia.setFocusable(false);
            if (btnMedia.getVisibility() == View.VISIBLE) {
                btnMedia.animate().cancel();
                if (animations && !reducedMotion && !backdropBlurred) {
                    btnMedia.animate().setStartDelay(0L).scaleX(0.45f).scaleY(0.45f).alpha(0f)
                            .setDuration(155L).setInterpolator(Motion.easeInExit())
                            .withEndAction(() -> {
                                btnMedia.setVisibility(View.GONE);
                                btnMedia.setScaleX(1f);
                                btnMedia.setScaleY(1f);
                                btnMedia.setAlpha(1f);
                            }).start();
                } else {
                    btnMedia.setVisibility(View.GONE);
                    btnMedia.setScaleX(1f);
                    btnMedia.setScaleY(1f);
                    btnMedia.setAlpha(1f);
                }
            }
            if (hadFocus) (btnWifi.getVisibility() == View.VISIBLE && btnWifi.isFocusable() ? btnWifi : firstTopButton()).requestFocus();
            return;
        }
        android.media.session.MediaController primary = controllers.get(0);
        android.media.session.PlaybackState ps = primary.getPlaybackState();
        boolean playing = ps != null && MediaSessionTracker.isPlayingState(ps.getState());
        android.media.MediaMetadata md = primary.getMetadata();
        Bitmap art = null;
        if (md != null) {
            art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        int accent = 0;
        if (art == null) {
            String pkg = primary.getPackageName();
            Integer cachedAccent = mediaAccentCache.get(pkg);
            if (cachedAccent != null) {
                accent = cachedAccent;
            } else {
                try {
                    Drawable icon = getPackageManager().getApplicationIcon(pkg);
                    accent = AppAdapter.vividColor(icon);
                    mediaAccentCache.put(pkg, accent);
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                }
            }
        }
        vinylDisc.setAccentAndArt(accent, art);
        mediaFocusAccent = accent;
        vinylDisc.setSpinning(playing);
        btnMedia.setFocusable(true);
        if (btnMedia.getVisibility() != View.VISIBLE || btnMedia.getAlpha() < 0.99f) {
            btnMedia.animate().cancel();
            boolean poppingIn = btnMedia.getVisibility() != View.VISIBLE;
            btnMedia.setVisibility(View.VISIBLE);
            invalidateTopButtons();
            if (poppingIn && animations && !reducedMotion && !backdropBlurred) {
                btnMedia.setScaleX(0.35f);
                btnMedia.setScaleY(0.35f);
                btnMedia.setAlpha(0f);
                btnMedia.animate().setStartDelay(0L).scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(290L).setInterpolator(Motion.springPop()).start();
            } else {
                btnMedia.setScaleX(1f);
                btnMedia.setScaleY(1f);
                btnMedia.setAlpha(1f);
            }
        }
        if (mediaMiniPlayer != null) {
            mediaMiniPlayer.updateSessions(controllers);
        }
    }

    private void showMediaMiniPlayer() {
        if (mediaTracker == null || mediaMiniPlayerPending) return;
        List<android.media.session.MediaController> controllers = mediaTracker.controllers();
        if (controllers.isEmpty()) return;
        if (openFolder != null) openFolder.close();
        if (openPopup != null) openPopup.dismiss();
        if (mediaMiniPlayer != null) mediaMiniPlayer.close();
        mediaMiniPlayerPending = true;
        // Capture the live unblurred Home backdrop FIRST so the smooth blur fade-in and mini-player
        // card reveal start on the exact same vsync frame (zero mid-animation blur pop).
        captureLiveHomeBackdropThen(() -> {
            mediaMiniPlayerPending = false;
            if (isFinishing() || isDestroyed() || mediaTracker == null) return;
            List<android.media.session.MediaController> liveControllers = mediaTracker.controllers();
            if (liveControllers.isEmpty()) return;
            final MediaMiniPlayerDialog[] ref = new MediaMiniPlayerDialog[1];
            ref[0] = new MediaMiniPlayerDialog(this, mediaTracker, mediaAppLabels, reducedMotion, () -> {
                if (mediaMiniPlayer == ref[0]) mediaMiniPlayer = null;
                if (openPopup == ref[0].popup) openPopup = null;
                updateBackdropBlur();
                if (btnMedia != null && btnMedia.getVisibility() == View.VISIBLE) btnMedia.requestFocus();
                else restoreFocus();
            });
            mediaMiniPlayer = ref[0];
            openPopup = ref[0].popup;
            ref[0].show(liveControllers);
        });
    }

    @Override
    public void onLibraryRequested() {
        if (!appLibraryEnabled) return;
        if (openFolder != null) openFolder.close();
        if (mediaMiniPlayer != null) mediaMiniPlayer.close();
        if (openPopup != null) openPopup.dismiss();
        libraryFocusPending = false;
        OverlayActivity.launch(this, OverlayActivity.MODE_LIBRARY);
    }

    @Override
    public void onTileFocused(int position, View card) {
        glassOnTopBar = false;
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        glass.setLightSurface(ExpressiveGlass.isLightGlass()
                && ((dockRow && (singleRow || position < COLUMNS)) || adapter.isFolderAt(position)));
        // Focus restored before the first layout (cold start): the tile has no size yet, and the glass
        // would be sized to 2*pad. Run again once the tile is laid out.
        if (card.getWidth() == 0) {
            card.post(() -> {
                if (card.isFocused() && card.getWidth() > 0) onTileFocused(position, card);
            });
            return;
        }
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = GlassView.insetPx(density);        // matches GlassView's glow + liquid wobble inset
        float scale = adapter.focusScale();
        int glassW = Math.round(card.getWidth() * scale + 2 * pad);
        int glassH = Math.round(card.getHeight() * scale + 2 * pad);
        float x = r.exactCenterX() - glassW / 2f;
        // advanced_effects lifts a focused grid tile by 4dp and the glass follows it; dock tiles stay put (#2135).
        boolean lifted = advancedEffects && !DockSlots.inDockRow(dockRow, singleRow, position, COLUMNS);
        float y = r.exactCenterY() - glassH / 2f - (lifted ? 4f * density : 0f);

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != glassW || lp.height != glassH || card.getWidth() != glassCardW) {
            lp.width = glassW;
            lp.height = glassH;
            glass.setLayoutParams(lp);
            // GlassView draws its slab inset by pad, i.e. exactly over the zoomed tile: same corners as the tile.
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
        }
        glassCardW = card.getWidth();
        glassCardRect.set(r);
        glassTargetX = x;
        glassTargetY = y;
        // Cinematic shelf: the highlight glows in the dock app's colour, plain white in the grid (and on the dock with
        // cinematic_white_focus). app_glow: every tile. In a single row every tile is the dock's kind.
        boolean coloured = (appGlow || cinematic && !cinematicWhiteFocus && (singleRow || position < COLUMNS)) && !festive;
        int accent = coloured ? adapter.ambientColorAt(position) : 0;
        int secondaryAccent = coloured ? adapter.secondaryAccentAt(position) : 0;
        if (!reordering && !festive && (accent != glassAccent || secondaryAccent != glassSecondaryAccent)) {
            glassAccent = accent;
            glassSecondaryAccent = secondaryAccent;
            glass.setDualAccent(accent, secondaryAccent);
        }
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.resetMotion();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            if (animations && !reducedMotion) glass.animate().alpha(1f).setDuration(180).start();
            else glass.setAlpha(1f);
            glassShown = true;
        } else if (!animations) {
            springX.cancel();
            springY.cancel();
            glass.resetMotion();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
        } else {
            glass.onJump(x - glass.getTranslationX(), y - glass.getTranslationY());
            springX.animateToFinalPosition(x - glassScrollShiftX);
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        View item = (View) card.getParent();
        if (dockRow && (singleRow || position < COLUMNS)) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app. In a single row
            // every tile is in the dock's row, which never scrolls vertically.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            // A 1px mismatch used to start a scroll on every dock move. Only scroll when the row is
            // actually off; reduced motion does it in one jump so it does not fight the highlight.
            boolean scroll = !singleRow && Math.abs(dy) > (int) (8 * density);
            if (scroll) {
                if (!animations || reducedMotion) grid.scrollBy(0, dy);
                else grid.smoothScrollBy(0, dy, Motion.easeOutExpo(), 250);
            }
            showHero(position, scroll ? r.top - dy : r.top);
        } else {
            // Below the dock (or in a plain grid) the glow follows the tile without the big name; letting it
            // fade out left the grid on plain black.
            showGlowOnly(position);
            // Scroll just enough so the whole glass (not only the tile) is visible.
            float glowPad = 6f * density;
            revealRect.set(0, -(int) glowPad * 2, item.getWidth(), item.getHeight() + (int) glowPad);
            grid.requestChildRectangleOnScreen(item, revealRect, reducedMotion);
        }
    }

    /** Refits the glass to the focused holder once the grid layout and tile dimensions settle. */
    private void refitGlass() {
        if (isDestroyed() || adapter.appCount() == 0 || libraryFocusPending) return;
        int pos = Math.max(0, Math.min(lastFocused, adapter.appCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        View card = vh == null ? null : vh.itemView.findViewById(R.id.card);
        if (card == null || card.getWidth() <= 0 || !card.isFocused()) return;
        float pad = GlassView.insetPx(density);
        float scale = adapter.focusScale();
        int w = Math.round(card.getWidth() * scale + 2 * pad);
        int h = Math.round(card.getHeight() * scale + 2 * pad);
        // The tile's rect, not only its width: the grid's top padding lands after the tile has its width, so a
        // cold start can leave the glass at the right size and the wrong place. Comparing the rect catches that.
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        if (!glassShown || card.getWidth() != glassCardW || glass.getWidth() != w || glass.getHeight() != h
                || !glassCardRect.equals(r)) {
            onTileFocused(pos, card);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - hero.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());
        if (cinematic && heroArtAlpha > 0f) showHeroArt(position);

        if (!animations || reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            hero.animate().cancel();
            ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.setAlpha(1f);
            if (ambientGlow) {
                setAmbient(color);
                ambient.setAlpha(1f);
            }
            heroShown = true;
            return;
        }

        if (!heroShown) {
            stopAmbientAnimation();
            hero.animate().cancel();
            ambient.animate().cancel();
            fillHero(position, label);
            lastHeroPosition = position;
            hero.setY(y + 12 * density);
            hero.setTranslationX(0f);
            hero.animate().alpha(1f).y(y).setDuration(290L).setInterpolator(Motion.easeOutExpo()).start();
            if (ambientGlow) {
                setAmbient(color);
                ambient.animate().alpha(1f).setDuration(600L).setInterpolator(Motion.easeOutExpo()).start();
            }
            heroShown = true;
        } else if (changed) {
            int dir = (lastHeroPosition >= 0 && position < lastHeroPosition) ? -1 : 1;
            if (rtlAppGrid) dir = -dir;
            lastHeroPosition = position;
            // Instant-swap Apple tvOS glide: swap text immediately without a 110ms fade-out stall so rapid
            // D-pad movement across folders and apps stays crisp and synced with the focus ring.
            hero.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setAlpha(0f);
            hero.setTranslationX(16f * density * dir);
            hero.animate().alpha(1f).translationX(0f).setDuration(210L)
                    .setInterpolator(Motion.easeOutExpo()).start();
            if (ambientGlow && advancedEffects) {
                transitionAmbient(color);
            } else if (ambientGlow && color != ambientColor && glowSnap) {
                ambient.animate().cancel();
                setAmbient(color);
                ambient.setAlpha(1f);
            } else if (ambientGlow && color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            lastHeroPosition = position;
            hero.setY(y);
            // onPause cancels the fade-ins, and a second Home during one lands on the same tile, which starts none:
            // finish them, or the shelf and the glow stay dim until focus moves to another app.
            if (hero.getAlpha() < 1f) hero.animate().alpha(1f).setDuration(260L).setInterpolator(Motion.easeOutExpo()).start();
            if (ambientGlow && color == ambientColor && ambient.getAlpha() < 1f) {
                ambient.animate().alpha(1f).setDuration(600L).setInterpolator(Motion.easeOutExpo()).start();
            }
        }
    }

    /** The shelf's text for this dock app: its name and, on the cinematic shelf, "Last opened" and the dots. */
    private void fillHero(int position, String label) {
        heroName.setText(label);
        if (!cinematic) return;
        updateHeroMeta(position);
        updateHeroDots(position);
    }

    // ---- cinematic shelf -------------------------------------------------------------------------

    /**
     * The parts that never change: a larger name, the meta line and the dots, the artwork and the scrim, at
     * scrimPercent of its strength (cinematic_scrim). cinematic_art 0 leaves the artwork out, 0 percent the scrim.
     */
    private void setUpCinematicShelf(int scrimPercent) {
        heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 68f);
        heroName.setLetterSpacing(-0.025f);
        heroMeta.setVisibility(View.INVISIBLE);   // keeps its height, so the name does not jump when it fills in
        heroDots.setVisibility(heroDotsShown ? View.VISIBLE : View.GONE);
        heroArt.setVisibility(heroArtAlpha > 0f ? View.VISIBLE : View.GONE);
        // One stretched bitmap: apply its alpha while drawing instead of through an offscreen buffer.
        heroArt.forceHasOverlappingRendering(false);
        if (scrimPercent <= 0) return;   // cinematic_scrim 0: no scrim at all
        // When a wallpaper is active, WallpaperPlayer already baked both the top readability vignette and
        // cinematic scrim directly into the GPU wallpaper bitmap (eliminating a full-screen 1080p overdraw pass).
        if (wallpaperPlayer != null && wallpaperPlayer.hasActiveWallpaper()) return;
        View scrim = findViewById(R.id.heroScrim);
        Drawable shade = scrimDrawable();
        // On the bitmap's paint, not the view: a view's alpha would draw the full screen through an offscreen buffer.
        shade.setAlpha(scrimPercent * 255 / 100);
        scrim.setBackground(shade);
        scrim.setVisibility(View.VISIBLE);
    }

    /** Applies luminous top-lit pearl gradients (and iOS 26 Liquid Glass clock optics) to floating Home text. */
    private void applyHomeTypography() {
        View clk = findViewById(R.id.clock);
        if (clk instanceof LiquidClockView) {
            ((LiquidClockView) clk).setLiquidEnabled(FeaturePrefs.isLiquidClockEnabled(this), false);
        }
        ExpressiveGlass.applyLuminousTitle(findViewById(R.id.clock), density, true);
        ExpressiveGlass.applyLuminousTitle(heroName, density, true);
        ExpressiveGlass.applyLuminousTitle(raceHeadline, density, false);
        ExpressiveGlass.applyReadableShadow(greeting, density, true);
        ExpressiveGlass.applyReadableShadow(findViewById(R.id.ampm), density, true);
        ExpressiveGlass.applyReadableShadow(findViewById(R.id.date), density, true);
        ExpressiveGlass.applyReadableShadow(shamsiDate, density, true);
        ExpressiveGlass.applyReadableShadow(lunarDate, density, true);
        ExpressiveGlass.applyReadableShadow(stats, density, true);
        ExpressiveGlass.applyReadableShadow(heroMeta, density, true);
        ExpressiveGlass.applyReadableShadow(raceKicker, density, true);
        ExpressiveGlass.applyReadableShadow(raceSessions, density, true);
    }

    /**
     * Darkens the side the shelf's text is on and the lower part of the screen, so the name, the dock and
     * the grid stay readable over the artwork. One small bitmap stretched over the screen, like the glow.
     */
    private Drawable scrimDrawable() {
        int w = 480, h = 270;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        Paint paint = new Paint(Paint.DITHER_FLAG);
        paint.setShader(new LinearGradient(rtl ? w : 0, 0, rtl ? 0 : w, 0,
                new int[]{0xD1060406, 0x59060406, 0x00060406}, new float[]{0f, 0.42f, 0.7f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x00060406, 0x00060406, 0xDB060406, 0xFF060406}, new float[]{0f, 0.28f, 0.58f, 0.76f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        bmp.prepareToDraw();
        BitmapDrawable scrim = new BitmapDrawable(getResources(), bmp);
        scrim.setDither(true);
        scrim.setGravity(Gravity.FILL);
        return scrim;
    }

    private static java.util.concurrent.ExecutorService heroArtExecutor() {
        java.util.concurrent.ExecutorService e = heroArtWorker;
        if (e == null) {
            synchronized (MainActivity.class) {
                e = heroArtWorker;
                if (e == null) {
                    e = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "hero-art");
                        t.setDaemon(true);
                        return t;
                    });
                    heroArtWorker = e;
                }
            }
        }
        return e;
    }

    /** Swaps the backdrop to this app's artwork: fade out, swap, fade in, the way the shelf's name changes. */
    private void showHeroArt(int position) {
        String key = adapter.keyAt(position);
        if (key == null) return;
        heroArt.animate().cancel();
        if (key.equals(heroArtKey) && heroArtCache.containsKey(key)) {
            fadeHeroArt(420);
            return;
        }
        heroArtKey = key;
        Bitmap art = heroArtCache.get(key);
        if (art != null) {
            applyHeroArtBitmap(art);
            return;
        }
        if (heroArtInFlight.contains(key)) return;
        Bitmap big = HeroArt.rasterize(adapter.artworkAt(position));
        if (big == null) {
            applyHeroArtBitmap(null);
            return;
        }
        heroArtInFlight.add(key);
        final int gen = heroArtGeneration;
        heroArtExecutor().execute(() -> {
            Bitmap processed = HeroArt.processRasterized(big);
            handler.post(() -> {
                heroArtInFlight.remove(key);
                if (gen != heroArtGeneration || isDestroyed()) {
                    if (processed != null && !processed.isRecycled()) processed.recycle();
                    return;
                }
                if (processed != null) heroArtCache.put(key, processed);
                if (key.equals(heroArtKey) && heroShown) {
                    applyHeroArtBitmap(processed);
                }
            });
        });
    }

    private void applyHeroArtBitmap(Bitmap next) {
        if (!animations || reducedMotion || heroArt.getAlpha() == 0f) {
            heroArt.setImageBitmap(next);
            fadeHeroArt(240);
            return;
        }
        markBackdropLayerAnimating(360L);
        heroArt.animate().alpha(0f).setDuration(95L)
                .setInterpolator(Motion.easeInExit())
                .withEndAction(() -> {
                    heroArt.setImageBitmap(next);
                    heroArt.animate().alpha(heroArtAlpha)
                            .setDuration(220L).setInterpolator(Motion.easeOutExpo())
                            .withEndAction(this::invalidateGlassBackdrops).start();
                }).start();
    }

    private void fadeHeroArt(int ms) {
        if (!animations) {
            heroArt.setAlpha(heroArtAlpha);
            invalidateGlassBackdrops();
        } else {
            long dur = reducedMotion ? Math.min(ms, Motion.REDUCED_MS) : ms;
            markBackdropLayerAnimating(dur + 60L);
            heroArt.animate().alpha(heroArtAlpha)
                    .setDuration(dur)
                    .withEndAction(this::invalidateGlassBackdrops).start();
        }
    }

    private void invalidateGlassBackdrops() {
        invalidateTopButtons();
        // Use grid.invalidate() (paint only), NEVER invalidateItemDecorations() which calls requestLayout()
        // and re-measures every tile mid-scroll! Also skip when grid is already scrolling (draws every frame).
        if (dockBackdrop && grid != null && grid.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
            if (dockDecoration == null || !dockDecoration.invalidateBackdrop()) {
                grid.invalidate();
            }
        }
    }

    /** "Last opened yesterday, 9:40 PM", the time in white; for folders, the app count. */
    private void updateHeroMeta(int position) {
        AppAdapter.Entry folder = adapter.folderAt(position);
        if (folder != null) {
            int n = folder.folderMembers.size();
            heroMeta.setText(getResources().getQuantityString(R.plurals.library_app_count, n, n));
            heroMeta.setVisibility(View.VISIBLE);
            return;
        }
        long when = LastOpened.get(this, adapter.packageAt(position));
        if (when <= 0) {
            heroMeta.setVisibility(View.INVISIBLE);
            return;
        }
        String rel = LastOpened.describe(this, when);
        String text = getString(R.string.last_opened, rel);
        SpannableString line = new SpannableString(text);
        int at = text.indexOf(rel);
        if (at >= 0) line.setSpan(new ForegroundColorSpan(0xFFFFFFFF), at, at + rel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        heroMeta.setText(line);
        heroMeta.setVisibility(View.VISIBLE);
    }

    /** One dot per dock app, the focused one drawn long and white. Hebrew docks start on the right, so do the dots. */
    private void updateHeroDots(int position) {
        if (!heroDotsShown) return;
        int count = Math.min(adapter.dockFilled(), COLUMNS);
        heroDots.setVisibility(count > 1 ? View.VISIBLE : View.INVISIBLE);
        if (heroDotsView == null) {
            heroDots.removeAllViews();
            heroDotsView = new HeroDotsView(this, density, rtlAppGrid);
            heroDots.addView(heroDotsView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(6f * density)));
        }
        heroDotsView.update(count, position, animations && !reducedMotion && heroShown);
    }

    /** Zero-layout dock page indicator: interpolates capsule dot widths purely in onDraw() (zero requestLayout()). */
    private static final class HeroDotsView extends View {
        private final float density;
        private final boolean rtl;
        private final float dotH;
        private final float gap;
        private final float smallW;
        private final float largeW;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int count;
        private int active = -1;
        private float[] widths = new float[0];
        private float[] fromWidths = new float[0];
        private float[] toWidths = new float[0];
        private ValueAnimator anim;

        HeroDotsView(Context ctx, float density, boolean rtl) {
            super(ctx);
            this.density = density;
            this.rtl = rtl;
            this.dotH = 6f * density;
            this.gap = 6f * density;
            this.smallW = 6f * density;
            this.largeW = 22f * density;
        }

        void update(int newCount, int pos, boolean animate) {
            boolean countChanged = newCount != count;
            if (countChanged) {
                cancelAnim(false);
                count = Math.max(0, newCount);
                widths = new float[count];
                fromWidths = new float[count];
                toWidths = new float[count];
                for (int i = 0; i < count; i++) {
                    widths[i] = toWidths[i] = (i == pos ? largeW : smallW);
                }
                active = pos;
                requestLayout();
                invalidate();
                return;
            }
            if (pos == active) return;
            active = pos;
            cancelAnim(false);
            for (int i = 0; i < count; i++) {
                fromWidths[i] = widths[i];
                toWidths[i] = (i == pos ? largeW : smallW);
            }
            if (!animate) {
                System.arraycopy(toWidths, 0, widths, 0, count);
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(220L);
            anim.setInterpolator(Motion.easeOutExpo());
            anim.addUpdateListener(va -> {
                float t = (float) va.getAnimatedValue();
                for (int i = 0; i < count; i++) {
                    widths[i] = fromWidths[i] + (toWidths[i] - fromWidths[i]) * t;
                }
                invalidate();
            });
            anim.start();
        }

        void cancelAnim(boolean finishToEnd) {
            if (anim != null) {
                if (finishToEnd) anim.end();
                else anim.cancel();
                anim = null;
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int totalW = count <= 0 ? 0
                    : Math.round(largeW + Math.max(0, count - 1) * (smallW + gap));
            setMeasuredDimension(totalW, Math.round(dotH));
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (count <= 0) return;
            float r = dotH * 0.5f;
            float x = 0f;
            for (int v = 0; v < count; v++) {
                int idx = rtl ? (count - 1 - v) : v;
                float w = widths[idx];
                paint.setColor(idx == active ? 0xFFFFFFFF : 0x4DFFFFFF);
                rect.set(x, 0f, x + w, dotH);
                canvas.drawRoundRect(rect, r, r, paint);
                x += w + gap;
            }
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture. It is painted once in white and tinted with the app's colour,
     * so changing apps neither repaints it on the CPU (about 11 ms) nor uploads it again.
     */
    private void setAmbient(int color) {
        setAmbient(color, true);
    }

    private void setAmbient(int color, boolean syncGlass) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        BitmapDrawable glow = (BitmapDrawable) ambient.getBackground();
        if (glow == null) {
            int w = 480;
            int h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            if (cinematic) {
                // Higher and stronger, from the corner away from the shelf's text: the app's colour floods
                // the top of the screen over its artwork, and the scrim keeps the text side dark.
                boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
                paint.setShader(new RadialGradient(
                        w * (rtl ? 0.20f : 0.80f), h * 0.06f, w * 0.78f,
                        new int[]{0xA6FFFFFF, 0x47FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.5f, 1f},
                        Shader.TileMode.CLAMP));
            } else if (Config.GLOW_HORIZON.equals(glowStyle)) {
                // The colour washes down from the top edge and fades out three quarters of the way down,
                // with a brighter band along the top like light over a horizon.
                paint.setShader(new LinearGradient(0, 0, 0, h * 0.75f,
                        new int[]{0x66FFFFFF, 0x24FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.4f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, paint);
                paint.setShader(new RadialGradient(
                        w * 0.5f, -h * 0.25f, w * 0.55f,
                        new int[]{0x40FFFFFF, 0x00FFFFFF},
                        null,
                        Shader.TileMode.CLAMP));
            } else {
                paint.setShader(new RadialGradient(
                        w * 0.32f, h * 0.42f, w * 0.62f,
                        new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.45f, 1f},
                        Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, w, h, paint);
            bmp.prepareToDraw();
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setDither(true);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        // The default tint mode, SRC_IN, keeps the mask's alpha and takes the colour from the tint.
        glow.setTint(0xFF000000 | color);
        if (syncGlass) invalidateGlassBackdrops();
    }

    private int pillAmbientColor() {
        if (!ambientGlow || ambient == null || ambient.getVisibility() != View.VISIBLE
                || ambient.getAlpha() <= 0f || ambientColor == -1) return 0;
        int alpha = Math.round(ambient.getAlpha() * 0x24);
        return alpha > 0 ? (alpha << 24) | (ambientColor & 0xFFFFFF) : 0;
    }

    private void invalidateTopButtons() {
        ViewGroup bar = findViewById(R.id.topButtons);
        if (bar == null) return;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) child.invalidate();
        }
    }

    private void transitionAmbient(int color) {
        // A rapid focus change continues from the colour actually displayed on screen.
        stopAmbientAnimation();
        ambient.animate().cancel();
        markBackdropLayerAnimating(320L);
        if (color == ambientColor) {
            ambient.animate().alpha(1f).setDuration(180).start();
            return;
        }
        ambient.animate().alpha(1f).setDuration(150L).setInterpolator(Motion.easeOutExpo()).start();
        ambientAnimator = ValueAnimator.ofObject(ARGB_EVALUATOR, ambientColor, color);
        ambientAnimator.setDuration(260L);
        ambientAnimator.setInterpolator(Motion.easeOutExpo());
        ambientAnimator.addUpdateListener(animation -> setAmbient((int) animation.getAnimatedValue(), false));
        ambientAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean canceled;
            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                canceled = true;
            }
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!canceled && !backdropBlurred) invalidateGlassBackdrops();
            }
        });
        ambientAnimator.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    private void cancelHeroDotAnimations(boolean finishToEnd) {
        if (heroDotsView != null) heroDotsView.cancelAnim(finishToEnd);
    }

    /**
     * Grid tiles (every tile with dock_row false): the glow behind the grid takes the focused tile's colour; the big
     * name above the dock does not show (every grid tile has its name underneath).
     */
    private void showGlowOnly(int position) {
        int color = adapter.ambientColorAt(position);
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        if (animations && !reducedMotion && hero.getAlpha() > 0.02f) {
            hero.animate().alpha(0f).y(hero.getY() - 10f * density).setDuration(110L)
                    .setInterpolator(Motion.easeInExit()).start();
        } else {
            hero.setAlpha(0f);
        }
        boolean hadHeroArt = heroArt.getAlpha() > 0f;
        heroArt.animate().cancel();
        heroArt.setAlpha(0f);
        heroShown = false;
        lastHeroPosition = -1;
        if (hadHeroArt) invalidateGlassBackdrops();
        if (!ambientGlow) return;
        if (!animations || reducedMotion) {
            setAmbient(color);
            ambient.setAlpha(1f);
            return;
        }
        if (advancedEffects && ambientColor != -1) {
            transitionAmbient(color);
            return;
        }
        if (color == ambientColor && ambient.getBackground() != null) {
            ambient.animate().alpha(1f).setDuration(300).start();
            return;
        }
        if (glowSnap) {
            setAmbient(color);
            ambient.setAlpha(1f);
            return;
        }
        // The same dip-and-swap as the dock's top shelf.
        ambient.animate().alpha(0.35f).setDuration(ambient.getAlpha() > 0.35f ? 200 : 0).withEndAction(() -> {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(500).start();
        }).start();
    }

    private void hideHero() {
        if (!heroShown) return;
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        heroShown = false;
        lastHeroPosition = -1;
        if (animations && !reducedMotion) {
            markBackdropLayerAnimating(360L);
            hero.animate().alpha(0f).setDuration(160).start();
            if (ambientGlow) ambient.animate().alpha(0f).setDuration(300).start();
            heroArt.animate().alpha(0f).setDuration(300L)
                    .setInterpolator(Motion.easeOutExpo())
                    .withEndAction(this::invalidateGlassBackdrops).start();
        } else {
            hero.setAlpha(0f);
            ambient.setAlpha(0f);
            heroArt.setAlpha(0f);
            invalidateGlassBackdrops();
        }
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.resetMotion();
        glass.animate().cancel();
        if (animations && !reducedMotion) glass.animate().alpha(0f).setDuration(160).start();
        else glass.setAlpha(0f);
    }

    // ---- optional system stats (config.xml show_stats) --------------------------------------------

    /** Background reader. Started only while Home is resumed and show_stats is on. */
    private HandlerThread statsThread;
    /** The current run's handler; a run whose handler is no longer this one stops (volatile: read off-thread). */
    private volatile Handler statsBg;

    private void startStats() {
        if (statsThread != null) return;
        stats.setVisibility(View.VISIBLE);
        statsThread = new HandlerThread("stats");
        statsThread.start();
        // Each start gets its own handler and private CPU sampler state (zero data races across pause/resume).
        Handler bg = new Handler(statsThread.getLooper());
        statsBg = bg;
        bg.post(new Runnable() {
            private final CpuSampler sampler = new CpuSampler();

            @Override
            public void run() {
                if (statsBg != bg) return;
                String line = readStatsLine(sampler);
                handler.post(() -> {
                    // Skip text mutations while root is blurred so Mali/Skia keeps the cached 1080p RenderEffect static.
                    if (showStats && stats != null && statsBg == bg && !backdropBlurred) stats.setText(line);
                });
                if (statsBg == bg) bg.postDelayed(this, STATS_MS);
            }
        });
    }

    private void stopStats() {
        if (statsThread == null) return;
        statsBg.removeCallbacksAndMessages(null);
        statsThread.quit();
        statsThread = null;
        statsBg = null;
        // Paused (the quick panel or an app on top): keep the last line on screen. Taking it away moved the grid up
        // under a glass highlight that stayed put.
        if (!showStats) stats.setVisibility(View.GONE);
    }

    /** One quiet line, e.g. "CPU 8%  ·  Memory 430 MB free  ·  Storage 3.1 GB free". Runs off the main thread. */
    private static void appendStat(StringBuilder line, String part) {
        if (line.length() > 0) line.append("  ·  ");
        line.append(part);
    }

    private String readStatsLine(CpuSampler sampler) {
        int cpu = sampler.sample();
        if (cpu < 0) {
            try {
                Thread.sleep(200);             // second sample, so the first line already has a CPU figure
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
            cpu = sampler.sample();
        }
        // Android 8 and later hide /proc/stat from apps on many TVs: then the line leaves CPU out rather than
        // showing a dash that never fills in.
        StringBuilder line = new StringBuilder();
        if (cpu >= 0) line.append(getString(R.string.stats_cpu, cpu));
        long[] mem = memUsedOfTotalMb();
        if (mem != null) appendStat(line, getString(R.string.stats_memory, mem[0], mem[1]));
        String free = formatGb(getResources(), Environment.getDataDirectory().getFreeSpace());
        if (!free.isEmpty()) appendStat(line, getString(R.string.stats_storage, free));
        return StatsText.forLocale(line.toString(), getResources().getConfiguration().getLocales().get(0));
    }

    // -1: need another sample. -2: this source cannot be read (Android 9 hides /proc/stat from apps).
    private static final int CPU_UNREADABLE = -2;

    private static final class CpuSampler {
        private long cpuTotalPrev = -1, cpuIdlePrev = -1;
        private boolean procStatDead;
        private long idlePrev = -1, wallPrev = -1;

        int sample() {
            if (!procStatDead) {
                int fromStat = cpuFromProcStat();
                if (fromStat != CPU_UNREADABLE) return fromStat;
                procStatDead = true;
                idlePrev = -1;
            }
            return cpuFromIdle();
        }

        private int cpuFromProcStat() {
            String s = readFirstLine("/proc/stat");
            if (s == null || !s.startsWith("cpu")) return CPU_UNREADABLE;
            String[] t = s.trim().split("\\s+");
            if (t.length < 5) return CPU_UNREADABLE;
            try {
                long user = Long.parseLong(t[1]), nice = Long.parseLong(t[2]), sys = Long.parseLong(t[3]);
                long idle = Long.parseLong(t[4]);
                long iowait = t.length > 5 ? Long.parseLong(t[5]) : 0;
                long irq = t.length > 6 ? Long.parseLong(t[6]) : 0;
                long soft = t.length > 7 ? Long.parseLong(t[7]) : 0;
                long total = user + nice + sys + idle + iowait + irq + soft;
                long idleAll = idle + iowait;
                if (cpuTotalPrev < 0) {
                    cpuTotalPrev = total;
                    cpuIdlePrev = idleAll;
                    return -1;
                }
                long dt = total - cpuTotalPrev, di = idleAll - cpuIdlePrev;
                cpuTotalPrev = total;
                cpuIdlePrev = idleAll;
                if (dt <= 0) return -1;
                int busy = (int) Math.round((dt - di) * 100.0 / dt);
                return Math.max(0, Math.min(100, busy));
            } catch (NumberFormatException e) {
                return CPU_UNREADABLE;
            }
        }

        /** Busy percent from cpuidle time (microseconds) across the cores. -1 until the second sample. */
        private int cpuFromIdle() {
            long idle = 0;
            int cores = 0;
            for (int i = 0; i < 8; i++) {
                boolean any = false;
                for (int state = 0; state < 8; state++) {
                    String t = readFirstLine("/sys/devices/system/cpu/cpu" + i
                            + "/cpuidle/state" + state + "/time");
                    if (t == null) break;
                    try {
                        idle += Long.parseLong(t.trim());
                        any = true;
                    } catch (NumberFormatException e) {
                        break;
                    }
                }
                if (!any) break;
                cores++;
            }
            if (cores == 0) return -1;
            long wall = SystemClock.elapsedRealtime() * 1000L;
            if (idlePrev < 0) {
                idlePrev = idle;
                wallPrev = wall;
                return -1;
            }
            long di = idle - idlePrev;
            long dw = (wall - wallPrev) * cores;
            idlePrev = idle;
            wallPrev = wall;
            if (dw <= 0) return -1;
            int busy = (int) Math.round((dw - di) * 100.0 / dw);
            return Math.max(0, Math.min(100, busy));
        }
    }

    /** RAM usage as {used, total} in MB (MemInfo.usedOfTotalMb); null when /proc/meminfo cannot be read. */
    private long[] memUsedOfTotalMb() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            for (String line; (line = r.readLine()) != null;) lines.add(line);
        } catch (IOException ignored) {
            return null;
        }
        return MemInfo.usedOfTotalMb(lines);
    }

    private static String readFirstLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return r.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String formatGb(Resources res, long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 10 ? res.getString(R.string.storage_gb_whole, gb)
                : res.getString(R.string.storage_gb_decimal, gb);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.appCount();
        int target;
        if (singleRow) {
            // One row: Up leaves for the top bar, Down has nowhere to go, and Left/Right walk the whole row,
            // stopping at its two ends (the default focus search scrolls the row to the next tile).
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            boolean forward = rtlAppGrid ? keyCode == KeyEvent.KEYCODE_DPAD_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            int last = adapter.focusablePosition(count - 1);
            if (forward && position >= last) {
                if (adapter.isLibraryAt(count)) focusLibrary();
                return true;
            }
            return !forward && position == 0;
        }
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean forward = rtlAppGrid ? keyCode == KeyEvent.KEYCODE_DPAD_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (forward && position == adapter.focusablePosition(count - 1) && adapter.isLibraryAt(count)) {
                focusLibrary();
                return true;
            }
            // In the dock, the last app is the row's end: the empty slots after it take no focus.
            int rowCount = position < COLUMNS ? Math.min(count, adapter.dockFilled()) : count;
            return GridNavigation.atHorizontalEdge(position, rowCount, COLUMNS,
                    rtlAppGrid, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) {
                if (adapter.isLibraryAt(count)) focusLibrary();
                return true;
            }
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        // The default focus search clicks for Left/Right; Up/Down are handled here, so click for them too.
        grid.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_UP
                ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN);
        return true;
    }

    @Override public void onLibraryFocused() {
        libraryFocusPending = false;
        hideHero();
    }

    /**
     * date_below_clock: the date column moves to its own line under the clock, in the vertical LinearLayout that holds
     * the clock's row, and the line between them goes. In Hebrew (rtl_menu_bar) the date and the clock's row sit right.
     */
    private void moveDateBelowClock() {
        View column = (View) findViewById(R.id.date).getParent();
        View clockRow = (View) findViewById(R.id.clock).getParent();
        LinearLayout clockColumn = (LinearLayout) clockRow.getParent();
        ((ViewGroup) column.getParent()).removeView(column);
        findViewById(R.id.clockDivider).setVisibility(View.GONE);
        // Its own width, not the column's: in Hebrew the column is only as wide as the clock.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(4 * density);
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            // Date and clock flush right; the greeting stays left and follows the clock by translation.
            lp.gravity = Gravity.RIGHT;
            ((LinearLayout.LayoutParams) clockRow.getLayoutParams()).gravity = Gravity.RIGHT;
        }
        clockColumn.addView(column, clockColumn.indexOfChild(clockRow) + 1, lp);
        // The column now wraps its texts, so they must wrap too: a match_parent TextView does not ask for a new
        // layout when its text gets longer, and the column kept the width of the day before ("TUESDAY 27 OCTOB…").
        ViewGroup texts = (ViewGroup) column;
        for (int i = 0; i < texts.getChildCount(); i++) {
            View text = texts.getChildAt(i);
            LinearLayout.LayoutParams textLp = (LinearLayout.LayoutParams) text.getLayoutParams();
            textLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            // Keep each line where its own gravity put it (right in Hebrew) now that it no longer fills the column.
            textLp.gravity = ((TextView) text).getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK;
            text.setLayoutParams(textLp);
        }
    }

    /** Centre the one-row header when the greeting and extra date lines are absent (#2265). */
    private void centerCompactHeader() {
        LinearLayout clockRow = (LinearLayout) findViewById(R.id.clock).getParent();
        clockRow.setGravity(Gravity.CENTER_VERTICAL);
        clockRow.setBaselineAligned(false);
        View[] columns = {findViewById(R.id.clockDivider), (View) findViewById(R.id.date).getParent()};
        for (View column : columns) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) column.getLayoutParams();
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.bottomMargin = 0;
            column.setLayoutParams(lp);
        }
        LinearLayout buttons = findViewById(R.id.topButtons);
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) buttons.getLayoutParams();
        lp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
        lp.bottomMargin = 0;
        buttons.setLayoutParams(lp);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
    }

    /** With the date below, keep the icons beside the clock rather than beside the last date line. */
    private void alignDateBelowIconsWithClock() {
        View clock = findViewById(R.id.clock);
        View clockRow = (View) clock.getParent();
        LinearLayout buttons = findViewById(R.id.topButtons);
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) buttons.getLayoutParams();
        lp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
        lp.removeRule(RelativeLayout.ALIGN_BOTTOM);
        lp.topMargin = 0;
        lp.bottomMargin = 0;
        buttons.setLayoutParams(lp);
        // Either side can be laid out first; update after both without requesting another layout mid-pass.
        Runnable align = () -> buttons.setTranslationY(clockRow.getTop() + clock.getTop()
                + (clock.getHeight() - buttons.getHeight()) / 2f);
        clockRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> align.run());
        buttons.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> align.run());
    }

    /** The first top-bar button after Inputs that is shown, in bar order (top_bar_apps, the network pill, ...); Settings is always there. */
    private View firstTopButton() {
        ViewGroup bar = findViewById(R.id.topButtons);
        // The Hebrew layout (layout-iw) lists the bar mirrored, Settings first and Inputs last, so bar order
        // runs from the end there. Walking it forwards returned Settings, and the pill key guard then treated
        // Settings as both ends of the bar: Right from Settings was swallowed and never reached Wi-Fi.
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        int n = bar.getChildCount();
        for (int k = 0; k < n; k++) {
            View button = bar.getChildAt(rtl ? n - 1 - k : k);
            if (button != btnInputs && button.getVisibility() == View.VISIBLE && button.isFocusable()) return button;
        }
        return findViewById(R.id.btnSettings);
    }

    /** top_bar_apps buttons, in bar order. */
    private final List<View> topAppButtons = new ArrayList<>();

    /**
     * Puts each top_bar_apps app in the top bar as a round pill with the app's own logo, left of the network pill.
     * The logo keeps its colours: the pill style's tint, which turns the other icons white, is cleared.
     */
    private void addTopBarApps(List<String> packages, Map<String, String> launchActions) {
        ViewGroup bar = findViewById(R.id.topButtons);
        PackageManager pm = getPackageManager();
        int size = Math.round(22 * density);
        for (String pkg : packages) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;   // not installed, or disabled
            // Use the same configured launch_actions as tiles; retain the normal entry if unavailable.
            launch = LaunchActions.select(pkg, launchActions, launch, (targetPackage, action) -> {
                Intent custom = LaunchActions.intent(targetPackage, action);
                return pm.resolveActivity(custom, 0) != null ? custom : null;
            });
            Drawable logo;
            CharSequence label;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                logo = info.loadIcon(pm);
                label = info.loadLabel(pm);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            TextView button = (TextView) getLayoutInflater().inflate(R.layout.top_app_button, bar, false);
            // pillKeys finds the bar's ends by id: without one of its own, every app button would be an end.
            button.setId(View.generateViewId());
            logo.setBounds(0, 0, size, size);
            button.setCompoundDrawablesRelative(logo, null, null, null);
            button.setCompoundDrawableTintList(null);
            button.setContentDescription(label);
            Intent target = launch;
            // An input: action switches the TV input, as the tile for the same app does.
            String inputId = LaunchActions.inputId(launchActions.get(pkg));
            if (inputId != null) button.setOnClickListener(v -> openInput(inputId));
            else button.setOnClickListener(v -> open(new Intent(target)));
            // After the network pill and the VPN pill; the Hebrew bar runs the other way, so before both there.
            bar.addView(button, bar.indexOfChild(getResources().getBoolean(R.bool.rtl_menu_bar) ? btnVpn : btnWifi));
            topAppButtons.add(button);
        }
    }

    @Override public void onLibraryUp() {
        if (adapter.appCount() == 0) { firstTopButton().requestFocus(); return; }
        int lastRow = ((adapter.appCount() - 1) / COLUMNS) * COLUMNS;
        focusTile(Math.min(lastRow + lastFocused % COLUMNS, adapter.appCount() - 1));
    }

    private void focusLibrary() {
        cancelPendingFocusScroll();
        int position = adapter.appCount();
        if (!adapter.isLibraryAt(position)) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            View button = holder.itemView.findViewById(R.id.btnLibrary);
            Rect visible = new Rect();
            if (button.getGlobalVisibleRect(visible) && visible.height() >= button.getHeight()) {
                if (button.requestFocus()) libraryFocusPending = false;
                return;
            }
        }
        if (!animations) {
            grid.scrollToPosition(position);
            grid.post(() -> {
                if (overlayOpen || !hasWindowFocus()) return;
                RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
                if (footer != null && adapter.isLibraryAt(adapter.appCount())
                        && footer.itemView.findViewById(R.id.btnLibrary).requestFocus()) libraryFocusPending = false;
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder footer = rv.findViewHolderForAdapterPosition(position);
                if (footer != null && footer.itemView.findViewById(R.id.btnLibrary).requestFocus())
                    libraryFocusPending = false;
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(position);
    }

    private void cancelPendingFocusScroll() {
        if (pendingFocusScroll != null) {
            grid.removeOnScrollListener(pendingFocusScroll);
            pendingFocusScroll = null;
            grid.stopScroll();
        }
    }

    private void focusTile(int position) {
        libraryFocusPending = false;
        cancelPendingFocusScroll();
        if (adapter.appCount() == 0) {
            if (adapter.isLibraryAt(0)) focusLibrary();
            else firstTopButton().requestFocus();
            return;
        }
        final int pos = adapter.focusablePosition(Math.max(0, Math.min(position, adapter.appCount() - 1)));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        if (!animations) {
            grid.scrollToPosition(pos);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View first = rv.getChildAt(0);
                View target = h != null ? h.itemView.findViewById(R.id.card)
                        : (first != null ? first.findViewById(R.id.card) : null);
                if (target != null) target.requestFocus();
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Some TVs (Coocaa OS) set the network time without ACTION_TIME_CHANGED, so keep checking until it is valid. */
    private final Runnable clockCheck = this::updateClockVisibility;

    private void updateClockVisibility() {
        boolean valid = System.currentTimeMillis() >= TIME_VALID_AFTER_MS;
        clockBlock.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
        handler.removeCallbacks(clockCheck);
        if (valid) {
            updateGreeting();
        } else {
            handler.postDelayed(clockCheck, 1_000L);
        }
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        // Locale-specific day parts (app language on Android 13+).
        // Indonesian: Pagi 00-10, Siang 10-15, Sore 15-18, Malam 18-24.
        // Spain (es-ES) and Catalan: "Buenas tardes" into the evening; "Buenas noches" from 21.
        // Latin American Spanish (es-MX, es-AR, …) keeps the default hours: evening strings are also
        // «Buenas noches», and there «Buenas noches» from ~17 matches local use better than Spain's 21.
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        if ("in".equals(lang) || "id".equals(lang)) {
            if (hour < 10) res = R.string.good_morning;
            else if (hour < 15) res = R.string.good_afternoon;
            else if (hour < 18) res = R.string.good_evening;
            else res = R.string.good_night;
        } else if (("es".equals(lang) && "ES".equals(country)) || "ca".equals(lang)) {
            if (hour < 6) res = R.string.good_night;
            else if (hour < 12) res = R.string.good_morning;
            else if (hour < 21) res = R.string.good_afternoon;
            else res = R.string.good_night;
        } else if ("da".equals(lang)) {
            // Danish day parts: "Godmorgen" until 11, then "God formiddag" to noon.
            if (hour < 5) res = R.string.good_night;
            else if (hour < 11) res = R.string.good_morning;
            else if (hour < 12) res = R.string.good_forenoon;
            else if (hour < 18) res = R.string.good_afternoon;
            else if (hour < 22) res = R.string.good_evening;
            else res = R.string.good_night;
        } else {
            if (hour < 5) res = R.string.good_night;
            else if (hour < 12) res = R.string.good_morning;
            else if (hour < 17) res = R.string.good_afternoon;
            else if (hour < 22) res = R.string.good_evening;
            else res = R.string.good_night;
        }
        String festiveGreeting = festive ? festival.greetingText(getResources()) : "";
        if (!festiveGreeting.isEmpty()) {
            greeting.setText(festiveGreeting);
            greeting.setTextColor(liftTowardsWhite(festival.accentColor(), 0.25f));
        } else {
            greeting.setText(greetingName.isEmpty() ? getString(res)
                    : getString(R.string.greeting_with_name, getString(res), greetingName));
            greeting.setTextColor(greetingColor);
        }
        if (shamsi) shamsiDate.setText(Shamsi.today(getResources()));
        if (lunar) lunarDate.setText(Lunar.today(getResources(), lunarOffset));
    }

    // ---- race-weekend panel (a theme's race_calendar) -----------------------------------------------

    private static final int RACE_RED = 0xFFFF3B2F;

    /**
     * Shows or hides the race-weekend panel for this minute. Text only, set once a minute with the clock:
     * nothing animates, so the panel costs the GPU nothing between ticks.
     */
    /**
     * The race panel's place under the clock. With game_day off it is exactly the F1 panel (updateRace). With it on,
     * each team's next game takes a turn there, and the theme's F1 weekend is one more turn drawn as today.
     */
    private void updatePanel() {
        if (gameDay == null) {
            updateRace();
            return;
        }
        handler.removeCallbacks(turnTick);
        long now = System.currentTimeMillis();
        List<GameDay.Turn> games = gameDay.turns();
        for (GameDay.Turn g : games) {
            if (g.endMs <= now && now - lastEndedRead > MINUTE_MS) {
                // A game is over: the add-on already has that team's next one.
                lastEndedRead = now;
                gameDay.read();
                break;
            }
        }
        RaceWeekend.State f1 = raceCalendar.isEmpty() ? null : RaceWeekend.at(raceCalendar, java.time.Instant.ofEpochMilli(now));
        List<GameDay.Turn> turns = GameDay.merge(games, f1, now);
        if (turns.isEmpty()) {
            racePanel.setVisibility(View.GONE);
            return;
        }
        int seconds = gameDay.turnSeconds();
        long since = SystemClock.elapsedRealtime() - rotationStart;
        int on = GameDay.current(turns.size(), since, seconds);
        GameDay.Turn t = turns.get(on);
        if (t.f1) {
            raceBar.setBackgroundResource(R.drawable.race_bar);
            raceSessions.setVisibility(View.VISIBLE);
            updateRace();
        } else {
            GradientDrawable bar = new GradientDrawable();
            bar.setCornerRadius(1.5f * density);
            bar.setColor(t.colour);
            raceBar.setBackground(bar);
            raceKicker.setText(t.topLine);
            if (GameDay.live(t, now)) {
                SpannableString live = new SpannableString("\u25CF  " + getString(R.string.game_live));
                live.setSpan(new ForegroundColorSpan(RACE_RED), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                raceHeadline.setText(live);
            } else {
                String in = RaceWeekend.countdown(getResources(), GameDay.until(t, now));
                raceHeadline.setText(t.word.isEmpty() ? in : getString(R.string.race_next, t.word, in));
            }
            // No session times for a game: the line takes no room, so the dots sit under the countdown.
            raceSessions.setVisibility(View.GONE);
            racePanel.setVisibility(View.VISIBLE);
        }
        showPager(turns.size(), on);
        if (turns.size() > 1 && !stopped) handler.postDelayed(turnTick, GameDay.delayToNextTurn(since, seconds));
    }

    /** One dot per turn, the one on screen bright; none for a single turn. */
    private void showPager(int count, int on) {
        if (count < 2) {
            racePager.setVisibility(View.GONE);
            return;
        }
        if (racePager.getChildCount() != count) {
            racePager.removeAllViews();
            int size = Math.round(5 * density);
            for (int k = 0; k < count; k++) {
                View dot = new View(this);
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                d.setColor(0xFFFFFFFF);
                dot.setBackground(d);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMarginEnd(size);
                racePager.addView(dot, lp);
            }
        }
        for (int k = 0; k < count; k++) racePager.getChildAt(k).setAlpha(k == on ? 0.9f : 0.3f);
        racePager.setVisibility(View.VISIBLE);
    }

    private void updateRace() {        // an empty calendar (a theme without race_calendar, or a reset) hides a panel the last theme showed
        RaceWeekend.State s = raceCalendar.isEmpty() ? null : RaceWeekend.at(raceCalendar, java.time.Instant.now());
        if (s == null) {
            racePanel.setVisibility(View.GONE);
            return;
        }
        RaceWeekend.Race race = s.race;
        raceKicker.setText(race.place.isEmpty()
                ? getString(R.string.race_kicker_no_place, race.round, race.shortName())
                : getString(R.string.race_kicker, race.round, race.shortName(), race.place));

        if (s.live != null) {
            SpannableString live = new SpannableString("\u25CF  " + getString(R.string.race_live, s.live.title(getResources())));
            live.setSpan(new android.text.style.ForegroundColorSpan(RACE_RED), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            raceHeadline.setText(live);
        } else if (s.next != null) {
            String in = RaceWeekend.countdown(getResources(), s.untilNext);
            raceHeadline.setText(s.next.isRace() ? getString(R.string.race_lights_out, in)
                    : getString(R.string.race_next, s.next.title(getResources()), in));
        } else {
            raceHeadline.setText(R.string.race_finished);
        }

        // FP1 FRI 11:00 PM   FP2 SAT 2:30 AM ...: done sessions fade, the live one is red, the next one bright.
        java.time.format.DateTimeFormatter when = java.time.format.DateTimeFormatter.ofPattern(
                ClockFormat.is24(this, clockFormat) ? "EEE HH:mm" : "EEE h:mm a", Locale.getDefault())
                .withZone(java.time.ZoneId.systemDefault());
        android.text.SpannableStringBuilder row = new android.text.SpannableStringBuilder();
        for (int i = 0; i < race.sessions.size(); i++) {
            RaceWeekend.Session session = race.sessions.get(i);
            if (i > 0) row.append("     ");
            int start = row.length();
            row.append(session.shortName(getResources())).append("  ").append(when.format(session.start).toUpperCase(Locale.getDefault()));
            int color;
            switch (s.statuses.get(i)) {
                case DONE: color = 0x47FFFFFF; break;
                case LIVE: color = RACE_RED; break;
                case NEXT: color = 0xFFFFFFFF; break;
                default: color = 0x99FFFFFF;
            }
            row.setSpan(new android.text.style.ForegroundColorSpan(color), start, row.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        raceSessions.setText(row);
        racePanel.setVisibility(View.VISIBLE);
    }

    private void refreshPillsAfterNextDraw() {
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Posted from inside the frame's traversal, so it runs after that frame is drawn. A post straight
                // from onResume would run first: the frame is only scheduled after onResume returns.
                handler.post(() -> {
                    if (isDestroyed()) return;
                    updateWifiLabel();
                    updateInputsButton();
                });
                return true;
            }
        });
        root.invalidate();   // a resume that changes nothing on screen still draws once, so the refresh runs
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    @SuppressWarnings("deprecation")
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false, validated = false;
        int icon = R.drawable.ic_wifi;
        // The festive light (diya flame or jack-o'-lantern candle): 0 is out (offline), 1 to 4 follow the Wi-Fi
        // signal, Ethernet burns steady at 4.
        int flame = 0;
        boolean steady = false;
        try {
            ConnectivityManager cm = getApplicationContext().getSystemService(ConnectivityManager.class);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                // Android reached the internet through it; a captive portal stays unvalidated until login.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) validated = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
                flame = 4;
                steady = true;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                flame = info != null ? Math.max(1, Math.min(4, WifiManager.calculateSignalLevel(info.getRssi(), 5))) : 3;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        if (isDestroyed()) return;
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpnPill) updateVpnPill(vpn);
        if (wifiIconOnly) {
            String desc = (vpn || vpnDown) && !vpnPill
                    ? (text.isEmpty() ? getString(vpn ? R.string.vpn : R.string.vpn_off)
                    : text + " " + getString(vpn ? R.string.vpn : R.string.vpn_off))
                    : text;
            if (!TextUtils.equals(btnWifi.getContentDescription(), desc)) {
                btnWifi.setContentDescription(desc);
            }
            if (btnWifi.getText() != null && btnWifi.getText().length() > 0) btnWifi.setText(null);
        } else if ((vpn || vpnDown) && !vpnPill) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The Wi-Fi icon and its
            // gap are counted by hand: until the pill has a resolved layout direction (the first call, from
            // onResume, runs before that) getTotalPaddingStart/End leave the compound drawables out, which
            // left the name about 31dp too much room and the badge cut off.
            TextPaint paint = btnWifi.getPaint();
            Drawable[] compound = btnWifi.getCompoundDrawablesRelative();
            int iconRoom = compound[0] != null ? compound[0].getIntrinsicWidth() + btnWifi.getCompoundDrawablePadding() : 0;
            float room = btnWifi.getMaxWidth() - btnWifi.getPaddingStart() - btnWifi.getPaddingEnd() - iconRoom
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            String name = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            // A long badge ("VPN отключён") can leave room for a stub like "MT…", which says nothing and, after
            // rounding, can push the badge itself out of the pill. Show the badge alone then. On Ethernet the
            // icon already names the link, and "Ethernet" cut to "Ethe…" or not depended on the font size (#961),
            // so Ethernet always shows the icon and the badge alone.
            // The space stays even then: a line holding only the span gets no font metrics and collapses.
            boolean stub = name.length() < text.length() && name.length() < 5;
            text = stub || icon == R.drawable.ic_ethernet ? "" : name;
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!TextUtils.equals(btnWifi.getText(), label)) btnWifi.setText(label);
        } else if (!TextUtils.equals(btnWifi.getText(), text)) {
            btnWifi.setText(text);
        }
        if (festive && lamp != null) {
            if (online && flame == 0) flame = 3;   // online some other way (cellular, USB tethering)
            lamp.setSteady(steady);
            lamp.setFlame(flame);
        } else {
            if (icon != wifiIcon) {
                wifiIcon = icon;
                btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            }
            ColorStateList tint;
            if (ExpressiveGlass.isLightGlass()) {
                if (wifiOnlineTint == null) {
                    tint = ExpressiveGlass.adaptivePillTextColors();
                } else if (wifiNoInternetTint != null) {
                    tint = ColorStateList.valueOf(!online ? 0xFFDC2626 : validated ? 0xFF15803D : 0xFFB45309);
                } else {
                    tint = online ? ColorStateList.valueOf(0xFF15803D) : ExpressiveGlass.adaptivePillTextColors();
                }
            } else {
                tint = online ? wifiOnlineTint : wifiPlainTint;
                // wifi_status_warnings: orange while connected but not validated (a captive portal), red while offline.
                if (wifiNoInternetTint != null) tint = !online ? wifiOfflineTint : validated ? wifiOnlineTint : wifiNoInternetTint;
            }
            wifiFocusAccent = wifiOnlineTint == null ? 0
                    : (!online ? (wifiNoInternetTint != null ? 0xFFFF453A : 0)
                    : (validated || wifiNoInternetTint == null ? 0xFF34C759 : 0xFFFF9F0A));
            if (tint != null && btnWifi.getCompoundDrawableTintList() != tint) {
                btnWifi.setCompoundDrawableTintList(tint);
            }
        }
    }

    /** config.xml icon_only_buttons. */
    private boolean iconOnlyButtons;
    /** config.xml top_menu: Inputs, Wi-Fi and Settings folded into one gear. */
    private boolean topMenu;
    /** show_inputs_pill as configured: top_menu clears showInputsPill, so its Inputs row reads this. */
    private boolean showInputsMenuRow = true;
    private boolean launcherSettingsButton;
    /** icon_only_buttons without wifi_label_width: the Wi-Fi pill shows no network name. */
    private boolean wifiIconOnly;
    /** config.xml wifi_status_color: the Wi-Fi icon's green while online and its usual pill colours; null while off. */
    private ColorStateList wifiOnlineTint, wifiPlainTint;
    /** wifi_status_warnings (with wifi_status_color): orange without internet, red offline; null while off. */
    private ColorStateList wifiNoInternetTint, wifiOfflineTint;
    /** The app library button in the top bar (library_button). */
    private View btnLibraryTop;

    /** Turns a labelled top pill into the round TopButtonIcon shape: no text, the icon centred by even padding. */
    private void makeIconOnly(TextView pill) {
        pill.setContentDescription(pill.getText());
        pill.setText(null);
        pill.setMaxWidth(Integer.MAX_VALUE);
        int pad = Math.round(10 * density);
        pill.setPaddingRelative(pad, 0, pad, 0);
        pill.setCompoundDrawablePadding(0);
        pill.getLayoutParams().width = Math.round(42 * density);
    }

    // ---- festive skin ------------------------------------------------------------------------------

    /**
     * Swaps the pill icons for the festival's: a bell for Inputs that swings when focused, a diya for the
     * network whose flame follows the connection, and a marigold for Settings that turns when focused. Halloween
     * has its own: a bat for Inputs that flaps, a jack-o'-lantern whose face glows while online, and a ghost for
     * Settings that floats up.
     */
    private void applyFestiveSkin(TextView inputs, TextView settings) {
        if (festival.isHalloween()) {
            SpookyIconDrawable bat = new SpookyIconDrawable(getResources(), density, SpookyIconDrawable.BAT);
            SpookyIconDrawable ghost = new SpookyIconDrawable(getResources(), density, SpookyIconDrawable.GHOST);
            JackOLanternDrawable pumpkin = new JackOLanternDrawable(getResources(), density);
            bat.setReducedMotion(reducedMotion);
            ghost.setReducedMotion(reducedMotion);
            pumpkin.setReducedMotion(reducedMotion);
            lamp = pumpkin;
            setFestiveIcon(inputs, bat);
            setFestiveIcon(btnWifi, pumpkin);
            setFestiveIcon(settings, ghost);
            addFocusAction(inputs, bat::nudge);
            addFocusAction(settings, ghost::nudge);
            return;
        }
        bell = new BellDrawable(density);
        marigold = new MarigoldDrawable(density);
        DiyaDrawable diya = new DiyaDrawable(density);
        bell.setReducedMotion(reducedMotion);
        marigold.setReducedMotion(reducedMotion);
        diya.setReducedMotion(reducedMotion);
        lamp = diya;
        setFestiveIcon(inputs, bell);
        setFestiveIcon(btnWifi, diya);
        setFestiveIcon(settings, marigold);
        addFocusAction(inputs, bell::ring);
        addFocusAction(settings, marigold::turn);
    }

    private static void setFestiveIcon(TextView pill, Drawable icon) {
        // The pills tint their icons to follow focus; the festive ones keep their own colours.
        pill.setCompoundDrawableTintList(null);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
    }

    /** Runs {@code action} when the view gains focus, keeping any focus listener it already had. */
    private static void addFocusAction(View view, Runnable action) {
        View.OnFocusChangeListener previous = view.getOnFocusChangeListener();
        view.setOnFocusChangeListener((v, focused) -> {
            if (previous != null) previous.onFocusChange(v, focused);
            if (focused) action.run();
        });
    }

    private void playPetalShower() {
        if (isFinishing() || isDestroyed()) return;
        if (festival.isHalloween()) {
            BatFlightView bats = new BatFlightView(this);
            root.addView(bats, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            bats.play(16, 2800, () -> root.removeView(bats));
            return;
        }
        PetalShowerView shower = new PetalShowerView(this);
        root.addView(shower, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shower.play(60, 2200, () -> root.removeView(shower));
    }

    private static int liftTowardsWhite(int argb, float amount) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (int) ((255 - r) * amount);
        g += (int) ((255 - g) * amount);
        b += (int) ((255 - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. With
     * vpn_pill the VPN app has its own pill, so this one always opens Wi-Fi settings.
     */
    private void openNetworkPill() {
        Intent i = vpnPill ? null : vpnAppIntent();
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }

    /** vpn_app's launch intent, or null when none is set or it is not installed. */
    private Intent vpnAppIntent() {
        if (vpnApp.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        return i != null ? i : getPackageManager().getLaunchIntentForPackage(vpnApp);
    }

    /**
     * vpn_pill: the VPN app, where it is switched on and off. Without one installed, the App library to pick from, or
     * Android's VPN settings when the theme turned the library off.
     */
    private void openVpnPill() {
        Intent i = vpnAppIntent();
        if (i != null || !appLibraryEnabled) {
            open(i != null ? i : systemIntent(Settings.ACTION_VPN_SETTINGS));
            return;
        }
        onLibraryRequested();
    }

    /** vpn_pill: the pill outlined in green with a lock while a VPN is up, outlined in red "VPN off" while it is not;
     *  solid with a white ring only while focused. */
    private void updateVpnPill(boolean vpn) {
        int state = VpnBadge.pillState(vpn, vpnAppIntent() != null);
        if (state == VpnBadge.PILL_HIDDEN) {
            if (btnVpn.getVisibility() == View.GONE) return;
            boolean focused = btnVpn.hasFocus();
            btnVpn.setVisibility(View.GONE);
            if (focused) (btnWifi.getVisibility() == View.VISIBLE ? btnWifi : firstTopButton()).requestFocus();
            return;
        }
        if (state != vpnPillState) {
            vpnPillState = state;
            boolean up = state == VpnBadge.PILL_UP;
            // Frosted pill stays dark glass on focus (wobbly Fresnel ring surrounds it), so keep the vivid state hue.
            int stateColor = up ? VpnBadge.GREEN : VpnBadge.RED;
            ColorStateList ink = ColorStateList.valueOf(
                    ExpressiveGlass.isLightGlass() ? ExpressiveGlass.adaptColorForLight(stateColor) : stateColor);
            ColorStateList plainVpnInk = ExpressiveGlass.isLightGlass()
                    ? ExpressiveGlass.adaptivePillTextColors() : vpnPlainInk;
            if (iconOnlyVpn) {
                // An open lock on the plain glass button, or the closed lock outlined in green like the pill (solid
                // while focused); the words go to talkback.
                btnVpn.setContentDescription(getString(up ? R.string.vpn : R.string.vpn_off));
                btnVpn.setBackground(new FrostedPillDrawable(btnVpn, root, wallpaperPlayer, heroArt, this::pillAmbientColor,
                        density, 21f * density, up ? VpnBadge.GREEN : 0));
                btnVpn.setCompoundDrawablesRelativeWithIntrinsicBounds(VpnBadge.iconOnlyLock(up), 0, 0, 0);
                btnVpn.setCompoundDrawableTintList(up ? ink : plainVpnInk);
                btnVpn.setVisibility(View.VISIBLE);
                return;
            }
            btnVpn.setText(up ? R.string.vpn : R.string.vpn_off);
            btnVpn.setTextColor(ink);
            btnVpn.setBackground(new FrostedPillDrawable(btnVpn, root, wallpaperPlayer, heroArt, this::pillAmbientColor,
                    density, 21f * density, up ? VpnBadge.GREEN : VpnBadge.RED));
            btnVpn.setCompoundDrawablesRelativeWithIntrinsicBounds(up ? R.drawable.ic_lock : 0, 0, 0, 0);
            btnVpn.setCompoundDrawableTintList(ink);
        }
        btnVpn.setVisibility(View.VISIBLE);
    }


    /** Every settings entry shares the standard intent and the vendor fallback (SystemSettings). */
    private Intent systemIntent(String standardAction) {
        return SystemSettings.intent(this, standardAction);
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        if (isDestroyed()) return;
        if (!showInputsPill) {
            btnInputs.setVisibility(View.GONE);
            return;
        }
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty()
                || (coocaaSources && !CoocaaSources.list(this).isEmpty());
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                // Inputs hidden in Settings > Inputs are left out of the menu, so they don't count here either.
                if (info.isPassthroughInput() && !info.isHidden(this)) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** boot_to_live_tv, or the Open on start app: the first time Home comes up after the TV starts, open that app
     *  instead. Once per boot, keyed on Settings.Global.BOOT_COUNT and committed to disk before the app starts: on a
     *  low-memory TV the app can push the launcher out of memory, and an apply() still in flight was lost then, so
     *  every Home press opened the app again. */
    private boolean openLiveTvAfterBoot() {
        String app = openOnStartChoice ? getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getString(OpenOnStart.KEY, "") : "";
        if (!bootToLiveTv && app.isEmpty()) return false;
        int boot = OpenOnStart.boot(this);
        SharedPreferences prefs = getSharedPreferences(OpenOnStart.BOOT_PREFS, MODE_PRIVATE);
        String target = bootToLiveTv ? liveTvPkg : app;
        if (!OpenOnStart.due(boot, prefs.getInt(OpenOnStart.BOOT_KEY, -1), target, getPackageName())) return false;
        if (!prefs.edit().putInt(OpenOnStart.BOOT_KEY, boot).commit()) return false;
        if (bootToLiveTv && bootInputId != null) {
            openInput(bootInputId);
            return true;
        }
        Intent i = bootToLiveTv ? liveTvIntent() : OpenOnStart.intent(getPackageManager(), app);
        if (i == null) return false;
        return openWhenReady(i);
    }

    /** open_on_start_on_wake: the remote's power button is a light sleep on some sets, with no boot for
     *  openLiveTvAfterBoot to catch. */
    private void openStartAppOnWake() {
        String app = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getString(OpenOnStart.KEY, "");
        if (app.isEmpty() || app.equals(getPackageName())) return;
        Intent i = OpenOnStart.intent(getPackageManager(), app);
        if (i != null) openWhenReady(i);
    }

    private boolean openWhenReady(Intent i) {
        // open_on_start_network_wait: Home stays on screen until the network is back or the wait is over.
        if (openOnStartWaitMs > 0 && !OpenOnStart.online(this)) {
            startPending = i;
            startDeadline = SystemClock.elapsedRealtime() + openOnStartWaitMs;
            handler.postDelayed(openWhenOnline, openOnStartWaitMs);
            // Validated between the check above and startPending: networkCallback may have skipped it.
            if (OpenOnStart.online(this)) handler.post(openWhenOnline);
            return false;
        }
        open(i);
        return true;
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9): a
     *  service, or an activity on sets whose panel is a dialog (BYINTEK U4's QuickSourceActivity). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        int eq = sourcePanelExtra.indexOf('=');
        if (eq > 0) {
            try {
                i.putExtra(sourcePanelExtra.substring(0, eq).trim(),
                        Integer.parseInt(sourcePanelExtra.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            if (getPackageManager().resolveActivity(i, 0) != null) {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void open(Intent i) {
        String pkg = i.getComponent() != null ? i.getComponent().getPackageName() : i.getPackage();
        if (isGlassAddon(pkg)) {
            boolean isQuickBar = i.getComponent() != null
                    && i.getComponent().getClassName().endsWith("QuickActivity");
            captureLiveHomeBackdropThen(() -> {
                if (!isQuickBar) setAddonModalOpen(true);
                attachAddonBackdropExtras(i, true);
                if (isQuickBar) ExpressiveGlass.clearHomeLiveBackdrop();
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                    overridePendingTransition(0, 0);
                } catch (ActivityNotFoundException | SecurityException e) {
                    if (!isQuickBar) setAddonModalOpen(false);
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** `input_sources` in config.xml or a theme: "HW5=23" maps the framework input whose id ends in /HW5 to a vendor
     *  source number; "HW8=-" leaves that input out of the Inputs menu (a port the board reports but the set
     *  does not have). Returns the number, -1 for a left-out input, or null when the input is not listed. */
    private Integer mappedSource(String inputId) {
        return InputSources.mapped(inputSources, inputId);
    }

    /** Switches to a passthrough input (HDMI, AV). An input mapped in `input_sources` is opened by sending
     *  `input_action` with its source number as the int extra "input" (Xiaomi China firmware, whose tvplayer
     *  answers com.xiaomi.mitv.tvplayer.EXTSRC_PLAY and nothing else). Otherwise the standard way is
     *  ACTION_VIEW on its channel URI, which the Live TV app handles; where nothing takes that, the
     *  configured `input_action` is sent with the same URI instead (XGIMI's HDMI player only answers
     *  com.xgimi.action.hdmiPlayer). An `input_action` written as "package/.Activity" starts that activity
     *  itself, ahead of the standard way, for firmware that answers the channel URI with "unsupported"
     *  (Redmi X65, MiTV-NSOP0: com.xiaomi.mitv.tvplayer/.ExternalSourceActivity). With `input_uri_scheme` set (YaOS: live-tv) the same URI goes out
     *  with that scheme and no MIME type. A player guarded by a permission this build does not declare
     *  throws SecurityException, which used to take Home down with it (YaOS before the manifest asked
     *  for ACCESS_TV_SOURCE). */
    private void openInput(String inputId) {
        // A CEC device is a child input of its HDMI port; the vendor source number belongs to the port
        // (MiTV-MFFU0). Only that lookup uses the parent: the generic path below still opens the child.
        String sourceId = inputId;
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            try {
                TvInputInfo info = tim.getTvInputInfo(inputId);
                if (info != null && info.getParentId() != null) sourceId = info.getParentId();
            } catch (RuntimeException ignored) {
            }
        }
        Integer source = mappedSource(sourceId);
        if (source != null && source >= 0 && !inputAction.isEmpty()) {
            try {
                startActivity(inputActionIntent().putExtra("input", source.intValue())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // fall through to the standard way
            }
        } else if (source == null && InputSources.isComponent(inputAction)) {
            // An activity named in input_action is the vendor's own source picker (Xiaomi: tvplayer's
            // ExternalSourceActivity). Its firmware answers the standard channel URI with "unsupported", so the
            // row goes straight there; the picker is the overlay the remote's Source button opens.
            try {
                startActivity(inputActionIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // fall through to the standard way
            }
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = (inputUriScheme.isEmpty()
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                : new Intent(Intent.ACTION_VIEW, uri.buildUpon().scheme(inputUriScheme).build()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Aim it at the TV app first: apps installed later (a streaming app, a file manager) can claim the same
        // channel URI too, and with two or more handlers Android shows a chooser instead of the input (WhaleOS).
        if (!liveTvPkg.isEmpty()) {
            try {
                startActivity(new Intent(view).setPackage(liveTvPkg));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(inputActionIntent().setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** `input_action` as an intent: an explicit component when it reads "package/.Activity", else an action. */
    private Intent inputActionIntent() {
        ComponentName component = InputSources.isComponent(inputAction)
                ? ComponentName.unflattenFromString(inputAction) : null;
        return component != null ? new Intent().setComponent(component) : new Intent(inputAction);
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        // A configured source panel and no Live TV row to offer next to it: the panel is the whole menu.
        // The BYINTEK U4 needs this, as its TvInputManager lists three HDMI ports and an AV input it doesn't have.
        List<CoocaaSources.Source> coocaa = coocaaSources ? CoocaaSources.list(this) : Collections.emptyList();
        if (coocaa.isEmpty() && !sourcePanelService.isEmpty() && liveTvIntent() == null) {
            openSourcePanel();
            return;
        }
        // top_menu: the pill is gone, so the list hangs under the gear, growing inward from the right.
        // Read the switch, not the pill: show_inputs_pill=false hides the pill too, and there the list
        // keeps its usual place.
        boolean underGear = topMenu;
        View anchor = findViewById(underGear ? R.id.btnSettings : R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        // inputs_connected_only: ports without a connected device and the AV jacks fold behind a "More inputs" row.
        boolean connectedOnly = inputsConnectedOnly;
        List<View> folded = new ArrayList<>();
        boolean sawNonHdmi = false;
        // An explicit root URI asks the Live TV app to leave any HDMI source selected by its own UI.
        boolean rootChannel = TvContract.Channels.CONTENT_URI.toString().equals(liveTvInputId);
        String tunerId = null;
        boolean tunerIsHw = false;
        boolean tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // An HDMI-CEC device (an Apple TV, a soundbar) gets an input of its own whose parent is the
            // port it sits on, so the port and the device would each get a row (Vestel MT7332: "HDMI 1"
            // and "HDMI Apple TV"). The device takes its port's row instead, and a bare port is listed
            // only when nothing on it speaks CEC.
            Set<String> inputIds = new HashSet<>();
            for (TvInputInfo info : inputs) inputIds.add(info.getId());
            Map<String, List<TvInputInfo>> cecDevices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                String parent = info.getParentId();
                if (parent == null || !inputIds.contains(parent)) continue;
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                List<TvInputInfo> onPort = cecDevices.get(parent);
                if (onPort == null) cecDevices.put(parent, onPort = new ArrayList<>());
                onPort.add(info);
            }
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            // inputs_cec_state_only: the TV puts port states on the wrong port (TCL UnionTV, Android 9), so
            // ports show none, and a CEC device row shows its own state, which the CEC bus keeps right.
            boolean hdmiStateBogus = inputsCecStateOnly || (hdmiPorts > 1 && hdmiConnected == hdmiPorts);
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (liveTvInputId.isEmpty() || rootChannel || liveTvInputId.equals(info.getId()))) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    // Among tuners of the same kind, skip an analog input when another is present.
                    // MediaTek names them AnalogInputService and TunerInputService; alphabetical order
                    // would pick the analog one, and Live TV then opens a dead ATV source.
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null
                            || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog
                                    && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                // input_sources can leave out ports the board reports but the set does not have ("HW8=-").
                if (Integer.valueOf(-1).equals(mappedSource(info.getId()))) continue;
                // A CEC device's row is added with its port, below.
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = InputNames.tidy(info.loadCustomLabel(this));
                boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) {
                    View line = addSeparator(rows);
                    if (connectedOnly) fold(line, folded);
                }
                if (!hdmi) sawNonHdmi = true;
                List<TvInputInfo> devices = cecDevices.get(info.getId());
                if (devices != null) {
                    // "Apple TV · HDMI 1": the device's own name (or the one given to it or to its port in
                    // Settings > Inputs), then the port. Opening the device's input also makes it the CEC
                    // active source, which wakes it.
                    for (TvInputInfo device : devices) {
                        CharSequence name = InputNames.tidy(device.loadCustomLabel(this));
                        if (name == null || name.length() == 0) name = named ? custom : device.loadLabel(this);
                        String deviceLabel = name == null || name.length() == 0 ? label : name + " \u00B7 " + label;
                        final String deviceId = device.getId();
                        int state = hdmiStateBogus && !inputsCecStateOnly ? -1 : tim.getInputState(deviceId);
                        View row = addInputRow(rows, popup, R.drawable.ic_input, deviceLabel, state, () -> openInput(deviceId));
                        if (connectedOnly && InputFold.folds(true, state)) fold(row, folded);
                    }
                    continue;
                }
                // The name given in Settings > Inputs (e.g. "MacBook Pro") goes first, the port after it.
                if (named) label = custom + " \u00B7 " + label;
                final String id = info.getId();
                // Analog AV jacks have no hotplug detection, and some TVs report them as always connected
                // (TCL Smart TV Pro G03 says "Connected" with nothing plugged in), so AV rows show no state.
                int state = !hdmi || hdmiStateBogus ? -1 : tim.getInputState(id);
                View row = addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
                if (connectedOnly && InputFold.folds(hdmi, state)) fold(row, folded);
            }
        }
        if (!folded.isEmpty()) {
            View more = getLayoutInflater().inflate(R.layout.item_input, rows, false);
            ((ImageView) more.findViewById(R.id.icon)).setImageResource(R.drawable.ic_input);
            ((TextView) more.findViewById(R.id.title)).setText(R.string.more_inputs);
            more.findViewById(R.id.status).setVisibility(View.GONE);
            more.findViewById(R.id.dot).setVisibility(View.GONE);
            more.setOnClickListener(v -> {
                for (View f : folded) f.setVisibility(View.VISIBLE);
                rows.removeView(more);
                panel.post(() -> ExpressiveGlass.invalidateLiquidPanels(panel));
                if (animations && !reducedMotion) {
                    int n = 0;
                    for (View f : folded) {
                        if (n++ >= 8) break;
                        f.setAlpha(0f);
                        f.setTranslationY(6f * density);
                        f.animate().alpha(1f).translationY(0f)
                                .setStartDelay(n * 22L).setDuration(180L)
                                .setInterpolator(Motion.easeOutExpo())
                                .withEndAction(() -> f.animate().setStartDelay(0L)).start();
                    }
                }
                for (View f : folded) {
                    if (f.isFocusable()) { f.requestFocus(); break; }
                }
            });
            Motion.attachRowFocus(more, density, reducedMotion);
            rows.addView(more);
        }
        // Coocaa OS registers nothing with TvInputManager; com.skyworth.tv lists its sources instead.
        if (rows.getChildCount() == 0) {
            for (CoocaaSources.Source s : coocaa) {
                int icon = "HDMI".equals(s.name) ? R.drawable.ic_input
                        : "VOD".equals(s.name) ? R.drawable.ic_livetv : R.drawable.ic_av;
                addInputRow(rows, popup, icon, s.label, -1, () -> {
                    if (!CoocaaSources.open(this, s)) {
                        Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            // Tune that input only when the Live TV package itself handles the URI.
            // The unrestricted input player also resolves it (that is how HDMI opens), but on the
            // AIWA AI PONT SA that player stays black, and the Live TV package does not handle the
            // URI at all, which used to toast "Not available on this TV". Keep the leanback activity
            // then: it is the same path as the Live TV tile. Do not drop the package restriction.
            // An explicit tuner uses its channel collection (e.g. DVB-S rather than DVB-T/C).
            // Keep the legacy passthrough route for automatic selection: some OEM players need it.
            // If the configured input is absent, tunerId stays null and the app opens normally.
            boolean configured = !liveTvInputId.isEmpty() && !rootChannel;
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(configured ? TvContract.buildChannelsUriForInput(tunerId)
                                    : TvContract.buildChannelUriForPassthroughInput(tunerId),
                            configured ? TvContract.Channels.CONTENT_TYPE : TvContract.Channels.CONTENT_ITEM_TYPE)
                    .setPackage(liveTvPkg);
            ComponentName handler = tuned.resolveActivity(getPackageManager());
            if (handler != null) {
                // On some MediaTek sets the tuner passthrough URI leaves the app's HDMI source selected.
                // The stock launcher addresses the same activity with the root channel URI instead (#1941).
                liveTv = rootChannel
                        ? new Intent(Intent.ACTION_VIEW).setData(TvContract.Channels.CONTENT_URI).setComponent(handler)
                        : tuned;
            }
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            if (openPopup == popup) openPopup = null;
            updateBackdropBlur();
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int pad = FocusRingController.wrapInsetPx(density);
        popup.setWidth(Math.max(width, Math.min(panel.getMeasuredWidth(), (int) (360 * density))) + 2 * pad);

        int alignment = getResources().getBoolean(R.bool.rtl_menu_bar) != underGear ? Gravity.END : Gravity.START;
        dropIn(popup, panel, anchor, alignment);
    }

    private static void fold(View view, List<View> folded) {
        view.setVisibility(View.GONE);
        folded.add(view);
    }

    private static void focusFirstRow(View panel) {
        View rows = panel.findViewById(R.id.rows);
        if (!(rows instanceof ViewGroup)) return;
        ViewGroup vg = (ViewGroup) rows;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.isFocusable()) {
                child.requestFocus();
                return;
            }
        }
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow prevPopup = openPopup;
        openPopup = null;
        ExpressiveGlass.applyPanelSurface(panel, density, 22f, false, false);
        int pad = FocusRingController.wrapInsetPx(density);
        FrameLayout ringHost = FocusRingController.wrapPopup(panel, density, animations, reducedMotion);
        final Runnable[] dismissRunner = new Runnable[1];
        final boolean[] dismissing = new boolean[1];
        final PopupWindow[] popupRef = new PopupWindow[1];
        FrameLayout keyHost = new FrameLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                        PopupWindow p = popupRef[0];
                        if (!dismissing[0] && p != null && p.isShowing()) {
                            dismissing[0] = true;
                            if (openPopup == p) openPopup = null;
                            if (!hasBackdropModal()) setBackdropBlur(false);
                            if (dismissRunner[0] != null) dismissRunner[0].run();
                            else p.dismiss();
                        }
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        keyHost.setClipChildren(false);
        keyHost.setClipToPadding(false);
        keyHost.setTag(dismissRunner);
        keyHost.addView(ringHost, new FrameLayout.LayoutParams(-1, -1));
        PointerSupport.install(keyHost);
        UiClickSound.attachFocusSound(keyHost);
        final PopupWindow popup = new PopupWindow(keyHost, width + 2 * pad, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupRef[0] = popup;
        openPopup = popup;
        if (prevPopup != null && prevPopup.isShowing()) prevPopup.dismiss();
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            dismissing[0] = false;
            Motion.cancelPanelAnim(keyHost);
            ringHost.animate().cancel();
            panel.animate().cancel();
            if (openPopup == popup) openPopup = null;
            updateBackdropBlur();
        });
        return popup;
    }

    /** Drop in: pre-warms blur FBO + PopupWindow surface on Frame 0, then starts both 60 fps animations on Frame 1. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        int pad = FocusRingController.wrapInsetPx(density);
        int xOff = gravity == Gravity.END ? pad : -pad;
        boolean reduce = reducedMotion || !animations;
        captureLiveHomeBackdropThen(() -> {
            if (openPopup != popup || isFinishing() || isDestroyed()) return;
            prepareBackdropBlurForPopup();
            View animTarget = popup.getContentView() != null ? popup.getContentView() : panel;
            animTarget.setAlpha(0f);
            if (!reduce) {
                animTarget.setPivotY(pad);
                animTarget.setScaleX(0.94f);
                animTarget.setScaleY(0.90f);
                animTarget.setTranslationY(-44f * density);
            }
            if (animTarget.getTag() instanceof Runnable[]) {
                ((Runnable[]) animTarget.getTag())[0] = () -> Motion.dismissDropdown(
                        animTarget, density, false, pad, reduce, popup::dismiss);
            }
            popup.showAsDropDown(anchor, xOff, (int) (10 * density) - pad, gravity);
            ExpressiveGlass.applyAdaptiveContentTheme(panel);
            focusFirstRow(panel);
            animTarget.post(() -> animTarget.postOnAnimation(() -> {
                if (openPopup != popup || !popup.isShowing() || isFinishing() || isDestroyed()) return;
                startBackdropBlurFadeIn();
                Motion.revealDropdown(animTarget, density, false, pad, reduce);
            }));
        });
    }

    private View addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(ExpressiveGlass.adaptiveDividerColor());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
        return line;
    }

    private View addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.setBackground(ExpressiveGlass.makeAdaptiveRowBg(density, 16f, false));
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        Motion.attachRowFocus(row, density, reducedMotion || !animations);
        ExpressiveGlass.applyAdaptiveContentTheme(row);
        rows.addView(row);
        return row;
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /** top_menu: the gear's dropdown, standing in for the Inputs, Wi-Fi and Settings pills. */
    private void showTopMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (220 * density));
        // Inputs opens its own list once this one has closed.
        if (showInputsMenuRow) addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, () -> {
            holdBackdropBlurForHandoff();
            root.post(this::showInputs);
        });
        addInputRow(rows, popup, R.drawable.ic_wifi, getString(R.string.quick_network), -1, this::openNetworkPill);
        CharSequence network = wifiIconOnly ? btnWifi.getContentDescription() : btnWifi.getText();
        if (!TextUtils.isEmpty(network)) {
            TextView status = rows.getChildAt(rows.getChildCount() - 1).findViewById(R.id.status);
            status.setText(network);
            status.setVisibility(View.VISIBLE);
        }
        addInputRow(rows, popup, R.drawable.ic_swap_vert, getString(R.string.reorder), -1, this::toggleReorder);
        if (launcherSettingsButton) {
            addInputRow(rows, popup, R.drawable.ic_home_settings_pill, getString(R.string.launcher_settings), -1, () -> {
                holdBackdropBlurForHandoff();
                openLauncherSettingsAnimated();
            });
        } else {
            addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.settings), -1,
                    () -> open(systemIntent(Settings.ACTION_SETTINGS)));
        }
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int pad = FocusRingController.wrapInsetPx(density);
        popup.setWidth(Math.max((int) (220 * density), Math.min(panel.getMeasuredWidth(), (int) (360 * density))) + 2 * pad);
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.START : Gravity.END);
    }

    /** The header opens launcher settings directly; hold OK keeps the extra home actions available. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        if (!launcherSettingsButton) addSettingsMenuRow(rows, popup, R.string.launcher_settings, () -> {
            holdBackdropBlurForHandoff();
            openLauncherSettingsAnimated();
        });
        // Firmware that ships its own settings app next to the AOSP one (Xiaomi China is one): the
        // vendor app holds pages the AOSP settings has no row for, such as Account & Security with
        // the ADB switch. Offered here only while one of them is installed.
        if (SystemSettings.vendorIntent(this) != null) {
            addSettingsMenuRow(rows, popup, R.string.native_settings, () -> {
                Intent vendor = SystemSettings.vendorIntent(this);
                if (vendor != null) open(vendor);
            });
        }
        if (moduleCards) addSettingsMenuRow(rows, popup, R.string.module_cards, () -> {
            holdBackdropBlurForHandoff();
            showModuleCards();
        });
        if (dockRow) addSettingsMenuRow(rows, popup, R.string.dock_edit, this::startDockEdit);
        addSettingsMenuRow(rows, popup, R.string.reorder, this::toggleReorder);
        // A launcher may not restart the TV itself; the Home key helper can open the system power menu instead.
        if (HomeKeyService.canOpenPowerMenu()) addSettingsMenuRow(rows, popup, R.string.power_menu, HomeKeyService::openPowerMenu);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int pad = FocusRingController.wrapInsetPx(density);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()) + 2 * pad);
        // Hebrew puts Settings at the left of the header: grow the menu inward from it.
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
    }

    /** A tile configured with launch_actions "input:<id>" (config.xml): switch to that TV input directly,
     *  the same path the Inputs menu's own rows use, instead of opening the input's owning app normally. */
    @Override
    public void onOpenInput(String inputId) {
        openInput(inputId);
    }

    /**
     * Hold OK on a tile: a glass menu next to it. Move starts reorder mode with this tile already picked up;
     * Remove from Home takes the shortcut off (only with the app library on, where one press adds it back);
     * App info opens the TV's page for the app, which is what holding OK used to do.
     */
    // ---- hold OK ---------------------------------------------------------------------------------
    // View's own long click misses on some remotes: an IR remote that sends its repeat frames as separate
    // presses, or a Bluetooth one whose repeats land after a tile rebind, never holds one DOWN long enough.
    // So the launcher times the hold itself: OK down on a tile, still down (or still repeating) after
    // HOLD_OK_MS opens the tile menu, and the UP that follows is eaten so it doesn't also open the app.

    private static final long HOLD_OK_MS = 550;
    private long holdOkDown = -1;
    private int holdOkPosition = -1;
    private String holdOkKey;    // the app under OK when it went down; a tile rebuild can move another app to that slot
    private boolean holdOkFired;
    private final Runnable holdOkTimer = this::fireHoldOk;

    private static boolean isOkKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    /** True when the event belongs to a hold that opened the tile menu and must not reach the tile. */
    private boolean holdOk(KeyEvent event) {
        if (!tileMenuEnabled || !isOkKey(event.getKeyCode())) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                holdOkFired = false;
                handler.removeCallbacks(holdOkTimer);
                holdOkPosition = reordering || openPopup != null || openFolder != null
                        || cardPrompt != null || folderPrompt != null ? -1 : focusedTilePosition();
                holdOkKey = holdOkPosition < 0 || holdOkPosition >= adapter.appCount() ? null : adapter.keyAt(holdOkPosition);
                if (holdOkKey == null) {
                    holdOkDown = -1;
                    return false;
                }
                holdOkDown = event.getDownTime();
                handler.postDelayed(holdOkTimer, HOLD_OK_MS);
                return false;
            }
            if (holdOkFired) return true;
            if (holdOkDown >= 0 && event.getEventTime() - holdOkDown >= HOLD_OK_MS) {
                fireHoldOk();
                return true;
            }
            return false;
        }
        // ACTION_UP
        handler.removeCallbacks(holdOkTimer);
        holdOkDown = -1;
        holdOkKey = null;
        if (holdOkFired) {
            holdOkFired = false;
            View f = getCurrentFocus();
            if (f != null) f.setPressed(false);
            return true;
        }
        return false;
    }

    /**
     * Stops timing a held OK when the launcher is paused, loses its window or goes away, so the menu can't open
     * behind another screen. An interrupted press still counts as handled: its UP, if it comes back here, is eaten
     * rather than opening the tile.
     */
    private void cancelHoldOk() {
        handler.removeCallbacks(holdOkTimer);
        if (holdOkDown >= 0) holdOkFired = true;
        holdOkDown = -1;
        holdOkPosition = -1;
        holdOkKey = null;
    }

    private void fireHoldOk() {
        handler.removeCallbacks(holdOkTimer);
        if (holdOkDown < 0 || holdOkFired) return;
        holdOkFired = true;
        if (openPopup != null || reordering) return;   // View's own long click got there first
        int position = holdOkPosition;
        if (position < 0 || position >= adapter.appCount() || position != focusedTilePosition()) return;
        // Same slot is not enough: tiles may have been rebuilt during the hold, so the app must match too.
        if (!holdOkKey.equals(adapter.keyAt(position))) return;
        View card = getCurrentFocus();
        if (card != null) card.setPressed(false);
        onTileLongPress(position, card);
    }

    @Override
    public void onFolderClicked(AppAdapter.Entry folder) {
        if (folder == null || folder.folderId == null) return;
        if (openFolder != null) openFolder.close();
        if (mediaMiniPlayer != null) mediaMiniPlayer.close();
        if (openPopup != null) openPopup.dismiss();
        OverlayActivity.launchFolder(this, folder, new FolderDialog.Callbacks() {
            @Override
            public void onLaunch(AppAdapter.Entry app) {
                adapter.launch(app);
            }

            @Override
            public void onRemoveApp(String folderId, String pkg) {
                if (!Folders.removePackage(MainActivity.this, folderId, pkg)) return;
                reloadTiles();
                AppAdapter.Entry updated = adapter.folderById(folderId);
                if (updated != null && !updated.folderMembers.isEmpty()) {
                    OverlayActivity.updateFolder(updated);
                } else {
                    for (int i = 0; i < adapter.appCount(); i++) {
                        if (pkg.equals(adapter.packageAt(i))) {
                            lastFocusedKey = adapter.keyAt(i);
                            lastFocused = i;
                            break;
                        }
                    }
                    OverlayActivity.closeFolderIfOpen();
                }
            }

            @Override
            public void onMoveAppToFolder(String fromFolderId, String toFolderId, String pkg) {
                if (!Folders.addPackage(MainActivity.this, toFolderId, pkg)) return;
                reloadTiles();
                AppAdapter.Entry updated = adapter.folderById(fromFolderId);
                if (updated != null && !updated.folderMembers.isEmpty()) {
                    OverlayActivity.updateFolder(updated);
                } else {
                    lastFocusedKey = Folders.keyFor(MainActivity.this, toFolderId);
                    OverlayActivity.closeFolderIfOpen();
                }
            }

            @Override
            public void onReorderApps(String folderId, List<String> orderedPackages) {
                if (Folders.reorderPackages(MainActivity.this, folderId, orderedPackages)) {
                    reloadTiles();
                } else {
                    Toast.makeText(MainActivity.this, R.string.reorder_not_saved, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onAddApps(String folderId, String folderName) {
                holdBackdropBlurForHandoff();
                OverlayActivity.finishForHandoff();
                root.post(() -> {
                    if (!isFinishing() && !isDestroyed()) showAddAppsToFolderDialog(folderId, folderName, true);
                });
            }

            @Override
            public void onRename(String folderId, String currentName) {
                holdBackdropBlurForHandoff();
                OverlayActivity.finishForHandoff();
                root.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        showFolderNameDialog(getString(R.string.folder_rename_title), currentName,
                                getString(R.string.folder_save), name -> renameFolder(folderId, name, true), folderId);
                    }
                });
            }

            @Override
            public void onDismissed() {
                updateBackdropBlur();
                if (resumed && folderPrompt == null) restoreFocus();
            }
        });
    }

    @Override
    public void onTileLongPress(int position, View card) {
        if (reordering || openPopup != null || openFolder != null) return;
        String key = adapter.keyAt(position);
        String pkg = adapter.packageAt(position);
        AppAdapter.Entry folder = adapter.folderAt(position);
        // Empty dock slots and the Default-home tile carry the launcher's own package: no menu for them.
        if (folder == null && (key == null || pkg == null || pkg.equals(getPackageName()))) return;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (220 * density));
        if (folder != null) {
            addSettingsMenuRow(rows, popup, R.string.folder_open, () -> {
                holdBackdropBlurForHandoff();
                onFolderClicked(folder);
            });
            addSettingsMenuRow(rows, popup, R.string.folder_add_apps, () -> {
                holdBackdropBlurForHandoff();
                showAddAppsToFolderDialog(folder.folderId, folder.label, false);
            });
            addSettingsMenuRow(rows, popup, R.string.folder_rename, () -> {
                holdBackdropBlurForHandoff();
                showFolderNameDialog(getString(R.string.folder_rename_title), folder.label,
                        getString(R.string.folder_save), name -> renameFolder(folder.folderId, name, false), null);
            });
            if (dockRow && position < adapter.movableCount()) {
                boolean inDock = position < COLUMNS;
                addSettingsMenuRow(rows, popup, inDock ? R.string.tile_replace_in_dock : R.string.tile_add_to_dock,
                        () -> startDockEditFor(key, inDock));
            }
            if (position < adapter.movableCount()) addSettingsMenuRow(rows, popup, R.string.tile_move, () -> {
                if (!beginReorder()) return;
                int at = adapter.positionOf(key);
                if (at < 0) return;
                focusTile(at);
                pickUp(at);
                Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
            });
            addSettingsMenuRow(rows, popup, R.string.folder_dissolve, () -> dissolveFolder(folder));
        } else {
            if (dockRow && position < adapter.movableCount()) {
                boolean inDock = position < COLUMNS;
                addSettingsMenuRow(rows, popup, inDock ? R.string.tile_replace_in_dock : R.string.tile_add_to_dock,
                        () -> startDockEditFor(key, inDock));
            }
            if (position < adapter.movableCount()) addSettingsMenuRow(rows, popup, R.string.tile_move, () -> {
                if (!beginReorder()) return;
                int at = adapter.positionOf(key);   // beginReorder can rebuild the tiles
                if (at < 0) return;
                focusTile(at);
                pickUp(at);
                Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
            });
            addSettingsMenuRow(rows, popup, R.string.folder_add_to, () -> {
                holdBackdropBlurForHandoff();
                root.post(() -> showFolderPickerForApp(pkg, key, card));
            });
            if (appLibraryEnabled) addSettingsMenuRow(rows, popup, R.string.library_remove_shortcut, () -> {
                if (LibraryApps.save(this, pkg, false)) reloadApps(false);
                else Toast.makeText(this, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            });
            // Some firmware has no App info page at all; a row that only toasts "can't open" helps nobody.
            Intent info = SystemSettings.appInfo(this, pkg);
            if (info != null) addSettingsMenuRow(rows, popup, R.string.library_app_info, () -> open(info));
        }
        showTileAnchoredPopup(popup, panel, card);
    }

    private void showTileAnchoredPopup(PopupWindow popup, View panel, View anchor) {
        int pad = FocusRingController.wrapInsetPx(density);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int panelW = Math.max((int) (220 * density), panel.getMeasuredWidth());
        popup.setWidth(panelW + 2 * pad);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int gap = (int) (10 * density), height = panel.getMeasuredHeight();
        int x = Math.max((int) (16 * density), Math.min(at[0], root.getWidth() - panelW - (int) (16 * density)));
        boolean below = at[1] + anchor.getHeight() + gap + height <= root.getHeight();
        int y = below ? at[1] + anchor.getHeight() + gap : Math.max(0, at[1] - gap - height);
        float pivotY = below ? pad : (height + pad);
        boolean reduce = reducedMotion || !animations;
        captureLiveHomeBackdropThen(() -> {
            if (openPopup != popup || isFinishing() || isDestroyed()) return;
            prepareBackdropBlurForPopup();
            View animTarget = popup.getContentView() != null ? popup.getContentView() : panel;
            animTarget.setAlpha(0f);
            if (!reduce) {
                animTarget.setPivotY(pivotY);
                animTarget.setScaleX(0.94f);
                animTarget.setScaleY(0.90f);
                animTarget.setTranslationY((below ? -44f : 44f) * density);
            }
            if (animTarget.getTag() instanceof Runnable[]) {
                ((Runnable[]) animTarget.getTag())[0] = () -> Motion.dismissDropdown(
                        animTarget, density, !below, pivotY, reduce, popup::dismiss);
            }
            popup.showAtLocation(root, Gravity.NO_GRAVITY, x - pad, y - pad);
            focusFirstRow(panel);
            animTarget.post(() -> animTarget.postOnAnimation(() -> {
                if (openPopup != popup || !popup.isShowing() || isFinishing() || isDestroyed()) return;
                startBackdropBlurFadeIn();
                Motion.revealDropdown(animTarget, density, !below, pivotY, reduce);
            }));
        });
    }

    /** Submenu from a tile's Hold-OK menu: choose an existing folder or create a new one for this app. */
    private void showFolderPickerForApp(String pkg, String appKey, View anchor) {
        if (isFinishing() || isDestroyed()) return;
        List<Folders.Folder> existing = Folders.load(this);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (230 * density));
        for (Folders.Folder f : existing) {
            addSettingsMenuRow(rows, popup, f.name, () -> {
                if (Folders.addPackage(this, f.id, pkg)) {
                    lastFocusedKey = Folders.keyFor(this, f.id);
                    reloadTiles();
                    restoreFocus();
                }
            });
        }
        addSettingsMenuRow(rows, popup, getString(R.string.folder_new), () -> {
            holdBackdropBlurForHandoff();
            String defName = getString(R.string.folder_default_name, existing.size() + 1);
            showFolderNameDialog(getString(R.string.folder_new_title), defName, getString(R.string.folder_create),
                    name -> createFolderForApp(name, pkg, appKey), null);
        });
        showTileAnchoredPopup(popup, panel, anchor);
    }

    private void createFolderForApp(String name, String pkg, String appKey) {
        Folders.Folder created = Folders.create(this, name, pkg);
        if (created == null) return;
        String folderKey = Folders.keyFor(this, created.id);
        replaceOrderKey(appKey, folderKey);
        lastFocusedKey = folderKey;
        reloadTiles();
        restoreFocus();
    }

    private void renameFolder(String folderId, String newName, boolean reopen) {
        if (!Folders.rename(this, folderId, newName)) return;
        reloadTiles();
        lastFocusedKey = Folders.keyFor(this, folderId);
        if (reopen) {
            root.post(() -> {
                AppAdapter.Entry updated = adapter.folderById(folderId);
                if (updated != null) onFolderClicked(updated);
                else restoreFocus();
            });
        } else {
            restoreFocus();
        }
    }

    private void dissolveFolder(AppAdapter.Entry folder) {
        if (folder == null || folder.folderId == null) return;
        List<String> memberKeys = new ArrayList<>();
        for (AppAdapter.Entry m : folder.folderMembers) {
            memberKeys.add(m.component.flattenToShortString());
        }
        String folderKey = folder.component.flattenToShortString();
        if (!Folders.dissolve(this, folder.folderId)) return;
        expandOrderKey(folderKey, memberKeys);
        if (!memberKeys.isEmpty()) lastFocusedKey = memberKeys.get(0);
        reloadTiles();
        restoreFocus();
    }

    /** Preserves the app's exact Home slot when turning it into a newly created folder. */
    private void replaceOrderKey(String oldKey, String newKey) {
        if (oldKey == null || newKey == null) return;
        Order saved = Order.load(Order.file(this));
        if (saved == null || !saved.dock.equals(Order.dockKey(dockApps))) return;
        List<String> keys = new ArrayList<>(saved.keys.size());
        boolean replaced = false;
        for (String k : saved.keys) {
            if (oldKey.equals(k) && !keys.contains(newKey)) {
                keys.add(newKey);
                replaced = true;
            } else if (!newKey.equals(k)) {
                keys.add(k);
            }
        }
        if (!replaced) return;
        Order next = new Order(saved.dock, keys, Math.min(saved.docked, keys.size()));
        if (Order.save(Order.file(this), next)) {
            adapter.setSavedOrder(Order.ranks(next, dockApps), Order.docked(next, dockApps));
        }
    }

    /** Expands a dissolved folder's slot in order.txt into its member apps so they stay nearby. */
    private void expandOrderKey(String folderKey, List<String> memberKeys) {
        if (folderKey == null || memberKeys.isEmpty()) return;
        Order saved = Order.load(Order.file(this));
        if (saved == null || !saved.dock.equals(Order.dockKey(dockApps))) return;
        List<String> keys = new ArrayList<>(saved.keys.size() + memberKeys.size());
        boolean expanded = false;
        for (String k : saved.keys) {
            if (folderKey.equals(k)) {
                for (String mk : memberKeys) if (!keys.contains(mk)) keys.add(mk);
                expanded = true;
            } else if (!memberKeys.contains(k)) {
                keys.add(k);
            }
        }
        if (!expanded) return;
        Order next = new Order(saved.dock, keys, saved.docked < 0 ? -1 : Math.min(saved.docked, keys.size()));
        if (Order.save(Order.file(this), next)) {
            adapter.setSavedOrder(Order.ranks(next, dockApps), Order.docked(next, dockApps));
        }
    }

    /** TV-friendly frosted dialog for creating or renaming a folder, with quick preset chips and an EditText. */
    private void showFolderNameDialog(String titleText, String initialName, String confirmLabel,
                                      java.util.function.Consumer<String> onConfirm, String reopenFolderIdOnCancel) {
        if (folderPrompt != null) folderPrompt.dismiss();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        ExpressiveGlass.applyPanelSurface(panel, density, 26f, false, false);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        int pH = Math.round(22 * density), pV = Math.round(20 * density);
        panel.setPadding(pH, pV, pH, Math.round(16 * density));

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setTypeface(UiFont.get(this, R.string.font_medium));
        ExpressiveGlass.applyLuminousTitle(title, density, false);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        EditText input = new EditText(this);
        input.setText(initialName);
        input.setSelection(initialName != null ? initialName.length() : 0);
        input.setHint(R.string.folder_name_hint);
        input.setHintTextColor(0x80FFFFFF);
        input.setTextColor(Color.WHITE);
        input.setTextSize(17f);
        input.setTypeface(UiFont.get(this, R.string.font_medium));
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Folders.MAX_NAME_LENGTH)});
        int inPadH = Math.round(16 * density), inPadV = Math.round(12 * density);
        input.setPadding(inPadH, inPadV, inPadH, inPadV);
        input.setBackground(ExpressiveGlass.makeAdaptiveInputBg(density, 14f));
        FocusRingController.configure(input, 1f, 0f, 14f, 0);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, -2);
        inputLp.topMargin = Math.round(14 * density);
        panel.addView(input, inputLp);

        // Two rows of 3 one-click preset chips so users rarely need the TV on-screen keyboard.
        int[] presetIds = {
                R.string.folder_preset_streaming, R.string.folder_preset_tv, R.string.folder_preset_sport,
                R.string.folder_preset_music, R.string.folder_preset_games, R.string.folder_preset_tools
        };
        LinearLayout chipsBox = new LinearLayout(this);
        chipsBox.setOrientation(LinearLayout.VERTICAL);
        chipsBox.setPadding(Math.round(4 * density), Math.round(5 * density), Math.round(4 * density), Math.round(5 * density));
        LinearLayout.LayoutParams chipsBoxLp = new LinearLayout.LayoutParams(-1, -2);
        chipsBoxLp.topMargin = Math.round(10 * density);
        panel.addView(chipsBox, chipsBoxLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        buttons.setPadding(Math.round(6 * density), Math.round(6 * density), Math.round(6 * density), Math.round(4 * density));
        LinearLayout.LayoutParams btnBarLp = new LinearLayout.LayoutParams(-1, -2);
        btnBarLp.topMargin = Math.round(12 * density);
        panel.addView(buttons, btnBarLp);

        TextView btnCancel = makeDialogPill(getString(R.string.folder_cancel));
        TextView btnConfirm = makeDialogPill(confirmLabel);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(-2, Math.round(42 * density));
        cancelLp.rightMargin = Math.round(10 * density);
        buttons.addView(btnCancel, cancelLp);
        buttons.addView(btnConfirm, new LinearLayout.LayoutParams(-2, Math.round(42 * density)));

        chipsBox.setClipChildren(false);
        chipsBox.setClipToPadding(false);
        for (int row = 0; row < 2; row++) {
            LinearLayout chipRow = new LinearLayout(this);
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            chipRow.setClipChildren(false);
            chipRow.setClipToPadding(false);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            if (row > 0) rowLp.topMargin = Math.round(8 * density);
            for (int col = 0; col < 3; col++) {
                String preset = getString(presetIds[row * 3 + col]);
                TextView chip = makeDialogPill(preset);
                chip.setTextSize(13f);
                chip.setSingleLine(true);
                chip.setEllipsize(TextUtils.TruncateAt.END);
                chip.setOnClickListener(v -> {
                    input.setText(preset);
                    input.setSelection(preset.length());
                    btnConfirm.requestFocus();
                });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, Math.round(36 * density), 1f);
                if (col > 0) clp.leftMargin = Math.round(8 * density);
                chipRow.addView(chip, clp);
            }
            chipsBox.addView(chipRow, rowLp);
        }
        ExpressiveGlass.applyAdaptiveContentTheme(panel);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final boolean reduce = reducedMotion || !animations;
        final boolean keepBlurOnClose = reopenFolderIdOnCancel != null;
        int dialogPad = FocusRingController.wrapInsetPx(density);
        FrameLayout ringHost = FocusRingController.wrapPopup(panel, density, animations, reducedMotion);
        final boolean[] confirmed = {false};
        final boolean[] dismissing = {false};
        final Runnable[] dismissRunner = new Runnable[1];
        FrameLayout keyHost = new FrameLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled() && dismissRunner[0] != null) {
                        dismissRunner[0].run();
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        keyHost.setClipChildren(false);
        keyHost.setClipToPadding(false);
        keyHost.addView(ringHost, new FrameLayout.LayoutParams(-1, -2));
        PointerSupport.install(keyHost);
        UiClickSound.attachFocusSound(keyHost);
        dialog.setContentView(keyHost);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            ExpressiveGlass.clearDialogShadow(w);
            w.setLayout((int) (490 * density) + 2 * dialogPad, ViewGroup.LayoutParams.WRAP_CONTENT);
            ExpressiveGlass.setWindowDimOnly(w, 0f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            w.setWindowAnimations(0);
        }

        java.util.function.Consumer<Runnable> animateClose = afterDismiss -> {
            if (dismissing[0]) return;
            dismissing[0] = true;
            if (folderPrompt == dialog) folderPrompt = null;
            if (keepBlurOnClose) holdBackdropBlurForHandoff();
            else if (!hasBackdropModal()) setBackdropBlur(false);
            if (!dialog.isShowing()) {
                if (afterDismiss != null) afterDismiss.run();
                return;
            }
            keyHost.setPivotX(keyHost.getWidth() / 2f);
            keyHost.setPivotY(keyHost.getHeight() / 2f);
            Motion.dismissModal(keyHost, density, reduce, () -> {
                if (dialog.isShowing()) dialog.dismiss();
                if (afterDismiss != null) afterDismiss.run();
            });
        };
        dismissRunner[0] = () -> animateClose.accept(null);

        Runnable submit = () -> {
            if (dismissing[0]) return;
            confirmed[0] = true;
            String cleaned = Folders.cleanName(String.valueOf(input.getText()));
            final String finalName = cleaned.isEmpty() ? initialName : cleaned;
            animateClose.accept(() -> onConfirm.accept(finalName));
        };
        btnConfirm.setOnClickListener(v -> submit.run());
        btnCancel.setOnClickListener(v -> dismissRunner[0].run());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                submit.run();
                return true;
            }
            return false;
        });
        dialog.setOnDismissListener(d -> {
            Motion.cancelPanelAnim(keyHost);
            if (folderPrompt == dialog) folderPrompt = null;
            updateBackdropBlur();
            if (!confirmed[0] && reopenFolderIdOnCancel != null) {
                root.post(() -> {
                    AppAdapter.Entry f = adapter.folderById(reopenFolderIdOnCancel);
                    if (f != null) onFolderClicked(f);
                    else restoreFocus();
                });
            } else if (!confirmed[0] && openFolder == null) {
                restoreFocus();
            }
        });
        folderPrompt = dialog;
        overlayOpen = false;
        captureLiveHomeBackdropThen(() -> {
            if (folderPrompt != dialog || isFinishing() || isDestroyed()) return;
            prepareBackdropBlurForPopup();
            keyHost.setAlpha(0f);
            if (!reduce) {
                keyHost.setScaleX(0.94f);
                keyHost.setScaleY(0.94f);
                keyHost.setTranslationY(84f * density);
            }
            dialog.show();
            btnConfirm.requestFocus();
            keyHost.post(() -> keyHost.postOnAnimation(() -> {
                if (folderPrompt != dialog || !dialog.isShowing() || dismissing[0] || isFinishing() || isDestroyed()) return;
                keyHost.setPivotX(keyHost.getWidth() / 2f);
                keyHost.setPivotY(keyHost.getHeight() / 2f);
                startBackdropBlurFadeIn();
                Motion.revealModal(keyHost, density, reduce);
            }));
        });
    }

    /** Multi-select frosted picker allowing the user to add one or more Home apps into a folder in one go. */
    private void showAddAppsToFolderDialog(String folderId, String folderName, boolean updateOpen) {
        List<AppAdapter.Entry> candidates = adapter.topLevelAppEntries();
        if (candidates.isEmpty()) {
            overlayOpen = false;
            Toast.makeText(this, R.string.folder_add_apps_empty, Toast.LENGTH_SHORT).show();
            if (updateOpen) {
                root.post(() -> {
                    AppAdapter.Entry updated = adapter.folderById(folderId);
                    if (updated != null) onFolderClicked(updated);
                    else updateBackdropBlur();
                });
            } else {
                updateBackdropBlur();
            }
            return;
        }
        Collections.sort(candidates, (a, b) -> a.label.compareToIgnoreCase(b.label));
        if (folderPrompt != null) folderPrompt.dismiss();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        ExpressiveGlass.applyPanelSurface(panel, density, 26f, false, false);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        int pH = Math.round(16 * density), pV = Math.round(18 * density);
        panel.setPadding(pH, pV, pH, pV);

        TextView title = new TextView(this);
        title.setText(getString(R.string.folder_add_apps_title, folderName));
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTypeface(UiFont.get(this, R.string.font_medium));
        title.setPadding(Math.round(12 * density), 0, Math.round(12 * density), Math.round(10 * density));
        ExpressiveGlass.applyLuminousTitle(title, density, false);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Math.round(24 * density));
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        rows.setPadding(Math.round(6 * density), Math.round(6 * density), Math.round(8 * density), Math.round(6 * density));
        FocusRingController.clipScrollContainers(scroll);
        scroll.addView(rows, new FrameLayout.LayoutParams(-1, -2));
        int maxScrollH = Math.round(330 * density);
        int rowH = Math.round(42 * density);
        int scrollH = candidates.size() * rowH > maxScrollH ? maxScrollH : ViewGroup.LayoutParams.WRAP_CONTENT;
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, scrollH));

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final boolean reduce = reducedMotion || !animations;
        int dialogPad = FocusRingController.wrapInsetPx(density);
        FrameLayout ringHost = FocusRingController.wrapPopup(panel, density, animations, reducedMotion);
        final boolean[] dismissing = {false};
        final Runnable[] dismissRunner = new Runnable[1];
        FrameLayout keyHost = new FrameLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled() && dismissRunner[0] != null) {
                        dismissRunner[0].run();
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        keyHost.setClipChildren(false);
        keyHost.setClipToPadding(false);
        keyHost.addView(ringHost, new FrameLayout.LayoutParams(-1, -2));
        PointerSupport.install(keyHost);
        UiClickSound.attachFocusSound(keyHost);
        dialog.setContentView(keyHost);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            ExpressiveGlass.clearDialogShadow(w);
            w.setLayout((int) (430 * density) + 2 * dialogPad, ViewGroup.LayoutParams.WRAP_CONTENT);
            ExpressiveGlass.setWindowDimOnly(w, 0f);
            w.setWindowAnimations(0);
        }
        dismissRunner[0] = () -> {
            if (dismissing[0]) return;
            dismissing[0] = true;
            if (folderPrompt == dialog) folderPrompt = null;
            if (updateOpen) holdBackdropBlurForHandoff();
            else if (!hasBackdropModal()) setBackdropBlur(false);
            if (!dialog.isShowing()) return;
            keyHost.setPivotX(keyHost.getWidth() / 2f);
            keyHost.setPivotY(keyHost.getHeight() / 2f);
            Motion.dismissModal(keyHost, density, reduce, () -> {
                if (dialog.isShowing()) dialog.dismiss();
            });
        };

        final boolean[] folderModified = {false};
        for (AppAdapter.Entry app : candidates) {
            View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
            row.setBackground(ExpressiveGlass.makeAdaptiveRowBg(density, 16f, false));
            ImageView icon = row.findViewById(R.id.icon);
            ExpressiveGlass.markPreserveImage(icon);
            Drawable art = app.banner != null ? app.banner : app.icon;
            if (art != null) {
                ViewGroup.LayoutParams ilp = icon.getLayoutParams();
                ilp.width = Math.round((app.banner != null ? 40 : 24) * density);
                ilp.height = Math.round(24 * density);
                icon.setLayoutParams(ilp);
                icon.setImageDrawable(art);
                icon.setImageTintList(null);
                icon.setScaleType(app.banner != null ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
                icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override public void getOutline(View view, android.graphics.Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 5 * density);
                    }
                });
                icon.setClipToOutline(true);
                icon.setVisibility(View.VISIBLE);
            } else {
                icon.setVisibility(View.GONE);
            }
            row.findViewById(R.id.dot).setVisibility(View.GONE);
            row.findViewById(R.id.status).setVisibility(View.GONE);
            ((TextView) row.findViewById(R.id.title)).setText(app.label);
            row.setOnClickListener(v -> {
                if (!row.isEnabled() || !Folders.addPackage(this, folderId, app.pkg)) return;
                row.setEnabled(false);
                folderModified[0] = true;
                Runnable removeNow = () -> {
                    int idx = rows.indexOfChild(row);
                    if (idx < 0) return;
                    rows.removeView(row);
                    if (rows.getChildCount() == 0) {
                        dismissRunner[0].run();
                    } else {
                        int nextH = rows.getChildCount() * rowH > maxScrollH ? maxScrollH : ViewGroup.LayoutParams.WRAP_CONTENT;
                        if (scroll.getLayoutParams().height != nextH) {
                            scroll.getLayoutParams().height = nextH;
                            scroll.requestLayout();
                        }
                        panel.post(() -> ExpressiveGlass.invalidateLiquidPanels(panel));
                        rows.getChildAt(Math.min(idx, rows.getChildCount() - 1)).requestFocus();
                    }
                };
                if (animations && !reducedMotion) {
                    row.animate().cancel();
                    row.animate().setStartDelay(0L).alpha(0f).translationX(26f * density).setDuration(135L)
                            .setInterpolator(Motion.easeInExit()).withEndAction(removeNow).start();
                } else {
                    removeNow.run();
                }
            });
            Motion.attachRowFocus(row, density, reduce);
            ExpressiveGlass.applyAdaptiveContentTheme(row);
            rows.addView(row);
        }
        ExpressiveGlass.applyAdaptiveContentTheme(panel);

        dialog.setOnDismissListener(d -> {
            Motion.cancelPanelAnim(keyHost);
            if (folderPrompt == dialog) folderPrompt = null;
            if (folderModified[0]) reloadTiles();
            updateBackdropBlur();
            lastFocusedKey = Folders.keyFor(this, folderId);
            if (updateOpen) {
                root.post(() -> {
                    AppAdapter.Entry updated = adapter.folderById(folderId);
                    if (updated != null) onFolderClicked(updated);
                    else restoreFocus();
                });
            } else if (openFolder == null) {
                restoreFocus();
            }
        });
        folderPrompt = dialog;
        overlayOpen = false;
        captureLiveHomeBackdropThen(() -> {
            if (folderPrompt != dialog || isFinishing() || isDestroyed()) return;
            prepareBackdropBlurForPopup();
            keyHost.setAlpha(0f);
            if (!reduce) {
                keyHost.setScaleX(0.94f);
                keyHost.setScaleY(0.94f);
                keyHost.setTranslationY(84f * density);
            }
            dialog.show();
            if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
            keyHost.post(() -> keyHost.postOnAnimation(() -> {
                if (folderPrompt != dialog || !dialog.isShowing() || dismissing[0] || isFinishing() || isDestroyed()) return;
                keyHost.setPivotX(keyHost.getWidth() / 2f);
                keyHost.setPivotY(keyHost.getHeight() / 2f);
                startBackdropBlurFadeIn();
                Motion.revealModal(keyHost, density, reduce);
            }));
        });
    }

    private TextView makeDialogPill(String text) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(15f);
        btn.setTypeface(UiFont.get(this, R.string.font_medium));
        btn.setGravity(Gravity.CENTER);
        int pad = Math.round(16 * density);
        btn.setPadding(pad, 0, pad, 0);
        btn.setBackground(ExpressiveGlass.makeAdaptiveTileBg(density, 18f, 0));
        btn.setTextColor(ExpressiveGlass.adaptivePillTextColors());
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setClickable(true);
        Motion.attachPillFocus(btn, 1.06f, 1.5f, density, reducedMotion || !animations);
        return btn;
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        addSettingsMenuRow(rows, popup, getString(title), action);
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, CharSequence title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.setBackground(ExpressiveGlass.makeAdaptiveRowBg(density, 16f, false));
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        Motion.attachRowFocus(row, density, reducedMotion || !animations);
        ExpressiveGlass.applyAdaptiveContentTheme(row);
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()
                || reordering) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ExpressiveGlass.applyPanelSurface(panel, density, 26f, false, false);
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        if (rtl) panel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);
        keep.setBackground(ExpressiveGlass.makeAdaptiveTileBg(density, 18f, 0));
        show.setBackground(ExpressiveGlass.makeAdaptiveTileBg(density, 18f, 0));
        ExpressiveGlass.applyAdaptiveContentTheme(panel);
        if (rtl) {
            LinearLayout buttons = (LinearLayout) keep.getParent();
            buttons.removeAllViews();
            buttons.addView(show);
            buttons.addView(keep);
            keep.setNextFocusLeftId(R.id.btnShow);
            keep.setNextFocusRightId(R.id.btnKeep);
            show.setNextFocusLeftId(R.id.btnShow);
            show.setNextFocusRightId(R.id.btnKeep);
        }

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final boolean reduce = reducedMotion || !animations;
        int dialogPad = FocusRingController.wrapInsetPx(density);
        FrameLayout ringHost = FocusRingController.wrapPopup(panel, density, animations, reducedMotion);
        final boolean[] decided = {false};
        final Runnable[] cancelRunner = new Runnable[1];
        FrameLayout keyHost = new FrameLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled() && cancelRunner[0] != null) {
                        cancelRunner[0].run();
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        keyHost.setClipChildren(false);
        keyHost.setClipToPadding(false);
        keyHost.addView(ringHost, new FrameLayout.LayoutParams(-1, -2));
        PointerSupport.install(keyHost);
        UiClickSound.attachFocusSound(keyHost);
        dialog.setContentView(keyHost);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            ExpressiveGlass.clearDialogShadow(w);
            w.setLayout((int) (440 * density) + 2 * dialogPad, ViewGroup.LayoutParams.WRAP_CONTENT);
            ExpressiveGlass.setWindowDimOnly(w, 0f);
            w.setWindowAnimations(0);
        }
        Motion.attachPillFocus(keep, 1.06f, 1.5f, density, reduce);
        Motion.attachPillFocus(show, 1.06f, 1.5f, density, reduce);
        // One decision per prompt: the buttons and Back (cancel) all end here.
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            final boolean showCard = v.getId() == R.id.btnShow;
            if (cardPrompt == dialog) cardPrompt = null;
            if (!hasBackdropModal()) setBackdropBlur(false);
            Runnable finishChoice = () -> {
                if (dialog.isShowing()) dialog.dismiss();
                decideCard(card, showCard);   // guards its own card calls; the tile reload stays outside
                restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
            };
            if (!dialog.isShowing()) {
                finishChoice.run();
                return;
            }
            keyHost.setPivotX(keyHost.getWidth() / 2f);
            keyHost.setPivotY(keyHost.getHeight() / 2f);
            Motion.dismissModal(keyHost, density, reduce, finishChoice);
        };
        cancelRunner[0] = () -> choose.onClick(keep);
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            Motion.cancelPanelAnim(keyHost);
            if (cardPrompt == dialog) cardPrompt = null;
            updateBackdropBlur();
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
                return v.getId() == (rtl ? R.id.btnShow : R.id.btnKeep);
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    && v.getId() == (rtl ? R.id.btnKeep : R.id.btnShow);
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        captureLiveHomeBackdropThen(() -> {
            if (cardPrompt != dialog || isFinishing() || isDestroyed()) return;
            prepareBackdropBlurForPopup();
            keyHost.setAlpha(0f);
            if (!reduce) {
                keyHost.setScaleX(0.94f);
                keyHost.setScaleY(0.94f);
                keyHost.setTranslationY(84f * density);
            }
            dialog.show();
            keep.requestFocus();
            keyHost.post(() -> keyHost.postOnAnimation(() -> {
                if (cardPrompt != dialog || !dialog.isShowing() || decided[0] || isFinishing() || isDestroyed()) return;
                keyHost.setPivotX(keyHost.getWidth() / 2f);
                keyHost.setPivotY(keyHost.getHeight() / 2f);
                startBackdropBlurFadeIn();
                Motion.revealModal(keyHost, density, reduce);
            }));
        });
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        if (getResources().getBoolean(R.bool.rtl_menu_bar))
            panel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        FocusRingController.clipScrollContainers(panel);
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
                LinearLayout heading = (LinearLayout) row.findViewById(R.id.title).getParent();
                View status = row.findViewById(R.id.state);
                heading.removeView(status);
                heading.addView(status, 0);
            }
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            Motion.attachRowFocus(row, density, reducedMotion || !animations);
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        int pad = FocusRingController.wrapInsetPx(density);
        int panelH = Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density)));
        ViewGroup.LayoutParams panelLp = panel.getLayoutParams();
        if (panelLp != null) {
            panelLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            panel.setLayoutParams(panelLp);
        }
        popup.setHeight(panelH + 2 * pad);
        // Hebrew places Settings on the left: align the outer panel inward, like its parent menu.
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
