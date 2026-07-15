/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
#include <jni.h>
#include <android/log.h>
#include <whisper.h>

#include <string>
#include <vector>

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_khateev_medvoice_speech_WhisperEngine_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);
    if (ctx == nullptr) {
        LOGE("Failed to load whisper model");
        return 0;
    }
    LOGI("Whisper model loaded");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_khateev_medvoice_speech_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jobject /*thiz*/, jlong ctx_ptr, jfloatArray pcm,
        jstring prompt, jint n_threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    if (ctx == nullptr) return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(pcm);
    std::vector<float> audio(static_cast<size_t>(n));
    env->GetFloatArrayRegion(pcm, 0, n, audio.data());

    const char *c_prompt = env->GetStringUTFChars(prompt, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "en";
    params.translate = false;
    params.n_threads = n_threads;
    // Whisper always encodes a 30 s window; for short dictated commands, limiting
    // the encoder context to the actual audio length gives a large speedup
    // (same trick as whisper.cpp's realtime stream example). 1500 = full window.
    // The +128 margin matters: too tight a context makes the decoder loop.
    const int audio_ctx = static_cast<int>(n / 320) + 128;
    params.audio_ctx = audio_ctx < 1500 ? (audio_ctx < 192 ? 192 : audio_ctx) : 1500;
    params.no_context = true;
    params.single_segment = true;   // dictated commands are one utterance
    params.suppress_blank = true;
    params.initial_prompt = c_prompt;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;

    std::string result;
    if (whisper_full(ctx, params, audio.data(), n) == 0) {
        const int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; ++i) {
            result += whisper_full_get_segment_text(ctx, i);
        }
    } else {
        LOGE("whisper_full failed");
    }

    env->ReleaseStringUTFChars(prompt, c_prompt);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_khateev_medvoice_speech_WhisperEngine_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ctx_ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    if (ctx != nullptr) whisper_free(ctx);
}
