// llama-android.cpp (crash-proof / mobile-safe)
//
// Key crash causes fixed:
// 1) SIGABRT from ggml/llama asserts when prompt tokens > n_batch
//    ✅ FIX: prompt/embedding evaluation is now CHUNKED into <= n_batch pieces.
// 2) SIGABRT / weird logs because ggml callback text was treated like printf fmt
//    ✅ FIX: log_callback prints with "%s".
// 3) UnsatisfiedLinkError for embedding_for_text when symbol missing
//    ✅ FIX: embedding_for_text is included and matches JNI signature.
// 4) More null checks + malloc checks to avoid native aborts.
//
// NOTE: This cannot guarantee against *all* internal ggml asserts (e.g., OOM),
// but it removes the common ones you were hitting (n_batch/prompt overflow).

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <iomanip>
#include <mutex>
#include <sstream>
#include <string>
#include <unistd.h>
#include <unordered_map>
#include <vector>

#include "llama.h"
#include "common.h"

#define JSON_ASSERT GGML_ASSERT
#include "json.hpp"

using json = nlohmann::ordered_json;

template <typename T>
static T json_value(const json & body, const std::string & key, const T & default_value) {
    if (body.contains(key) && !body.at(key).is_null()) {
        try {
            return body.at(key);
        } catch (NLOHMANN_JSON_NAMESPACE::detail::type_error const &) {
            return default_value;
        }
    }
    return default_value;
}

#define TAG "llama-android.cpp"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/**
 * SPEED DEFAULTS (Chat)
 * Keep n_ctx modest for mobile. n_batch modest too.
 * IMPORTANT: We chunk decode, so prompts > n_batch do NOT crash.
 */
static const int CHAT_N_CTX_DEFAULT   = 1024;
static const int CHAT_N_BATCH_DEFAULT = 512;

static std::string cached_token_chars;

// Track heap allocations for llama_batch so free_batch() can reliably free all buffers.
static std::mutex g_batch_mu;
static std::unordered_map<llama_batch *, int> g_batch_n_tokens;
static std::unordered_map<llama_batch *, int> g_batch_n_seq_max;

// Track context limits so we can safely chunk decode.
static std::mutex g_ctx_mu;
static std::unordered_map<llama_context *, int> g_ctx_n_batch;
static std::unordered_map<llama_context *, int> g_ctx_n_ctx;

