// Puente JNI — adaptado de OfflineLLM (jegly/OfflineLLM, Apache-2.0,
// smollm.cpp original) para el paquete/clase de Kairos
// (com.termux.llm.LlamaEngine). Ver LLMInference.h para la nota de
// atribución completa.

#include "LLMInference.h"
#include "ggml-backend.h"
#include <jni.h>
#include <atomic>
#include <string>

// Carga los plugins de backend de ggml (libggml-cpu-android_*.so + libggml-vulkan.so)
// desde el nativeLibraryDir de la app. ggml puntúa cada variante de CPU
// contra las capacidades reales del dispositivo (dotprod/fp16/i8mm/SVE...)
// y se queda con la mejor. Idempotente.
extern "C" JNIEXPORT void JNICALL
Java_com_termux_llm_LlamaEngine_initBackends(JNIEnv* env, jobject thiz, jstring nativeLibDir) {
    static std::atomic_bool initialized{false};
    if (initialized.exchange(true)) {
        return;
    }
    jboolean isCopy = true;
    const char* dirCstr = env->GetStringUTFChars(nativeLibDir, &isCopy);
    if (dirCstr != nullptr && dirCstr[0] != '\0') {
        ggml_backend_load_all_from_path(dirCstr);
    } else {
        ggml_backend_load_all();
    }
    env->ReleaseStringUTFChars(nativeLibDir, dirCstr);
}

// Devuelve la descripción del primer backend de ggml de tipo GPU (ej. el
// nombre del dispositivo Vulkan, tipo "Adreno (TM) 640"), o "" si no hay
// ningún backend GPU registrado — sea porque no se compiló, sea porque
// falló la inicialización de Vulkan en este dispositivo.
extern "C" JNIEXPORT jstring JNICALL
Java_com_termux_llm_LlamaEngine_getGpuDeviceName(JNIEnv* env, jobject thiz) {
    std::string result;
    try {
        for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);
            if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
                const char* desc = ggml_backend_dev_description(dev);
                if (desc != nullptr) {
                    result = desc;
                }
                break;
            }
        }
    } catch (...) {
        result.clear();
    }
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_termux_llm_LlamaEngine_loadModel(JNIEnv* env, jobject thiz, jstring modelPath, jfloat minP,
                                            jfloat temperature, jfloat topP, jint topK, jfloat repeatPenalty,
                                            jboolean storeChats, jlong contextSize,
                                            jstring chatTemplate, jint nThreads, jboolean useMmap, jboolean useMlock,
                                            jint nGpuLayers, jint nThreadsBatch, jboolean kvCacheQ8) {
    jboolean    isCopy           = true;
    const char* modelPathCstr    = env->GetStringUTFChars(modelPath, &isCopy);
    auto*       llmInference     = new LLMInference();
    const char* chatTemplateCstr = env->GetStringUTFChars(chatTemplate, &isCopy);

    try {
        llmInference->loadModel(modelPathCstr, minP, temperature, topP, topK, repeatPenalty,
                                storeChats, contextSize, chatTemplateCstr, nThreads, useMmap, useMlock,
                                nGpuLayers, nThreadsBatch, kvCacheQ8);
    } catch (std::exception& error) {
        env->ReleaseStringUTFChars(modelPath, modelPathCstr);
        env->ReleaseStringUTFChars(chatTemplate, chatTemplateCstr);
        delete llmInference;
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return 0;
    }

    env->ReleaseStringUTFChars(modelPath, modelPathCstr);
    env->ReleaseStringUTFChars(chatTemplate, chatTemplateCstr);
    return reinterpret_cast<jlong>(llmInference);
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_llm_LlamaEngine_addChatMessage(JNIEnv* env, jobject thiz, jlong modelPtr, jstring message,
                                                 jstring role) {
    jboolean    isCopy       = true;
    const char* messageCstr  = env->GetStringUTFChars(message, &isCopy);
    const char* roleCstr     = env->GetStringUTFChars(role, &isCopy);
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    llmInference->addChatMessage(messageCstr, roleCstr);
    env->ReleaseStringUTFChars(message, messageCstr);
    env->ReleaseStringUTFChars(role, roleCstr);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_termux_llm_LlamaEngine_getResponseGenerationSpeed(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    return llmInference->getResponseGenerationTime();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_llm_LlamaEngine_getContextSizeUsed(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    return llmInference->getContextSizeUsed();
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_llm_LlamaEngine_close(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    delete llmInference;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_llm_LlamaEngine_startCompletion(JNIEnv* env, jobject thiz, jlong modelPtr, jstring prompt) {
    jboolean    isCopy       = true;
    const char* promptCstr   = env->GetStringUTFChars(prompt, &isCopy);
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    try {
        llmInference->startCompletion(promptCstr);
    } catch (std::exception& error) {
        env->ReleaseStringUTFChars(prompt, promptCstr);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return;
    }
    env->ReleaseStringUTFChars(prompt, promptCstr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_termux_llm_LlamaEngine_completionLoop(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    try {
        std::string response = llmInference->completionLoop();
        return env->NewStringUTF(response.c_str());
    } catch (std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_llm_LlamaEngine_stopCompletion(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    llmInference->stopCompletion();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_termux_llm_LlamaEngine_benchModel(JNIEnv* env, jobject /*unused*/, jlong modelPtr, jint pp, jint tg, jint pl,
                                             jint nr) {
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    std::string result       = llmInference->benchModel(pp, tg, pl, nr);
    return env->NewStringUTF(result.c_str());
}
