// Adaptado de OfflineLLM (jegly/OfflineLLM, Apache-2.0), a su vez basado en
// SmolChat-Android (shubham0204) — ver docs/referencias/REFERENCIA_OFFLINELLM.md y
// docs/ia-local/LLAMA_CPP_EMBEBIDO.md. Lógica de inferencia sin cambios sustanciales
// respecto al original; adaptado para el módulo llama-engine de Kairos.
//
// Copyright original: jegly/OfflineLLM contributors (Apache License 2.0)
// https://github.com/jegly/OfflineLLM

#ifndef LLMINFERENCE_H
#define LLMINFERENCE_H

#include "llama.h"
#include "ggml.h"
#include <string>
#include <vector>

class LLMInference {
public:
    void loadModel(const char *model_path, float minP, float temperature, float topP, int topK,
                   float repeatPenalty, bool storeChats, long contextSize, const char *chatTemplate,
                   int nThreads, bool useMmap, bool useMlock, int nGpuLayers = 0,
                   int nThreadsBatch = -1, bool kvCacheQ8 = false);
    void addChatMessage(const char *message, const char *role);
    float getResponseGenerationTime() const;
    int getContextSizeUsed() const;
    void startCompletion(const char *query);
    std::string completionLoop();
    void stopCompletion();
    std::string benchModel(int pp, int tg, int pl, int nr);
    ~LLMInference();

private:
    llama_model *_model = nullptr;
    llama_context *_ctx = nullptr;
    llama_sampler *_sampler = nullptr;
    llama_batch _batch = {};
    llama_token _currToken;

    std::vector<llama_chat_message> _messages;
    std::vector<char> _formattedMessages;
    std::vector<llama_token> _promptTokens;
    std::string _response;
    std::string _cacheResponseTokens;
    const char *_chatTemplate = nullptr;
    bool _storeChats = true;
    std::string _assistantRole = "assistant";
    // Longitud del prefijo de conversación templateado ya alimentado a la
    // KV cache. Cada turno solo tokeniza/decodea formatted[_prevLen..new_len),
    // en vez de re-alimentar todo el historial (que crecía cuadráticamente y
    // duplicaba KV).
    size_t _prevLen = 0;

    int64_t _responseGenerationTime = 0;
    int _responseNumTokens = 0;
    int _nCtxUsed = 0;

    llama_batch g_batch;

    static bool _isValidUtf8(const char *response);
    void _updatePrevLen();

    // Última línea WARN/ERROR emitida por el logger interno de ggml/llama.cpp,
    // capturada vía llama_log_set() — así la razón real de por qué
    // llama_model_load_from_file()/llama_init_from_model() devolvió null
    // (arquitectura no soportada, archivo corrupto, falla de alloc, etc.)
    // puede mostrarse en el mensaje de excepción en vez de un genérico
    // "loadModel() failed" sin detalle.
    static std::string _lastErrorLog;
    static void _logCallback(ggml_log_level level, const char *text, void *userData);
};

#endif // LLMINFERENCE_H