static bool is_valid_utf8(const char * string) {
    if (!string) return true;

    const unsigned char * bytes = (const unsigned char *) string;
    int num = 0;

    while (*bytes != 0x00) {
        if      ((*bytes & 0x80) == 0x00) num = 1;
        else if ((*bytes & 0xE0) == 0xC0) num = 2;
        else if ((*bytes & 0xF0) == 0xE0) num = 3;
        else if ((*bytes & 0xF8) == 0xF0) num = 4;
        else return false;

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
}

static std::string mapListToJSONString(JNIEnv *env, jobjectArray allMessages) {
    json jsonArray = json::array();

    jclass mapClass = env->FindClass("java/util/Map");
    if (!mapClass) return jsonArray.dump();

    jmethodID getMethod = env->GetMethodID(mapClass, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (!getMethod) {
        env->DeleteLocalRef(mapClass);
        return jsonArray.dump();
    }

    jstring roleKey = env->NewStringUTF("role");
    jstring contentKey = env->NewStringUTF("content");

    jsize arrayLength = env->GetArrayLength(allMessages);
    for (jsize i = 0; i < arrayLength; ++i) {
        jobject messageObj = env->GetObjectArrayElement(allMessages, i);
        if (!messageObj) continue;

        if (!env->IsInstanceOf(messageObj, mapClass)) {
            env->DeleteLocalRef(messageObj);
            continue;
        }

        json jsonMsg;

        jobject roleObj = env->CallObjectMethod(messageObj, getMethod, roleKey);
        if (roleObj) {
            const char* roleStr = env->GetStringUTFChars((jstring) roleObj, nullptr);
            jsonMsg["role"] = roleStr ? roleStr : "";
            if (roleStr) env->ReleaseStringUTFChars((jstring) roleObj, roleStr);
            env->DeleteLocalRef(roleObj);
        }

        jobject contentObj = env->CallObjectMethod(messageObj, getMethod, contentKey);
        if (contentObj) {
            const char* contentStr = env->GetStringUTFChars((jstring) contentObj, nullptr);
            jsonMsg["content"] = contentStr ? contentStr : "";
            if (contentStr) env->ReleaseStringUTFChars((jstring) contentObj, contentStr);
            env->DeleteLocalRef(contentObj);
        }

        if (!jsonMsg.empty()) jsonArray.push_back(jsonMsg);
        env->DeleteLocalRef(messageObj);
    }

    env->DeleteLocalRef(roleKey);
    env->DeleteLocalRef(contentKey);
    env->DeleteLocalRef(mapClass);

    return jsonArray.dump();
}

/**
 * ✅ IMPORTANT:
 * ggml/llama sends already-formatted text; do NOT treat it like printf format.
 */
static void log_callback(ggml_log_level level, const char * text, void * data) {
    (void) data;
    if (!text) return;

    if      (level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR,   TAG, "%s", text);
    else if (level == GGML_LOG_LEVEL_INFO)  __android_log_print(ANDROID_LOG_INFO,    TAG, "%s", text);
    else if (level == GGML_LOG_LEVEL_WARN)  __android_log_print(ANDROID_LOG_WARN,    TAG, "%s", text);
    else                                    __android_log_print(ANDROID_LOG_DEFAULT, TAG, "%s", text);
}

static int get_batch_capacity(llama_batch * batch) {
    if (!batch) return 0;
    std::lock_guard<std::mutex> lk(g_batch_mu);
    auto it = g_batch_n_tokens.find(batch);
    return it != g_batch_n_tokens.end() ? it->second : 0;
}

static void get_ctx_limits(llama_context * ctx, int & out_n_ctx, int & out_n_batch) {
    out_n_ctx = ctx ? llama_n_ctx(ctx) : 0;
    out_n_batch = 0;

    if (ctx) {
        std::lock_guard<std::mutex> lk(g_ctx_mu);
        auto itB = g_ctx_n_batch.find(ctx);
        auto itC = g_ctx_n_ctx.find(ctx);
        if (itC != g_ctx_n_ctx.end()) out_n_ctx = itC->second;
        if (itB != g_ctx_n_batch.end()) out_n_batch = itB->second;
    }

    if (out_n_batch <= 0) out_n_batch = CHAT_N_BATCH_DEFAULT;
    if (out_n_ctx   <= 0) out_n_ctx   = CHAT_N_CTX_DEFAULT;
}

/**
 * Chunked prompt evaluation to avoid n_batch asserts.
 * - Adds tokens with positions [pos0..pos0+len-1]
 * - Calls llama_decode per chunk
 * - logits only on final token of final chunk when want_logits=true
 */
static bool decode_tokens_chunked(
        llama_context * ctx,
        llama_batch   * batch,
        const std::vector<llama_token> & tokens,
        int pos0,
        bool want_logits_last_token
) {
    if (!ctx || !batch) return false;
    if (tokens.empty()) return true;

    int n_ctx = 0, n_batch_ctx = 0;
    get_ctx_limits(ctx, n_ctx, n_batch_ctx);

    const int batch_cap = get_batch_capacity(batch);
    const int chunk_cap = std::max(1, std::min(n_batch_ctx, batch_cap > 0 ? batch_cap : n_batch_ctx));

    int done = 0;
    while (done < (int)tokens.size()) {
        const int take = std::min(chunk_cap, (int)tokens.size() - done);

        common_batch_clear(*batch);
        for (int i = 0; i < take; ++i) {
            common_batch_add(*batch, tokens[done + i], pos0 + done + i, {0}, false);
        }

        // logits only on very last token of very last chunk (needed for sampling)
        if (want_logits_last_token && (done + take) == (int)tokens.size()) {
            if (batch->n_tokens > 0) batch->logits[batch->n_tokens - 1] = true;
        }

        const int rc = llama_decode(ctx, *batch);
        if (rc != 0) {
            LOGe("llama_decode() failed rc=%d (chunk done=%d take=%d)", rc, done, take);
            return false;
        }

        done += take;
    }

    return true;
}

// ---------------- JNI exports ----------------

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_log_1to_1android(JNIEnv *, jobject) {
    llama_log_set(log_callback, NULL);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_backend_1init(JNIEnv *, jobject) {
    llama_backend_init();
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_backend_1free(JNIEnv *, jobject) {
    llama_backend_free();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_android_llama_cpp_LLamaAndroid_system_1info(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_load_1model(JNIEnv *env, jobject, jstring filename) {
    llama_model_params model_params = llama_model_default_params();

    const char * path_to_model = env->GetStringUTFChars(filename, 0);
    LOGi("Loading model from %s", path_to_model ? path_to_model : "(null)");

    llama_model * model = llama_load_model_from_file(path_to_model, model_params);

    if (path_to_model) env->ReleaseStringUTFChars(filename, path_to_model);

    if (!model) {
        LOGe("load_model() failed");
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "load_model() failed");
        return 0;
    }

    return reinterpret_cast<jlong>(model);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1model(JNIEnv *, jobject, jlong model) {
    llama_free_model(reinterpret_cast<llama_model *>(model));
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1context(JNIEnv *env, jobject, jlong jmodel, jint userThreads) {
    auto model = reinterpret_cast<llama_model *>(jmodel);
    if (!model) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Model cannot be null");
        return 0;
    }

    const int cores = std::max(2, (int)sysconf(_SC_NPROCESSORS_ONLN));
    const int max_threads = std::min(8, cores);
    const int threads = (userThreads > 0)
                        ? std::max(2, std::min(max_threads, (int)userThreads))
                        : std::max(2, max_threads);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx   = CHAT_N_CTX_DEFAULT;
    ctx_params.n_batch = (uint32_t) std::min((int)CHAT_N_BATCH_DEFAULT, (int)ctx_params.n_ctx);
    ctx_params.n_threads       = threads;
    ctx_params.n_threads_batch = threads;

    LOGi("new_context(): cores=%d threads=%d n_ctx=%d n_batch=%d",
         cores, threads, (int)ctx_params.n_ctx, (int)ctx_params.n_batch);

    llama_context * ctx = llama_new_context_with_model(model, ctx_params);
    if (!ctx) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "llama_new_context_with_model() returned null");
        return 0;
    }

    {
        std::lock_guard<std::mutex> lk(g_ctx_mu);
        g_ctx_n_ctx[ctx]   = (int)ctx_params.n_ctx;
        g_ctx_n_batch[ctx] = (int)ctx_params.n_batch;
    }

    return reinterpret_cast<jlong>(ctx);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1embedding_1context(JNIEnv *env, jobject, jlong jmodel, jint userThreads, jint nCtx, jint poolingType) {
    auto model = reinterpret_cast<llama_model *>(jmodel);
    if (!model) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Model cannot be null");
        return 0;
    }

    const int cores = std::max(2, (int)sysconf(_SC_NPROCESSORS_ONLN));
    const int max_threads = std::min(8, cores);
    const int threads = (userThreads > 0)
                        ? std::max(2, std::min(max_threads, (int)userThreads))
                        : std::max(2, max_threads);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = (int)nCtx;
    ctx_params.n_threads       = threads;
    ctx_params.n_threads_batch = threads;

    ctx_params.embeddings      = true;
    ctx_params.pooling_type    = (enum llama_pooling_type)poolingType;

    // Keep embedding batch reasonable; we chunk anyway.
    ctx_params.n_batch = (uint32_t) std::min((int)nCtx, 512);

    LOGi("new_embedding_context(): cores=%d threads=%d n_ctx=%d n_batch=%d pooling=%d",
         cores, threads, (int)ctx_params.n_ctx, (int)ctx_params.n_batch, poolingType);

    llama_context * ctx = llama_new_context_with_model(model, ctx_params);
    if (!ctx) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "new_embedding_context(): llama_new_context_with_model() returned null");
        return 0;
    }

    {
        std::lock_guard<std::mutex> lk(g_ctx_mu);
        g_ctx_n_ctx[ctx]   = (int)ctx_params.n_ctx;
        g_ctx_n_batch[ctx] = (int)ctx_params.n_batch;
    }

    return reinterpret_cast<jlong>(ctx);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1context(JNIEnv *, jobject, jlong context) {
    auto ctx = reinterpret_cast<llama_context *>(context);
    if (ctx) {
        std::lock_guard<std::mutex> lk(g_ctx_mu);
        g_ctx_n_ctx.erase(ctx);
        g_ctx_n_batch.erase(ctx);
    }
    llama_free(ctx);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1batch(JNIEnv *env, jobject, jint n_tokens, jint embd, jint n_seq_max) {
    if (n_tokens <= 0 || n_seq_max <= 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "new_batch(): invalid sizes");
        return 0;
    }

    llama_batch *batch = new llama_batch{0, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr};

    auto malloc_or_throw = [&](size_t bytes) -> void * {
        void * p = malloc(bytes);
        if (!p) {
            env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "new_batch(): malloc failed");
        }
        return p;
    };

    if (embd) {
        batch->embd = (float *) malloc_or_throw(sizeof(float) * (size_t)n_tokens * (size_t)embd);
        if (env->ExceptionCheck()) { delete batch; return 0; }
    } else {
        batch->token = (llama_token *) malloc_or_throw(sizeof(llama_token) * (size_t)n_tokens);
        if (env->ExceptionCheck()) { delete batch; return 0; }
    }

    batch->pos      = (llama_pos *)     malloc_or_throw(sizeof(llama_pos)      * (size_t)n_tokens);
    if (env->ExceptionCheck()) { delete batch; return 0; }

    batch->n_seq_id = (int32_t *)       malloc_or_throw(sizeof(int32_t)        * (size_t)n_tokens);
    if (env->ExceptionCheck()) { delete batch; return 0; }

    batch->seq_id   = (llama_seq_id **) malloc_or_throw(sizeof(llama_seq_id *) * (size_t)n_tokens);
    if (env->ExceptionCheck()) { delete batch; return 0; }

    for (int i = 0; i < n_tokens; ++i) {
        batch->seq_id[i] = (llama_seq_id *) malloc_or_throw(sizeof(llama_seq_id) * (size_t)n_seq_max);
        if (env->ExceptionCheck()) { delete batch; return 0; }
    }

    batch->logits   = (int8_t *)        malloc_or_throw(sizeof(int8_t)         * (size_t)n_tokens);
    if (env->ExceptionCheck()) { delete batch; return 0; }

    {
        std::lock_guard<std::mutex> lk(g_batch_mu);
        g_batch_n_tokens[batch] = (int)n_tokens;
        g_batch_n_seq_max[batch] = (int)n_seq_max;
    }

    return reinterpret_cast<jlong>(batch);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1batch(JNIEnv *, jobject, jlong batch_pointer) {
    auto batch = reinterpret_cast<llama_batch *>(batch_pointer);
    if (!batch) return;

    int n_tokens = 0;
    {
        std::lock_guard<std::mutex> lk(g_batch_mu);
        auto itN = g_batch_n_tokens.find(batch);
        if (itN != g_batch_n_tokens.end()) n_tokens = itN->second;
        g_batch_n_tokens.erase(batch);
        g_batch_n_seq_max.erase(batch);
    }

    common_batch_clear(*batch);

    if (batch->token) free(batch->token);
    if (batch->embd)  free(batch->embd);
    if (batch->pos)   free(batch->pos);

    if (batch->seq_id) {
        for (int i = 0; i < n_tokens; ++i) free(batch->seq_id[i]);
        free(batch->seq_id);
    }

    if (batch->n_seq_id) free(batch->n_seq_id);
    if (batch->logits)   free(batch->logits);

    delete batch;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1sampler(JNIEnv *, jobject, jfloat top_p, jint top_k, jfloat temp) {
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;

    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k == 0 ? 40 : top_k));

    if (top_p == 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    } else {
        float adjusted_top_p = roundf(top_p * 10.0f) / 10.0f;
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(adjusted_top_p, 1));
    }

    if (temp == 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.4f));
    } else {
        float adjusted_temp = roundf(temp * 10.0f) / 10.0f;
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(adjusted_temp));
    }

    llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));
    return reinterpret_cast<jlong>(smpl);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1sampler(JNIEnv *, jobject, jlong sampler_pointer) {
    llama_sampler_free(reinterpret_cast<llama_sampler *>(sampler_pointer));
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_kv_1cache_1clear(JNIEnv *, jobject, jlong context) {
    llama_kv_cache_clear(reinterpret_cast<llama_context *>(context));
}

extern "C"
JNIEXPORT jint JNICALL
Java_android_llama_cpp_LLamaAndroid_completion_1init(
        JNIEnv *env,
        jobject,
        jlong context_pointer,
        jlong batch_pointer,
        jstring jtext,
        jint n_len
) {
    cached_token_chars.clear();

    auto ctx   = reinterpret_cast<llama_context *>(context_pointer);
    auto batch = reinterpret_cast<llama_batch *>(batch_pointer);

    if (!ctx || !batch) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "completion_init(): context/batch is null");
        return 0;
    }

    const int n_ctx = llama_n_ctx(ctx);

    const char * text = env->GetStringUTFChars(jtext, 0);
    llama_kv_cache_clear(ctx);

    std::vector<llama_token> tokens = common_tokenize(ctx, text ? text : "", 1);
    if (text) env->ReleaseStringUTFChars(jtext, text);

    // ---- SAFETY: trim prompt to fit KV cache (n_ctx) ----
    // Ensure prompt + n_len fits n_ctx.
    int max_prompt = n_ctx - (int)n_len - 8;
    if (max_prompt < 64) max_prompt = std::max(64, n_ctx - 64);

    if ((int)tokens.size() > max_prompt) {
        tokens.erase(tokens.begin(), tokens.end() - max_prompt); // keep last tokens
        LOGi("completion_init: trimmed prompt to max_prompt=%d (n_ctx=%d n_len=%d)", max_prompt, n_ctx, n_len);
    }

    const int prompt_tokens = (int)tokens.size();
    LOGi("completion_init: prompt_tokens=%d n_len=%d n_ctx=%d", prompt_tokens, n_len, n_ctx);

    // ---- CRASH-PROOF: chunked prompt eval (avoids n_batch asserts) ----
    const bool ok = decode_tokens_chunked(ctx, batch, tokens, /*pos0=*/0, /*want_logits_last_token=*/true);
    if (!ok) {
        // Do not abort process; return 0 so Kotlin can handle gracefully.
        LOGe("completion_init: decode_tokens_chunked failed");
        return 0;
    }

    // Return prompt length so Kotlin starts generation at correct position.
    return prompt_tokens;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_android_llama_cpp_LLamaAndroid_completion_1loop(
        JNIEnv * env,
        jobject,
        jlong context_pointer,
        jlong batch_pointer,
        jlong sampler_pointer,
        jint n_len,
        jobject intvar_ncur
) {
    auto ctx     = reinterpret_cast<llama_context *>(context_pointer);
    auto batch   = reinterpret_cast<llama_batch   *>(batch_pointer);
    auto sampler = reinterpret_cast<llama_sampler *>(sampler_pointer);

    if (!ctx || !batch || !sampler || !intvar_ncur) return nullptr;

    const int n_ctx = llama_n_ctx(ctx);
    const auto model = llama_get_model(ctx);

    jclass cls = env->GetObjectClass(intvar_ncur);
    if (!cls) return nullptr;

    jmethodID midGetValue = env->GetMethodID(cls, "getValue", "()I");
    jmethodID midInc      = env->GetMethodID(cls, "inc", "()V");
    if (!midGetValue || !midInc) {
        env->DeleteLocalRef(cls);
        return nullptr;
    }

    const jint n_cur = env->CallIntMethod(intvar_ncur, midGetValue);

    // Safety guard (avoid invalid positions)
    if (n_cur < 0 || n_cur >= n_ctx) {
        env->DeleteLocalRef(cls);
        return nullptr;
    }

    // sample next token
    const auto new_token_id = llama_sampler_sample(sampler, ctx, -1);

    if (llama_token_is_eog(model, new_token_id) ||
        n_cur == n_len ||
        new_token_id == llama_token_eot(model)) {
        env->DeleteLocalRef(cls);
        return nullptr;
    }

    // Convert token piece (may be partial UTF-8)
    cached_token_chars += common_token_to_piece(ctx, new_token_id);

    jstring out = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        out = env->NewStringUTF(cached_token_chars.c_str());
        cached_token_chars.clear();
    } else {
        out = env->NewStringUTF("");
    }

    // Decode this token
    common_batch_clear(*batch);
    common_batch_add(*batch, new_token_id, n_cur, {0}, true);

    env->CallVoidMethod(intvar_ncur, midInc);
    env->DeleteLocalRef(cls);

    const int rc = llama_decode(ctx, *batch);
    if (rc != 0) {
        LOGe("llama_decode() failed in completion_loop rc=%d", rc);
        return nullptr;
    }

    return out;
}

