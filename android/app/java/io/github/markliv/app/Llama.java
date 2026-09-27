package io.github.markliv.app;

/** Thin JNI facade over llama.cpp. Load order: copy GGUF to filesDir, then loadModel(). */
public final class Llama {

    static {
        try {
            System.loadLibrary("markliv");
        } catch (Throwable t) {
            // keep the app alive: the rule brain still works without the local LLM
        }
    }

    public interface Sink {
        void onToken(String piece);
        void onStatus(String status);
    }

    private static volatile Sink sink;

    public static void setSink(Sink s) { sink = s; }

    /** called from the native thread for every generated token */
    static void onToken(String piece) {
        Sink s = sink;
        if (s != null) s.onToken(piece);
    }

    /** called from the native thread for progress messages */
    static void onStatus(String status) {
        Sink s = sink;
        if (s != null) s.onStatus(status);
    }

    public static native String loadModel(String path, int nCtx, int nThreads);
    public static native void freeModel();
    public static native boolean isLoaded();
    public static native String info();
    public static native String generate(String system, String history, String prompt,
                                         int maxTokens, int nThreads);
    public static native void stop();
}
