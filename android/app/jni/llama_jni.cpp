// ===========================================================================
//  llama_jni.cpp — MARK LIV local brain
//  JNI bridge around llama.cpp: loads a GGUF model from the app's private
//  storage and generates text token-by-token, streaming each piece back into
//  the WebView UI.  Everything runs on-device: no sockets, no API key.
// ===========================================================================
#include <jni.h>
#include <android/log.h>

#include "llama.h"
#include "ggml.h"

#include <algorithm>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "markliv", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "markliv", __VA_ARGS__)

namespace {

struct Brain {
    llama_model   * model  = nullptr;
    llama_context * ctx    = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_sampler * smpl   = nullptr;
    int  n_ctx    = 2048;
    int  n_predict_max = 512;
    bool loaded   = false;
    std::atomic<bool> stop_flag{false};
    std::string model_path;
    std::string arch;
    std::string name;
    int n_params = 0;
};

Brain g;

JavaVM *g_vm = nullptr;
jclass  g_cls_callbacks = nullptr;   // io/github/markliv/app/Llama
jmethodID g_mid_token  = nullptr;
jmethodID g_mid_status = nullptr;

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return std::string();
    const char *raw = env->GetStringUTFChars(s, nullptr);
    std::string out(raw ? raw : "");
    if (raw) env->ReleaseStringUTFChars(s, raw);
    return out;
}