/**
 * Embedding API (matches Kotlin JNI signature)
 * CRASH-PROOF: chunked decode for long inputs.
 */
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_android_llama_cpp_LLamaAndroid_embedding_1for_1text(
        JNIEnv * env, jobject,
        jlong context_pointer, jlong batch_pointer, jstring jtext
) {
    auto ctx   = reinterpret_cast<llama_context *>(context_pointer);
    auto batch = reinterpret_cast<llama_batch *>(batch_pointer);

    if (!ctx || !batch) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "embedding_for_text(): context/batch is null");
        return env->NewFloatArray(0);
    }

    const char * text = env->GetStringUTFChars(jtext, 0);
    std::vector<llama_token> tokens = common_tokenize(ctx, text ? text : "", 1);
    if (text) env->ReleaseStringUTFChars(jtext, text);

    llama_kv_cache_clear(ctx);

    const int n_ctx = llama_n_ctx(ctx);
    if ((int)tokens.size() > n_ctx) tokens.resize(n_ctx);

    // chunked eval (logits true on last token for safety)
    const bool ok = decode_tokens_chunked(ctx, batch, tokens, /*pos0=*/0, /*want_logits_last_token=*/true);
    if (!ok) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "embedding_for_text(): decode failed");
        return env->NewFloatArray(0);
    }

    const llama_model * model = llama_get_model(ctx);

    const float * emb = llama_get_embeddings_seq(ctx, 0);
    if (!emb) emb = llama_get_embeddings(ctx);

    if (!emb) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Could not read embeddings from llama context");
        return env->NewFloatArray(0);
    }

    const int n_embd = llama_n_embd(model);
    jfloatArray out = env->NewFloatArray(n_embd);
    env->SetFloatArrayRegion(out, 0, n_embd, emb);
    return out;
}

