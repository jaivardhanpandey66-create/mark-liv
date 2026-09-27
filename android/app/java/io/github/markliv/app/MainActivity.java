package io.github.markliv.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TAG = "markliv";
    private static final int REQ_MIC = 4711;
    private static final int REQ_PICK_MODEL = 4712;
    private static final int COPY_CHUNK = 1 << 20;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService brain = Executors.newSingleThreadExecutor();

    private TextToSpeech tts;
    private SpeechRecognizer asr;
    private boolean micBusy = false;

    private File modelFile;          // models/<name>.ggmf inside filesDir
    private String pendingPickPath = null;
    private volatile boolean micWanted = false;

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
        public void stop() { Llama.stop(); }

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
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) { }
        try { if (asr != null) asr.destroy(); } catch (Throwable ignored) { }
        brain.execute(Llama::freeModel);
        brain.shutdown();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