void call_token(const std::string & piece) {
    if (!g_vm || !g_cls_callbacks || !g_mid_token) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    jstring js = env->NewStringUTF(piece.c_str());
    if (js) {
        env->CallStaticVoidMethod(g_cls_callbacks, g_mid_token, js);
        env->DeleteLocalRef(js);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

void call_status(const std::string & msg) {
    if (!g_vm || !g_cls_callbacks || !g_mid_status) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    jstring js = env->NewStringUTF(msg.c_str());
    if (js) {
        env->CallStaticVoidMethod(g_cls_callbacks, g_mid_status, js);
        env->DeleteLocalRef(js);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

void free_brain() {
    if (g.smpl)  { llama_sampler_free(g.smpl);  g.smpl  = nullptr; }
    if (g.ctx)   { llama_free(g.ctx);           g.ctx   = nullptr; }
    if (g.model) { llama_model_free(g.model);   g.model = nullptr; }
    g.vocab   = nullptr;
    g.loaded  = false;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local = env->FindClass("io/github/markliv/app/Llama");
    if (local) {
        g_cls_callbacks = (jclass) env->NewGlobalRef(local);
        g_mid_token  = env->GetStaticMethodID(local, "onToken", "(Ljava/lang/String;)V");
        g_mid_status = env->GetStaticMethodID(local, "onStatus", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(local);
    }
    return JNI_VERSION_1_6;
}

// ---- load / free ------------------------------------------------------------

JNIEXPORT jstring JNICALL
Java_io_github_markliv_app_Llama_loadModel(JNIEnv *env, jclass,
                                           jstring path, jint n_ctx, jint n_threads) {
    (void) env;
    const std::string p = jstr(env, path);
    if (p.empty()) return env->NewStringUTF("no model path");

    call_status("unloading previous model");
    free_brain();

    llama_backend_init();
    ggml_backend_load_all();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;              // CPU only — works on every phone
    mparams.check_tensors = false;

    call_status("reading " + p.substr(p.find_last_of('/') + 1));
    g.model = llama_model_load_from_file(p.c_str(), mparams);
    if (!g.model) {
        LOGE("failed to load model: %s", p.c_str());
        return env->NewStringUTF("could not read the model file");
    }
    g.vocab     = llama_model_get_vocab(g.model);
    g.model_path = p;
    g.n_params  = (int) llama_model_n_params(g.model);

    char buf[256];
    buf[0] = 0;
    if (llama_model_meta_val_str(g.model, "general.architecture", buf, sizeof(buf)) > 0)
        g.arch = buf;
    buf[0] = 0;
    if (llama_model_meta_val_str(g.model, "general.name", buf, sizeof(buf)) > 0)
        g.name = buf;
    if (g.name.empty()) g.name = g.arch;

    g.n_ctx = n_ctx > 0 ? (int) n_ctx : 2048;
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx        = (uint32_t) g.n_ctx;
    cparams.n_batch      = (uint32_t) std::min(g.n_ctx, 512);
    cparams.n_ubatch     = (uint32_t) std::min(g.n_ctx, 512);
    cparams.n_threads    = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = n_threads > 0 ? n_threads : 4;
    cparams.no_perf      = true;

    call_status("starting context (" + std::to_string(g.n_ctx) + " ctx)");
    g.ctx = llama_init_from_model(g.model, cparams);
    if (!g.ctx) {
        llama_model_free(g.model);
        g.model = nullptr;
        return env->NewStringUTF("could not start the context — not enough memory?");
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    g.smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g.smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g.smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(g.smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    g.loaded = true;
    call_status("model ready");

    std::string info = g.name + " · " + g.arch + " · " + std::to_string(g.n_params / 1000000) + "M params";
    LOGI("model loaded: %s", info.c_str());
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT void JNICALL
Java_io_github_markliv_app_Llama_freeModel(JNIEnv *, jclass) {
    free_brain();
}

JNIEXPORT jboolean JNICALL
Java_io_github_markliv_app_Llama_isLoaded(JNIEnv *, jclass) {
    return g.loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_io_github_markliv_app_Llama_info(JNIEnv *env, jclass) {
    if (!g.loaded) return env->NewStringUTF("");
    std::string s = g.name + "|" + g.arch + "|" + g.model_path;
    return env->NewStringUTF(s.c_str());
}

// ---- generation -------------------------------------------------------------

JNIEXPORT void JNICALL
Java_io_github_markliv_app_Llama_stop(JNIEnv *, jclass) {
    g.stop_flag.store(true);
}

/*  system  : persona / grounding prompt
 *  history : condensed earlier turns ("user: ...\nassistant: ...")
 *  prompt  : the new question
 *  maxTokens / nThreads
 *  returns the full generated text (tokens were also streamed via onToken)
 */
JNIEXPORT jstring JNICALL
Java_io_github_markliv_app_Llama_generate(JNIEnv *env, jclass,
                                          jstring system, jstring history, jstring prompt,
                                          jint maxTokens, jint nThreads) {
    if (!g.loaded || !g.ctx) return env->NewStringUTF("");

    const std::string sys  = jstr(env, system);
    const std::string hist = jstr(env, history);
    const std::string user_raw = jstr(env, prompt);

    /* user content: condensed history + the new question */
    std::string user = user_raw;
    if (!hist.empty()) user = "Conversation so far:\n" + hist + "\n\nNow answer this: " + user_raw;

    std::vector<llama_chat_message> msgs;
    if (!sys.empty()) {
        llama_chat_message m;
        m.role = "system";
        m.content = sys.c_str();
        msgs.push_back(m);
    }
    {
        llama_chat_message m;
        m.role = "user";
        m.content = user.c_str();
        msgs.push_back(m);
    }

    /* apply the model's own chat template */
    std::vector<char> tmpl;
    {
        const char * ctpl = llama_model_chat_template(g.model, nullptr);
        int32_t need = -1;
        if (ctpl) {
            need = llama_chat_apply_template(ctpl, msgs.data(), (int32_t) msgs.size(), true, nullptr, 0);
        }
        if (need > 0) {
            tmpl.resize((size_t) need + 1);
            llama_chat_apply_template(ctpl, msgs.data(), (int32_t) msgs.size(), true,
                                     tmpl.data(), (size_t) tmpl.size());
        } else {
            /* no template in the GGUF — Qwen-style fallback */
            std::string s;
            if (!sys.empty()) s += "<|im_start|>system\n" + sys + "<|im_end|>\n";
            s += "<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
            tmpl.assign(s.begin(), s.end());
            tmpl.push_back('\0');
        }
    }
    const std::string prompt_text(tmpl.data());

    /* tokenize */
    int n_prompt = -llama_tokenize(g.vocab, prompt_text.c_str(), prompt_text.size(), nullptr, 0, true, true);
    if (n_prompt <= 0) return env->NewStringUTF("");
    int budget = std::min(g.n_ctx - 8, n_prompt + (int) maxTokens);
    if (n_prompt > g.n_ctx / 2) {
        /* prompt too long for the context — keep the tail */
        n_prompt = g.n_ctx / 2;
    }
    std::vector<llama_token> tokens((size_t) n_prompt);
    if (llama_tokenize(g.vocab, prompt_text.c_str(), prompt_text.size(),
                       tokens.data(), tokens.size(), true, true) < 0) {
        return env->NewStringUTF("");
    }
    if ((int) tokens.size() > g.n_ctx - (int) maxTokens - 4) {
        tokens.resize((size_t) std::max(1, g.n_ctx - (int) maxTokens - 4));
    }

    llama_memory_clear(llama_get_memory(g.ctx), true);

    if (nThreads > 0) {
        llama_set_n_threads(g.ctx, nThreads, nThreads);
    }

    g.stop_flag.store(false);
    std::string out;
    int n_decoded = 0;
    const int64_t t0 = ggml_time_us();

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    int pos = 0;
    int guard = 0;
    const int max_guard = (int) maxTokens + 8;

    while (pos + batch.n_tokens < (int) tokens.size() + (int) maxTokens) {
        if (g.stop_flag.load()) break;
        if (llama_decode(g.ctx, batch) != 0) {
            LOGE("llama_decode failed at pos %d", pos);
            break;
        }
        pos += batch.n_tokens;

        llama_token id = llama_sampler_sample(g.smpl, g.ctx, -1);
        if (llama_vocab_is_eog(g.vocab, id)) break;

        char buf[256];
        int n = llama_token_to_piece(g.vocab, id, buf, sizeof(buf), 0, true);
        if (n < 0) break;
        std::string piece(buf, (size_t) n);
        out += piece;
        call_token(piece);
        n_decoded++;

        batch = llama_batch_get_one(&id, 1);
        if (++guard > max_guard) break;
    }

    const float secs = (ggml_time_us() - t0) / 1e6f;
    if (secs > 0.01f) {
        char stat[128];
        snprintf(stat, sizeof(stat), " · %d tok in %.1fs (%.1f tok/s)",
                 n_decoded, secs, n_decoded / secs);
        call_status(stat);
    }
    LOGI("generated %d tokens in %.2fs", n_decoded, secs);
    return env->NewStringUTF(out.c_str());
}

} // extern "C"
