#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_faithscroll_app_data_Whisper_nativeInit(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return (jlong) ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_com_faithscroll_app_data_Whisper_nativeFree(JNIEnv *, jobject, jlong ctx) {
    if (ctx) whisper_free((whisper_context *) ctx);
}

// Returns "startMs\tendMs\ttext\n" lines as UTF-8 bytes (decoded in Kotlin).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_faithscroll_app_data_Whisper_nativeTranscribe(JNIEnv *env, jobject, jlong ctxp, jfloatArray samples, jstring lang, jint threads, jint audioCtx) {
    auto *ctx = (whisper_context *) ctxp;
    std::string out;
    if (ctx) {
        jsize n = env->GetArrayLength(samples);
        jfloat *data = env->GetFloatArrayElements(samples, nullptr);
        const char *l = env->GetStringUTFChars(lang, nullptr);
        whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        p.print_realtime = false; p.print_progress = false; p.print_timestamps = false; p.print_special = false;
        p.translate = false; p.language = l; p.n_threads = threads; p.no_context = true; p.suppress_blank = true;
        // Speed: one greedy pass (no retries at higher temperature), and only as much of Whisper's
        // 30-second window as the chunk needs (it otherwise pads every chunk to 30 s).
        p.greedy.best_of = 1;
        p.temperature_inc = 0.0f;
        p.audio_ctx = audioCtx;
        if (whisper_full(ctx, p, data, n) == 0) {
            int ns = whisper_full_n_segments(ctx);
            for (int i = 0; i < ns; i++) {
                out += std::to_string(whisper_full_get_segment_t0(ctx, i) * 10) + "\t" +
                       std::to_string(whisper_full_get_segment_t1(ctx, i) * 10) + "\t" +
                       whisper_full_get_segment_text(ctx, i) + "\n";
            }
        }
        env->ReleaseStringUTFChars(lang, l);
        env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    }
    jbyteArray arr = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(arr, 0, (jsize) out.size(), (const jbyte *) out.data());
    return arr;
}

// ---- Parallel use: one loaded model, several independent "states" (one per worker) ----

extern "C" JNIEXPORT jlong JNICALL
Java_com_faithscroll_app_data_Whisper_nativeNewState(JNIEnv *, jobject, jlong ctx) {
    return ctx ? (jlong) whisper_init_state((whisper_context *) ctx) : 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_faithscroll_app_data_Whisper_nativeTranscribeState(JNIEnv *env, jobject, jlong ctxp, jlong statep, jfloatArray samples,
                                                            jstring lang, jint threads, jint audioCtx) {
    auto *ctx = (whisper_context *) ctxp;
    auto *st = (whisper_state *) statep;
    std::string out;
    if (ctx && st) {
        jsize n = env->GetArrayLength(samples);
        jfloat *data = env->GetFloatArrayElements(samples, nullptr);
        const char *l = env->GetStringUTFChars(lang, nullptr);
        whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        p.print_realtime = false; p.print_progress = false; p.print_timestamps = false; p.print_special = false;
        p.translate = false; p.language = l; p.n_threads = threads; p.no_context = true; p.suppress_blank = true;
        p.greedy.best_of = 1; p.temperature_inc = 0.0f; p.audio_ctx = audioCtx;
        if (whisper_full_with_state(ctx, st, p, data, n) == 0) {
            int ns = whisper_full_n_segments_from_state(st);
            for (int i = 0; i < ns; i++) {
                out += std::to_string(whisper_full_get_segment_t0_from_state(st, i) * 10) + "\t" +
                       std::to_string(whisper_full_get_segment_t1_from_state(st, i) * 10) + "\t" +
                       whisper_full_get_segment_text_from_state(st, i) + "\n";
            }
        }
        env->ReleaseStringUTFChars(lang, l);
        env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    }
    jbyteArray arr = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(arr, 0, (jsize) out.size(), (const jbyte *) out.data());
    return arr;
}
