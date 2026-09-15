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
#include "mtmd.h"
#include <string>
#include <vector>

// Soporte multimodal (imagen+texto) — ver LLMInference.cpp para el detalle completo de
// diseño/limitaciones. Investigación 2026-09-15 confirmó que llama.cpp mainline (el tag
// vendorizado por Kairos, ver ../../../build.gradle) ya trae libmtmd, la librería real detrás
// de `llama-mtmd-cli`/`llama-server --mmproj`, no un fork experimental — y que ya se compila
// como parte del árbol de tools/ existente (dependencia dura de llama-server, que Kairos ya
// construye). Este es el primer paso real, acotado: cargar un mmproj + procesar UNA imagen
// por turno. NO implementado (documentado como fuera de alcance de este paso, no omisión
// silenciosa): caching incremental de KV-cache en turnos CON imagen (ver
// startCompletionWithImage) y audio/video (mtmd también los soporta, pero Kairos no tiene
// ningún flujo de UI para adjuntarlos todavía).
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

    // Carga el vision projector (mmproj) para el modelo YA cargado por loadModel() — mtmd_init_from_file
    // necesita el llama_model* real, así que solo puede llamarse después de un loadModel() exitoso.
    // Lanza std::runtime_error si el modelo no está cargado o si mtmd_init_from_file() falla (mmproj
    // incompatible con el modelo de texto, archivo corrupto, etc.).
    void loadMultimodalProjector(const char *mmprojPath, bool useGpu);
    bool supportsVision() const;

    // Variante de startCompletion() que procesa una imagen junto con el prompt de este turno —
    // requiere loadMultimodalProjector() ya exitoso. A diferencia de startCompletion() (que
    // reusa la KV-cache incremental entre turnos, ver _prevLen), un turno CON imagen siempre
    // limpia y reprocesa la conversación completa (mismo mecanismo que _storeChats=false) — ver
    // el comentario de la implementación para el porqué. Lanza std::runtime_error si no hay
    // mtmd cargado, la imagen no se puede decodificar, o falla la codificación/decodificación.
    void startCompletionWithImage(const char *query, const unsigned char *imageBytes, size_t imageLen);

    ~LLMInference();

private:
    llama_model *_model = nullptr;
    llama_context *_ctx = nullptr;
    llama_sampler *_sampler = nullptr;
    llama_batch _batch = {};
    llama_token _currToken;
    mtmd_context *_mtmdCtx = nullptr;
    int _nThreads = 4;
    // true durante exactamente la primera llamada a completionLoop() que sigue a un
    // startCompletionWithImage() exitoso — mtmd_helper_eval_chunks() ya decodificó el prompt
    // completo (texto+imagen) y dejó los logits del último token listos, así que esa primera
    // vuelta del loop debe samplear directo en vez de repetir un llama_decode(_batch) redundante
    // (que además fallaría: _batch nunca se llenó con nada en el camino de imagen).
    bool _skipNextDecode = false;

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