// Format chat for template parsing
static std::string format_chat(const llama_model *model, const std::string &tmpl, const std::vector<json> &messages) {
    std::vector<common_chat_msg> chat;
    chat.reserve(messages.size());

    for (const auto & curr_msg : messages) {
        std::string role = json_value(curr_msg, "role", std::string(""));
        std::string content;

        if (curr_msg.contains("content")) {
            if (curr_msg["content"].is_string()) {
                content = curr_msg["content"].get<std::string>();
            } else if (curr_msg["content"].is_array()) {
                for (const auto &part : curr_msg["content"]) {
                    if (part.contains("text")) content += "\n" + part["text"].get<std::string>();
                }
            } else {
                throw std::runtime_error("Invalid 'content' type.");
            }
        } else {
            throw std::runtime_error("Missing 'content'.");
        }

        chat.push_back({role, content});
    }

    const auto formatted = common_chat_apply_template(model, tmpl, chat, true);
    LOGi("formatted_chat length=%d", (int)formatted.size());
    return formatted;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_android_llama_cpp_LLamaAndroid_oaicompat_1completion_1param_1parse(
        JNIEnv *env, jobject, jobjectArray allMessages, jlong model
) {
    try {
        std::string parsedData = mapListToJSONString(env, allMessages);
        std::vector<json> jsonMessages = json::parse(parsedData);
        const auto formatted = format_chat(reinterpret_cast<const llama_model *>(model), "", jsonMessages);
        return env->NewStringUTF(formatted.c_str());
    } catch (const std::exception &e) {
        LOGe("oaicompat parse error: %s", e.what());
        return env->NewStringUTF("");
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_android_llama_cpp_LLamaAndroid_get_1eot_1str(JNIEnv *env, jobject, jlong jmodel) {
    auto model = reinterpret_cast<llama_model *>(jmodel);
    if (!model) return env->NewStringUTF("<|im_end|>");

    const auto eot = llama_token_eot(model);
    if (eot == -1) return env->NewStringUTF("<|im_end|>");

    std::string piece;
    piece.resize(piece.capacity());
    int n_chars = llama_token_to_piece(model, eot, &piece[0], piece.size(), 0, true);
    if (n_chars < 0) {
        piece.resize(-n_chars);
        int check = llama_token_to_piece(model, eot, &piece[0], piece.size(), 0, true);
        GGML_ASSERT(check == -n_chars);
    } else {
        piece.resize(n_chars);
    }
    return env->NewStringUTF(piece.c_str());
}

// Optional bench API (Kotlin expects it if you declared it)
extern "C"
JNIEXPORT jstring JNICALL
Java_android_llama_cpp_LLamaAndroid_bench_1model(
        JNIEnv *env,
        jobject,
        jlong context_pointer,
        jlong model_pointer,
        jlong batch_pointer,
        jint pp,
        jint tg,
        jint pl,
        jint nr
) {
    const auto ctx   = reinterpret_cast<llama_context *>(context_pointer);
    const auto model = reinterpret_cast<llama_model   *>(model_pointer);
    const auto batch = reinterpret_cast<llama_batch   *>(batch_pointer);

    if (!ctx || !model || !batch) {
        return env->NewStringUTF("bench_model: missing context/model/batch");
    }

    double pp_avg = 0.0, tg_avg = 0.0, pp_std = 0.0, tg_std = 0.0;

    const int n_ctx = llama_n_ctx(ctx);
    LOGi("bench_model: n_ctx=%d", n_ctx);

    for (int r = 0; r < nr; ++r) {
        // prompt processing
        common_batch_clear(*batch);
        for (int i = 0; i < pp; ++i) {
            common_batch_add(*batch, 0, i, {0}, false);
        }
        if (batch->n_tokens > 0) batch->logits[batch->n_tokens - 1] = true;

        llama_kv_cache_clear(ctx);
        const auto t_pp_start = ggml_time_us();
        (void) llama_decode(ctx, *batch);
        const auto t_pp_end = ggml_time_us();

        // text generation
        llama_kv_cache_clear(ctx);
        const auto t_tg_start = ggml_time_us();
        for (int i = 0; i < tg; ++i) {
            common_batch_clear(*batch);
            for (int j = 0; j < pl; ++j) {
                common_batch_add(*batch, 0, i, {j}, true);
            }
            (void) llama_decode(ctx, *batch);
        }
        const auto t_tg_end = ggml_time_us();

        const double t_pp = double(t_pp_end - t_pp_start) / 1e6;
        const double t_tg = double(t_tg_end - t_tg_start) / 1e6;

        const double speed_pp = double(pp) / std::max(1e-9, t_pp);
        const double speed_tg = double(pl * tg) / std::max(1e-9, t_tg);

        pp_avg += speed_pp; tg_avg += speed_tg;
        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;
    }

    pp_avg /= double(std::max(1, nr));
    tg_avg /= double(std::max(1, nr));

    if (nr > 1) {
        pp_std = std::sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = std::sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0.0; tg_std = 0.0;
    }

    char model_desc[128];
    llama_model_desc(model, model_desc, sizeof(model_desc));

    const double model_size_gib     = double(llama_model_size(model)) / 1024.0 / 1024.0 / 1024.0;
    const double model_n_params_b   = double(llama_model_n_params(model)) / 1e9;

    std::stringstream ss;
    ss << std::setprecision(2);
    ss << "| model | size | params | backend | test | t/s |\n";
    ss << "| --- | --- | --- | --- | --- | --- |\n";
    ss << "| " << model_desc << " | " << model_size_gib << "GiB | " << model_n_params_b << "B | (Android) | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    ss << "| " << model_desc << " | " << model_size_gib << "GiB | " << model_n_params_b << "B | (Android) | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";

    return env->NewStringUTF(ss.str().c_str());
}
