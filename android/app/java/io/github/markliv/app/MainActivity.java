package io.github.markliv.app;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {

    private static final String TAG = "markliv";
    private static final int REQ_MIC = 4711;
    private static final int REQ_PICK_MODEL = 4712;
    private static final int COPY_CHUNK = 1 << 20;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService brain = Executors.newSingleThreadExecutor();
    private final ExecutorService cloud = Executors.newSingleThreadExecutor();
    private volatile boolean cloudStop = false;

    private TextToSpeech tts;
    private SpeechRecognizer asr;
    private boolean micBusy = false;

    private File modelFile;          // models/<name>.ggmf inside filesDir
    private String pendingPickPath = null;
    private volatile boolean micWanted = false;
    private PowerManager.WakeLock wakeLock = null;

    /* the cloud credentials live encrypted in the Android keystore, never in the
       WebView's localStorage and never in the APK */
    private static final String PREF_SECURE = "markliv_secure";
    private static final String KALIAS = "markliv_api_v1";
    private static final String P_IV = "iv";
    private static final String P_CT = "ct";

    /* actions that reach another person or change the device's commitments:
       the UI puts a confirm sheet in front of these unless full auto is on */
    private static final String[] RISKY = {"dial", "sms", "alarm", "timer", "camera"};

    // ------------------------------------------------------------------ setup

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(new View(this));

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFF14161A);
            getWindow().setNavigationBarColor(0xFF0B0C0F);
        }

        web = new WebView(this);
        web.setBackgroundColor(0xFF14161A);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (u != null && (u.getScheme().equals("http") || u.getScheme().equals("https"))) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (url != null && url.contains("pickModelResult")) return;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, "[web] " + m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");
        Llama.setSink(new Llama.Sink() {
            @Override public void onToken(String piece)  { post("window.__mlToken && window.__mlToken(" + js(piece) + ")"); }
            @Override public void onStatus(String status){ post("window.__mlStatus && window.__mlStatus(" + js(status) + ")"); }
        });

        web.loadUrl("file:///android_asset/web/index.html");
        initTts();
    }

    private void initTts() {
        try {
            tts = new TextToSpeech(this, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        tts.setLanguage(Locale.US);
                        tts.setSpeechRate(1.02f);
                        tts.setPitch(0.98f);
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String id) { post("window.__mlSpeak && window.__mlSpeak('start')"); }
                            @Override public void onDone(String id)  { post("window.__mlSpeak && window.__mlSpeak('done')"); }
                            @Override public void onError(String id) { post("window.__mlSpeak && window.__mlSpeak('done')"); }
                        });
                    } catch (Exception ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "tts unavailable: " + t);
        }
    }

    private void post(String js) {
        ui.post(() -> {
            try {
                web.evaluateJavascript(js, null);
            } catch (Throwable ignored) {
            }
        });
    }

    static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private int threads() {
        return Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));
    }

    // ------------------------------------------------------- secure key store
    //
    // One AES-GCM key lives in the Android keystore (non-exportable, hardware
    // backed where the device has a TEE). The provider block — endpoint, model,
    // dialect and the API key — is sealed with it and kept in private prefs, so
    // the key is asked for once, never touches disk in the clear, and cannot be
    // lifted out of the APK or read off the WebView's localStorage.

    private SharedPreferences secure() {
        return getSharedPreferences(PREF_SECURE, Activity.MODE_PRIVATE);
    }

    private SecretKey sealKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(KALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }

    /** seal the setup JSON; throws if the keystore is unavailable */
    private void seal(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, sealKey());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
        secure().edit()
                .putString(P_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(P_CT, Base64.encodeToString(ct, Base64.NO_WRAP))
                .apply();
    }

    /** open the sealed setup JSON, or "" when nothing is stored / it is unreadable */
    private String unseal() {
        try {
            String iv = secure().getString(P_IV, null), ct = secure().getString(P_CT, null);
            if (iv == null || ct == null) return "";
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, sealKey(),
                   new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), "UTF-8");
        } catch (Throwable t) {
            Log.w(TAG, "sealed setup unreadable: " + t);
            return "";
        }
    }

    private org.json.JSONObject storedSetup() {
        try {
            String raw = unseal();
            return raw.isEmpty() ? new org.json.JSONObject() : new org.json.JSONObject(raw);
        } catch (Throwable t) {
            return new org.json.JSONObject();
        }
    }

    private boolean isRisky(String action) {
        if (action == null) return false;
        for (String r : RISKY) if (r.equals(action)) return true;
        return false;
    }

    // ---------------------------------------------------------- phone control

    /** @return a short human sentence describing what happened */
    private String doPhone(String action, org.json.JSONObject a) throws Exception {
        if (action == null || action.trim().isEmpty())
            throw new IllegalArgumentException("no action given");

        switch (action) {

            case "battery": {
                Intent st = registerReceiver(null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int lvl = st == null ? -1 : st.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scl = st == null ? -1 : st.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int pct = (lvl >= 0 && scl > 0) ? Math.round(100f * lvl / scl) : -1;
                int plug = st == null ? 0 : st.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                String tech = st == null ? "" : String.valueOf(st.getIntExtra(BatteryManager.EXTRA_TECHNOLOGY, 0));
                return pct + "% " + (plug > 0 ? "charging" : "on battery")
                        + (tech.equals("2") ? ", USB" : tech.equals("1") ? ", AC" : tech.equals("5") ? ", wireless" : "");
            }

            case "storage": {
                StatFs fs = new StatFs(getFilesDir().getAbsolutePath());
                long free = fs.getAvailableBytes(), total = fs.getTotalBytes();
                return Math.round(free / 1048576.0) + " MB free of " + Math.round(total / 1048576.0) + " MB";
            }

            case "volume": {
                AudioManager am = audio();
                int v = Math.max(0, Math.min(100, a.optInt("level", 50)));
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                return "volume set to " + v + "%";
            }

            case "volume_up": {
                audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0);
                return "volume up";
            }

            case "volume_down": {
                audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0);
                return "volume down";
            }

            case "mute": {
                audio().setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                return "muted";
            }

            case "torch": {
                boolean on = a.optBoolean("on", true);
                CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                if (cm == null) throw new IllegalStateException("no camera service");
                for (String id : cm.getCameraIdList()) {
                    Boolean has = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (Boolean.TRUE.equals(has)) {
                        cm.setTorchMode(id, on);
                        return "torch " + (on ? "on" : "off");
                    }
                }
                throw new IllegalStateException("this phone has no torch");
            }

            case "wifi": {
                boolean on = a.optBoolean("on", true);
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm == null) throw new IllegalStateException("no wifi service");
                if (wm.isWifiEnabled() == on) return "wifi already " + (on ? "on" : "off");
                if (Build.VERSION.SDK_INT >= 29) {
                    /* Android 10+ refuses programmatic toggles — send the user to the panel */
                    startActivity(new Intent(Settings.Panel.ACTION_WIFI));
                    return "opened the Wi-Fi panel — tap to turn Wi-Fi " + (on ? "on" : "off");
                }
                wm.setWifiEnabled(on);
                return "wifi " + (on ? "on" : "off");
            }

            case "bluetooth": {
                boolean on = a.optBoolean("on", true);
                BluetoothAdapter ba = null;
                try {
                    BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
                    if (bm != null) ba = bm.getAdapter();
                } catch (Throwable ignored) { }
                if (ba == null) ba = BluetoothAdapter.getDefaultAdapter();
                if (ba == null) throw new IllegalStateException("no Bluetooth adapter");
                if (ba.isEnabled() == on) return "Bluetooth already " + (on ? "on" : "off");
                /* REQUEST_DISABLE is not public API, so the string is spelled out */
                startActivity(new Intent(on ? BluetoothAdapter.ACTION_REQUEST_ENABLE
                                             : "android.bluetooth.adapter.action.REQUEST_DISABLE"));
                return "Bluetooth " + (on ? "on" : "off") + " — confirm on the system dialog";
            }

            case "open_app": {
                String pkg = a.optString("package", "").trim();
                String name = a.optString("name", "").trim();
                PackageManager pm = getPackageManager();
                Intent launch = pkg.isEmpty() ? null : pm.getLaunchIntentForPackage(pkg);
                if (launch == null && !name.isEmpty()) {
                    String want = name.toLowerCase(Locale.ROOT);
                    List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                    for (ApplicationInfo ai : apps) {
                        String label = String.valueOf(pm.getApplicationLabel(ai)).toLowerCase(Locale.ROOT);
                        if (label.equals(want) || label.contains(want)) {
                            launch = pm.getLaunchIntentForPackage(ai.packageName);
                            if (launch != null) { pkg = ai.packageName; break; }
                        }
                    }
                }
                if (launch == null)
                    throw new IllegalStateException(name.isEmpty()
                            ? "no app with package " + pkg + " is installed"
                            : "no installed app called \"" + name + "\"");
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return "opened " + (name.isEmpty() ? pkg : name);
            }

            case "list_apps": {
                PackageManager pm = getPackageManager();
                StringBuilder b = new StringBuilder();
                int n = 0;
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
                    String label = String.valueOf(pm.getApplicationLabel(ai));
                    if (label.isEmpty() || label.length() > 28) continue;
                    if (b.length() > 0) b.append(", ");
                    b.append(label);
                    if (++n >= 40) break;
                }
                return b.length() == 0 ? "no launchable apps visible" : b.toString();
            }

            case "open_url": {
                String u = a.optString("url", "").trim();
                if (u.isEmpty()) throw new IllegalArgumentException("no url given");
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                return "opened " + u;
            }

            case "share": {
                String text = a.optString("text", "");
                String title = a.optString("title", "MARK LIV");
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                i.putExtra(Intent.EXTRA_TITLE, title);
                startActivity(Intent.createChooser(i, title));
                return "share sheet open";
            }

            case "alarm": {
                int h = Math.max(0, Math.min(23, a.optInt("hour", 9)));
                int m = Math.max(0, Math.min(59, a.optInt("minute", 0)));
                Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
                i.putExtra(AlarmClock.EXTRA_HOUR, h);
                i.putExtra(AlarmClock.EXTRA_MINUTES, m);
                String label = a.optString("label", "MARK LIV");
                if (!label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, a.optBoolean("skip_ui", true));
                if (a.optBoolean("silent", false)) i.putExtra(AlarmClock.EXTRA_VIBRATE, false);
                startActivity(i);
                return "alarm set for " + (h < 10 ? "0" + h : "" + h) + ":" + (m < 10 ? "0" + m : "" + m);
            }

            case "timer": {
                int secs = Math.max(1, a.optInt("seconds", 300));
                Intent i = new Intent(AlarmClock.ACTION_SET_TIMER);
                i.putExtra(AlarmClock.EXTRA_LENGTH, secs);
                i.putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "MARK LIV timer"));
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                startActivity(i);
                return secs + "s timer set";
            }

            case "screen": {
                if (!a.optBoolean("on", true))
                    throw new UnsupportedOperationException(
                            "Android does not let an app turn the screen off — press the power key");
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm == null) throw new IllegalStateException("no power service");
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP, "markliv:wake");
                wakeLock.acquire(20000L);
                return "screen woken";
            }

            case "media": {
                String cmd = a.optString("cmd", "play");
                int code;
                switch (cmd) {
                    case "play":  code = KeyEvent.KEYCODE_MEDIA_PLAY;  break;
                    case "pause": code = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
                    case "next":  code = KeyEvent.KEYCODE_MEDIA_NEXT;  break;
                    case "prev":  code = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
                    case "stop":  code = KeyEvent.KEYCODE_MEDIA_STOP;  break;
                    default: throw new IllegalArgumentException("unknown media command: " + cmd);
                }
                long t = SystemClock.uptimeMillis();
                audio().dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
                audio().dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
                return "media " + cmd;
            }

            case "dial": {
                String num = a.optString("number", "").trim();
                if (num.isEmpty()) throw new IllegalArgumentException("no number given");
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num))));
                return "dialer open for " + num + " — tap call to connect";
            }

            case "sms": {
                String num = a.optString("number", "").trim();
                if (num.isEmpty()) throw new IllegalArgumentException("no number given");
                Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num)));
                String body = a.optString("text", "");
                if (!body.isEmpty()) i.putExtra("sms_body", body);
                startActivity(i);
                return "message ready for " + num + " — review it, then send";
            }

            case "maps": {
                String q = a.optString("query", "").trim();
                if (q.isEmpty()) throw new IllegalArgumentException("no place given");
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=" + Uri.encode(q))));
                return "maps opened for " + q;
            }

            case "camera": {
                Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (i.resolveActivity(getPackageManager()) == null)
                    throw new IllegalStateException("no camera app on this device");
                startActivity(i);
                return "camera open";
            }

            default:
                throw new IllegalArgumentException("unknown action: " + action);
        }
    }

    private AudioManager audio() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) throw new IllegalStateException("no audio service");
        return am;
    }

    // ---------------------------------------------------------- cloud (optional)
    //
    // The local brain above needs no network. This block is the *optional*
    // cloud path: the user supplies an endpoint + key at runtime and we speak
    // whichever dialect that provider uses —
    //   "openai"    : OpenAI-compatible /chat/completions. Covers Qwen
    //                 (DashScope compatible-mode), OpenRouter, Groq, Together,
    //                 LM Studio, Ollama, and Anthropic's new compat endpoint.
    //   "gemini"    : generativelanguage ...:streamGenerateContent
    //   "anthropic" : api.anthropic.com /v1/messages
    // The request is made here in Java rather than with fetch() in the WebView
    // because the page is served from file:///android_asset/ — a "null"
    // origin — so cross-origin XHR is blocked.
    private static final String F_OPENAI    = "openai";
    private static final String F_GEMINI    = "gemini";
    private static final String F_ANTHROPIC = "anthropic";

    /** JSON string literal, surrounding quotes included */
    private static String jquote(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** pull the first "content":"..." string out of a JSON payload; "" if none */
    private static String contentOf(String json) {
        return stringFor(json, "\"content\"");
    }

    /** first "<key>":"<string>" value in the payload, unescaped; "" if absent */
    private static String stringFor(String json, String key) {
        if (json == null) return "";
        int i = json.indexOf(key);
        while (i >= 0) {
            int colon = json.indexOf(':', i + key.length());
            if (colon < 0) return "";
            int j = colon + 1;
            while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
            if (j < json.length() && json.charAt(j) == '"') {
                StringBuilder v = new StringBuilder();
                for (j++; j < json.length(); ) {
                    char ch = json.charAt(j);
                    if (ch == '\\' && j + 1 < json.length()) {
                        char e = json.charAt(++j);
                        if (e == 'u' && j + 4 < json.length()) {
                            try { v.append((char) Integer.parseInt(json.substring(j + 1, j + 5), 16)); j += 4; }
                            catch (Exception ignored) { }
                        } else {
                            switch (e) {
                                case 'n': v.append('\n'); break;
                                case 't': v.append('\t'); break;
                                case 'r': v.append('\r'); break;
                                case 'b': v.append('\b'); break;
                                case 'f': v.append('\f'); break;
                                default:  v.append(e);
                            }
                        }
                        j++;
                    } else if (ch == '"') {
                        return v.toString();
                    } else {
                        v.append(ch); j++;
                    }
                }
                return v.toString();
            }
            i = json.indexOf(key, i + key.length());
        }
        return "";
    }

    /** a fully-formed request: where to POST, what to POST, how to auth, where the text hides */
    private static final class Req {
        String url = "", body = "", deltaKey = "\"content\"";
        final java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
    }

    private static String role(org.json.JSONObject m) {
        String r = m.optString("role", "user");
        return r == null ? "user" : r;
    }

    private static String text(org.json.JSONObject m) {
        return m.optString("content", "");
    }

    /**
     * Translate the OpenAI-style message array the UI speaks into whatever the
     * target provider expects.
     */
    private static Req buildReq(String flavor, String url, String key, String model,
                                String messagesJson, int maxTokens, boolean probe) throws Exception {
        Req r = new Req();
        org.json.JSONArray in = new org.json.JSONArray(messagesJson);
        int cap = Math.max(16, Math.min(4096, maxTokens));
        String f = flavor == null ? F_OPENAI : flavor;

        if (F_GEMINI.equals(f)) {
            String sys = "", u = "";
            org.json.JSONArray contents = new org.json.JSONArray();
            for (int i = 0; i < in.length(); i++) {
                org.json.JSONObject m = in.getJSONObject(i);
                String role = role(m), t = text(m);
                if ("system".equals(role)) { sys = t; continue; }
                org.json.JSONObject c = new org.json.JSONObject();
                c.put("role", "assistant".equals(role) ? "model" : "user");
                c.put("parts", new org.json.JSONArray().put(new org.json.JSONObject().put("text", t)));
                contents.put(c);
            }
            org.json.JSONObject body = new org.json.JSONObject();
            if (!sys.isEmpty())
                body.put("systemInstruction",
                         new org.json.JSONObject().put("parts",
                             new org.json.JSONArray().put(new org.json.JSONObject().put("text", sys))));
            body.put("contents", contents);
            body.put("generationConfig", new org.json.JSONObject()
                     .put("maxOutputTokens", cap).put("temperature", 0.7));
            r.url   = geminiUrl(url, model, !probe);
            r.body  = body.toString();
            r.deltaKey = "\"text\"";
            r.headers.put("x-goog-api-key", key == null ? "" : key.trim());
            return r;
        }

        if (F_ANTHROPIC.equals(f)) {
            String sys = "";
            org.json.JSONArray msgs = new org.json.JSONArray();
            for (int i = 0; i < in.length(); i++) {
                org.json.JSONObject m = in.getJSONObject(i);
                String role = role(m), t = text(m);
                if ("system".equals(role)) { sys = sys.isEmpty() ? t : sys + "\n" + t; continue; }
                if (msgs.length() > 0) {           /* the API wants strict role alternation */
                    org.json.JSONObject last = msgs.getJSONObject(msgs.length() - 1);
                    if (role(last).equals(role)) {
                        last.put("content", text(last) + "\n\n" + t);
                        continue;
                    }
                }
                org.json.JSONObject c = new org.json.JSONObject();
                c.put("role", "assistant".equals(role) ? "assistant" : "user");
                c.put("content", t);
                msgs.put(c);
            }
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("model", model);
            body.put("max_tokens", cap);
            body.put("stream", !probe);
            body.put("temperature", 0.7);
            if (!sys.isEmpty()) body.put("system", sys);
            body.put("messages", msgs);
            r.url   = url.trim();
            r.body  = body.toString();
            r.deltaKey = "\"text\"";
            r.headers.put("x-api-key", key == null ? "" : key.trim());
            r.headers.put("anthropic-version", "2023-06-01");
            return r;
        }

        /* OpenAI-compatible — the common case, and the only one that needs no reshaping */
        org.json.JSONObject body = new org.json.JSONObject();
        body.put("model", model);
        body.put("stream", !probe);
        body.put("max_tokens", cap);
        body.put("temperature", 0.7);
        body.put("messages", in);
        r.url   = url.trim();
        r.body  = body.toString();
        r.deltaKey = "\"content\"";
        r.headers.put("Authorization", "Bearer " + (key == null ? "" : key.trim()));
        return r;
    }

    /**
     * Accepts a full endpoint, one templated with {model}, or just the API
     * base, and always normalises the method — a connection probe must never be
     * sent to the streaming method, or the reply comes back as SSE fragments.
     */
    private static String geminiUrl(String url, String model, boolean stream) {
        String u = url == null ? "" : url.trim();
        String m = model == null ? "" : model.trim();

        String extra = "";
        int q = u.indexOf('?');
        if (q >= 0) {
            extra = u.substring(q + 1);
            u = u.substring(0, q);
            extra = extra.replaceAll("(^|&)alt=[^&]*", "$1")   /* we set alt ourselves */
                         .replaceAll("&+", "&")
                         .replaceAll("^&|&$", "");
        }
        if (u.contains("{model}")) {
            u = u.replace("{model}", m);
            int mm = u.indexOf("/models/");          /* drop any ":method" the template carried */
            if (mm >= 0) {
                int colon = u.indexOf(':', mm);
                if (colon >= 0) u = u.substring(0, colon);
            }
        } else {
            int i = u.indexOf("/models/");
            if (i >= 0) u = u.substring(0, i + "/models/".length()) + m;
            else        u = (u.endsWith("/") ? u : u + "/") + "models/" + m;
        }

        StringBuilder out = new StringBuilder(u).append(stream ? ":streamGenerateContent" : ":generateContent");
        StringBuilder query = new StringBuilder();
        if (stream) query.append("alt=sse");
        if (!extra.isEmpty()) {
            if (query.length() > 0) query.append('&');
            query.append(extra);
        }
        if (query.length() > 0) out.append('?').append(query);
        return out.toString();
    }

    private static String drain(InputStream in) {
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 4096)) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) b.append(buf, 0, n);
        } catch (Exception ignored) { }
        return b.toString();
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return "(empty response)";
        /* providers report failures as {"error":{"message":"..."}} — show that, not raw JSON */
        String msg = stringFor(t, "\"message\"");
        if (msg.isEmpty()) msg = contentOf(t);
        if (!msg.isEmpty()) t = msg;
        return t.length() > 220 ? t.substring(0, 220) + "…" : t;
    }

    /**
     * One chat completion against any supported provider dialect.
     * Streams deltas to window.__mlNetToken and finishes with __mlNetDone /
     * __mlNetError / __mlNetStatus (the last one only for a connection probe).
     */
    private void cloudChat(final String url, final String key, final String model,
                           final String messagesJson, final int maxTokens,
                           final boolean probe, final String flavor) {
        final StringBuilder full = new StringBuilder();
        HttpURLConnection c = null;
        try {
            String u = url == null ? "" : url.trim();
            if (!u.startsWith("https://") && !u.startsWith("http://"))
                throw new IllegalArgumentException("endpoint must start with http:// or https://");
            if (messagesJson == null || !messagesJson.trim().startsWith("["))
                throw new IllegalArgumentException("bad messages payload");

            post("window.__mlNetStatus && window.__mlNetStatus(" + js("contacting " + hostOf(u)) + ")");

            Req req = buildReq(flavor, u, key, model, messagesJson, maxTokens, probe);
            c = (HttpURLConnection) new URL(req.url).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(180000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", probe ? "application/json" : "text/event-stream");
            c.setRequestProperty("Accept-Encoding", "identity");
            for (java.util.Map.Entry<String, String> h : req.headers.entrySet())
                if (h.getValue() != null && !h.getValue().trim().isEmpty())
                    c.setRequestProperty(h.getKey(), h.getValue());

            byte[] payload = req.body.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload);
                os.flush();
            }

            int code = c.getResponseCode();
            if (code / 100 != 2)
                throw new IllegalStateException("HTTP " + code + " — " + brief(drain(c.getErrorStream())));

            String ctype = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
            boolean sse = ctype.contains("text/event-stream");

            if (probe) {
                String all = drain(c.getInputStream());
                String reply = stringFor(all, req.deltaKey);
                post("window.__mlNetStatus && window.__mlNetStatus(" +
                        js("connected — " + (reply.isEmpty() ? "endpoint reachable" : brief(reply))) + ")");
                return;
            }

            if (sse) {
                try (BufferedReader rd = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"), 8192)) {
                    String line;
                    while ((line = rd.readLine()) != null) {
                        if (cloudStop) break;
                        String t = line.trim();
                        if (!t.startsWith("data:")) continue;
                        String data = t.substring(5).trim();
                        if (data.isEmpty()) continue;
                        if ("[DONE]".equals(data)) break;
                        String d = stringFor(data, req.deltaKey);
                        if (!d.isEmpty()) {
                            full.append(d);
                            post("window.__mlNetToken && window.__mlNetToken(" + js(d) + ")");
                        }
                    }
                }
            } else {
                /* provider ignored stream:true — take the whole body at once */
                String all = drain(c.getInputStream());
                String d = stringFor(all, req.deltaKey);
                if (d.isEmpty()) throw new IllegalStateException("no content in reply: " + brief(all));
                full.append(d);
                post("window.__mlNetToken && window.__mlNetToken(" + js(d) + ")");
            }

            post("window.__mlNetDone && window.__mlNetDone(" + js(full.toString()) + ")");

        } catch (Throwable t) {
            Log.e(TAG, "cloudChat", t);
            String m = t.getMessage();
            post("window.__mlNetError && window.__mlNetError(" +
                    js(m == null || m.isEmpty() ? t.getClass().getSimpleName() : m) + ")");
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
        }
    }

    private static String hostOf(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return url; }
    }

    // -------------------------------------------------------------- JS bridge

    private class Bridge {

        @JavascriptInterface
        public String device() {
            long free = 0;
            try {
                StatFsHolder.free(this);
            } catch (Throwable ignored) {
            }
            free = freeSpace();
            return "{\"model\":\"" + esc(Build.MODEL) + "\",\"abi\":\"" +
                    esc(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?") +
                    "\",\"sdk\":" + Build.VERSION.SDK_INT +
                    ",\"threads\":" + threads() +
                    ",\"cpu\":" + Runtime.getRuntime().availableProcessors() +
                    ",\"ramMb\":" + (int) (Runtime.getRuntime().maxMemory() / 1048576) +
                    ",\"freeMb\":" + (free / 1048576) +
                    ",\"loaded\":" + Llama.isLoaded() + "}";
        }

        @JavascriptInterface
        public boolean isModelLoaded() { return Llama.isLoaded(); }

        @JavascriptInterface
        public String modelInfo() { return Llama.info(); }

        /** "bundled" = copy the GGUF out of assets; anything else = absolute path already on disk */
        @JavascriptInterface
        public void loadModel(final String which) {
            brain.execute(() -> {
                try {
                    File target;
                    if ("bundled".equals(which) || which == null || which.isEmpty()) {
                        target = installBundledModel();
                    } else {
                        target = new File(which);
                    }
                    if (target == null || !target.exists()) {
                        post("window.__mlStatus && window.__mlStatus(" + js("model file not found") + ")");
                        post("window.__mlError && window.__mlError(" + js("model file not found") + ")");
                        return;
                    }
                    String info = Llama.loadModel(target.getAbsolutePath(), 2048, threads());
                    post("window.__mlLoaded && window.__mlLoaded(" + js(info) + ")");
                } catch (Throwable t) {
                    Log.e(TAG, "loadModel", t);
                    post("window.__mlError && window.__mlError(" + js(String.valueOf(t.getMessage())) + ")");
                }
            });
        }

        @JavascriptInterface
        public void generate(final String system, final String history,
                             final String prompt, final int maxTokens) {
            brain.execute(() -> {
                String out = Llama.generate(system, history, prompt, maxTokens, threads());
                post("window.__mlDone && window.__mlDone(" + js(out) + ")");
            });
        }

        @JavascriptInterface
        public void stop() { Llama.stop(); cloudStop = true; }

        // ---- optional cloud brain (user-supplied endpoint + key) ----

        @JavascriptInterface
        public void netChat(final String url, final String key, final String model,
                            final String messagesJson, final int maxTokens, final String flavor) {
            cloudStop = false;
            cloud.execute(() -> cloudChat(url, key, model, messagesJson, maxTokens, false, flavor));
        }

        @JavascriptInterface
        public void netTest(final String url, final String key, final String model, final String flavor) {
            cloudStop = false;
            String probe = "[{\"role\":\"user\",\"content\":\"Reply with the single word OK\"}]";
            cloud.execute(() -> cloudChat(url, key, model, probe, 16, true, flavor));
        }

        @JavascriptInterface
        public void netStop() { cloudStop = true; }

        // ---- the one-time key setup, sealed in the Android keystore ----

        /** true once a key has been saved — the UI only shows the gate while this is false */
        @JavascriptInterface
        public boolean hasSetup() {
            return storedSetup().optString("key", "").trim().length() > 0;
        }

        /** the stored provider block minus the key itself */
        @JavascriptInterface
        public String setupInfo() {
            org.json.JSONObject o = storedSetup();
            try {
                boolean has = o.optString("key", "").trim().length() > 0;
                o.remove("key");
                o.put("hasKey", has);
            } catch (Throwable ignored) { }
            return o.toString();
        }

        /** seals {prov,flavor,url,model,key}; false when it is incomplete or the keystore refuses */
        @JavascriptInterface
        public boolean saveSetup(String json) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(json == null ? "{}" : json);
                if (o.optString("key", "").trim().isEmpty()) return false;
                if (o.optString("url", "").trim().isEmpty()) return false;
                if (o.optString("model", "").trim().isEmpty()) return false;
                if (o.optString("flavor", "").trim().isEmpty()) o.put("flavor", F_OPENAI);
                seal(o.toString());
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "saveSetup", t);
                return false;
            }
        }

        @JavascriptInterface
        public void clearSetup() { secure().edit().clear().apply(); }

        // ---- chat using the sealed credentials; the key never enters the WebView ----

        @JavascriptInterface
        public void netChatAuto(final String messagesJson, final int maxTokens) {
            org.json.JSONObject o = storedSetup();
            cloudStop = false;
            cloud.execute(() -> cloudChat(o.optString("url"), o.optString("key"),
                    o.optString("model"), messagesJson, maxTokens, false,
                    o.optString("flavor", F_OPENAI)));
        }

        @JavascriptInterface
        public void netTestAuto() {
            org.json.JSONObject o = storedSetup();
            cloudStop = false;
            String probe = "[{\"role\":\"user\",\"content\":\"Reply with the single word OK\"}]";
            cloud.execute(() -> cloudChat(o.optString("url"), o.optString("key"),
                    o.optString("model"), probe, 16, true,
                    o.optString("flavor", F_OPENAI)));
        }

        // ---- device control ----

        @JavascriptInterface
        public boolean isRisky(String action) { return MainActivity.this.isRisky(action); }

        @JavascriptInterface
        public String riskyActions() {
            StringBuilder b = new StringBuilder();
            for (String r : RISKY) { if (b.length() > 0) b.append(','); b.append(r); }
            return b.toString();
        }

        /**
         * Runs one device action. Results come back asynchronously on
         * window.__phone({id,ok,msg}) so a started activity never blocks the
         * JavaScript thread it was called from.
         */
        @JavascriptInterface
        public void phoneDo(final String action, final String argsJson) {
            org.json.JSONObject parsed;
            try {
                parsed = (argsJson == null || argsJson.trim().isEmpty())
                        ? new org.json.JSONObject() : new org.json.JSONObject(argsJson);
            } catch (Throwable t) {
                parsed = new org.json.JSONObject();
            }
            final org.json.JSONObject a = parsed;
            ui.post(() -> {
                String ok = "1", msg;
                try {
                    msg = doPhone(action, a);
                } catch (Throwable t) {
                    ok = "0";
                    String m = t.getMessage();
                    msg = m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
                }
                StringBuilder js = new StringBuilder("{\"ok\":").append(ok)
                        .append(",\"msg\":").append(MainActivity.js(msg))
                        .append(",\"id\":").append(MainActivity.js(a.optString("_id", "")))
                        .append("}");
                post("window.__phone && window.__phone(" + js + ")");
            });
        }

        @JavascriptInterface
        public void speak(String text) {
            if (tts == null || text == null || text.trim().isEmpty()) return;
            String clean = text.length() > 600 ? text.substring(0, 600) : text;
            try {
                tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "markliv");
            } catch (Throwable ignored) {
            }
        }

        @JavascriptInterface
        public void stopSpeak() {
            try { if (tts != null) tts.stop(); } catch (Throwable ignored) { }
        }

        @JavascriptInterface
        public void buzz(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v == null || !v.hasVibrator()) return;
                if (Build.VERSION.SDK_INT >= 26)
                    v.vibrate(VibrationEffect.createOneShot(Math.max(1, ms), VibrationEffect.DEFAULT_AMPLITUDE));
                else
                    v.vibrate(Math.max(1, ms));
            } catch (Throwable ignored) {
            }
        }

        // ---- speech in ----
        @JavascriptInterface
        public void startMic() {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                return;
            }
            beginListening();
        }

        @JavascriptInterface
        public void stopMic() { micWanted = false; stopListening(); }

        // ---- model picker (user-supplied GGUF) ----
        @JavascriptInterface
        public void pickModel() { requestModelFile(); }

        @JavascriptInterface
        public String bundledModelName() {
            String[] names = assetModelNames();
            return names.length > 0 ? names[0] : "";
        }

        @JavascriptInterface
        public void exitApp() { ui.post(() -> finish()); }

        @JavascriptInterface
        public void toast(String msg) { Log.i(TAG, "ui: " + msg); }
    }

    private static final class StatFsHolder {
        static void free(Object o) { }
    }

    private long freeSpace() {
        try {
            File dir = getFilesDir();
            return dir.getUsableSpace();
        } catch (Throwable t) {
            return 0;
        }
    }

    private String[] assetModelNames() {
        try {
            String[] names = getAssets().list("models");
            if (names == null) return new String[0];
            java.util.List<String> out = new ArrayList<>();
            for (String n : names) if (n.toLowerCase(Locale.ROOT).endsWith(".gguf")) out.add(n);
            return out.toArray(new String[0]);
        } catch (Throwable t) {
            return new String[0];
        }
    }

    /** copy assets/models/<name>.gguf -> filesDir/models/<name>.gguf (once) */
    private File installBundledModel() throws Exception {
        String[] names = assetModelNames();
        if (names.length == 0) return null;
        String name = names[0];
        File dir = new File(getFilesDir(), "models");
        if (!dir.exists() && !dir.mkdirs()) return null;
        File target = new File(dir, name);

        long assetSize = assetSize("models/" + name);
        if (target.exists() && assetSize > 0 && target.length() == assetSize) {
            post("window.__mlStatus && window.__mlStatus(" + js("model already installed") + ")");
            return target;
        }

        post("window.__mlStatus && window.__mlStatus(" + js("installing " + name + " (first run)") + ")");
        try (InputStream in = getAssets().open("models/" + name)) {
            try (OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[COPY_CHUNK];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    final int pct = assetSize > 0 ? (int) (100 * done / assetSize) : 0;
                    post("window.__mlStatus && window.__mlStatus(" +
                            js("installing model " + pct + "%") + ")");
                }
                out.flush();
            }
        }
        return target;
    }

    private long assetSize(String path) {
        try (InputStream in = getAssets().open(path)) {
            return in.available();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------- mic

    private void beginListening() {
        micWanted = true;
        ui.post(() -> {
            try {
                if (asr == null) {
                    SpeechRecognizer sr = SpeechRecognizer.createSpeechRecognizer(this);
                    sr.setRecognitionListener(new RecognitionListener() {
                        @Override public void onReadyForSpeech(Bundle b) { post("window.__mlMic && window.__mlMic('ready')"); }
                        @Override public void onBeginningOfSpeech() { post("window.__mlMic && window.__mlMic('listening')"); }
                        @Override public void onRmsChanged(float v) { }
                        @Override public void onBufferReceived(byte[] b) { }
                        @Override public void onEndOfSpeech() { post("window.__mlMic && window.__mlMic('thinking')"); }
                        @Override public void onError(int e) {
                            micWanted = false;
                            post("window.__mlMic && window.__mlMic('error:" + e + "')");
                        }
                        @Override public void onResults(Bundle b) {
                            micWanted = false;
                            ArrayList<String> r = b == null ? null
                                    : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                            String text = (r != null && !r.isEmpty()) ? r.get(0) : "";
                            post("window.__mlHeard && window.__mlHeard(" + js(text) + ")");
                        }
                        @Override public void onPartialResults(Bundle b) {
                            ArrayList<String> r = b == null ? null
                                    : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                            if (r != null && !r.isEmpty())
                                post("window.__mlPartial && window.__mlPartial(" + js(r.get(0)) + ")");
                        }
                        @Override public void onEvent(int t, Bundle b) { }
                    });
                    asr = sr;
                }
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                asr.startListening(i);
            } catch (Throwable t) {
                micWanted = false;
                post("window.__mlMic && window.__mlMic('error')");
            }
        });
    }

    private void stopListening() {
        ui.post(() -> {
            try { if (asr != null) asr.stopListening(); } catch (Throwable ignored) { }
        });
    }

    // ----------------------------------------------------------- model picker

    private void requestModelFile() {
        ui.post(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "application/x-gguf"});
                startActivityForResult(i, REQ_PICK_MODEL);
            } catch (Throwable t) {
                post("window.__mlError && window.__mlError(" + js("no file picker available") + ")");
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_MODEL) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            post("window.__mlPicked && window.__mlPicked(" + js("") + ")");
            return;
        }
        Uri uri = data.getData();
        final String name = queryName(uri);
        brain.execute(() -> {
            String copied = "";
            try {
                File dir = new File(getFilesDir(), "models");
                if (!dir.exists()) dir.mkdirs();
                File dst = new File(dir, name.isEmpty() ? "custom.gguf" : name);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[COPY_CHUNK];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                }
                copied = dst.getAbsolutePath();
            } catch (Throwable t) {
                Log.e(TAG, "copy model", t);
            }
            final String path = copied;
            post("window.__mlPicked && window.__mlPicked(" + js(path) + ")");
            if (!path.isEmpty()) {
                String info = Llama.loadModel(path, 2048, threads());
                post("window.__mlLoaded && window.__mlLoaded(" + js(info) + ")");
            }
        });
    }

    private String queryName(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    // -------------------------------------------------------------- lifecycle

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && micWanted)
                beginListening();
            else
                post("window.__mlMic && window.__mlMic('denied')");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) { }
        micWanted = false;
        stopListening();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Llama.setSink(null);
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) { }
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) { }
        try { if (asr != null) asr.destroy(); } catch (Throwable ignored) { }
        brain.execute(Llama::freeModel);
        brain.shutdown();
        cloud.shutdownNow();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
