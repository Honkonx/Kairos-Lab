# Vulkan, memory and local AI Chat crashes (2026-10-09)

> Investigation done on a real device (Xiaomi 2109119DG, Android 15, 7.4 GB of RAM, Adreno 642L GPU).
> Everything marked **[measured]** comes from logs of that device; anything marked **[not verified]** was not tested.

## Reported symptoms

1. Opening the AI Chat with the local engine (embedded llama.cpp), the app closed, sometimes with red text.
2. With the preference set to CPU it worked.
3. It also happened with official Ollama → the device/Vulkan driver was suspected.

## Findings (three different problems)

### 1. 516 MiB memory reservation due to Gemma's vocabulary — **our own, fixed** [measured]

`llama_context_params.n_outputs_max = 0` (the JNI wrapper's default value) makes llama.cpp reserve, as a worst case,
as many outputs as the batch (`n_ubatch = 512`). With Gemma 3 (vocabulary of 262,144 tokens) that is
512 × 262,144 × 4 bytes ≈ **516 MiB of pinned memory** (`Vulkan_Host compute buffer size = 516.50 MiB`) right when
creating the context. On the 7.4 GB phone with other apps open, Android declared `mem-pressure-event` at that
same instant and killed processes (including the app).

- The module's `llama-server`, with the same model and `-ub 512`, reserved **12 MiB** because it sets that limit
  (`tools/server/server-context.cpp`).
- Fix: `ctx_params.n_outputs_max = 1` in `llama-engine/src/main/cpp/LLMInference.cpp` (the engine only reads the
  logits of the last token). Result [measured]: `Vulkan_Host compute buffer size = 8.52 MiB`, 0 memory-pressure events.
- The field also exists in the previous version (b10194): **going back to the old version does not fix this**, the parameter does.

### 2. Race condition when switching screens — **our own, fixed** [measured]

`SIGSEGV` in `ggml_compute_forward_glu` (CPU backend) inside `completionLoop`. Cause: `ChatFragment.onDestroyView`
closed the engine (`~llama_context`) on the main thread while the generation thread was still inside `llama_decode`
(`Thread.interrupt()` does not stop native code). Any tab switch in the middle of a response killed the app.

- Fix: `LlamaEngine` serializes native calls with a lock and `close()` **waits** for the step in progress; token
  callbacks run outside the lock; `onDestroyView` closes on a separate thread.
- Verified: switching tabs 4 s into a response → same PID, no fatal signal.

### 3. Vulkan driver failure on the Adreno 642L — **device-specific, mitigated** [measured]

Error shown in red: `vk::Device::createComputePipeline: ErrorUnknown` when generating with all 27 layers on GPU. It is the
driver that refuses to compile a shader. It was observed that a short message ("hola") produced text on the GPU (the response came out truncated,
"…¿En qué puedo ayudart") and that another ("di hola en una frase") failed with this error; **[unverified hypothesis]**: each prompt shape may need a
different shader variant and only some fail on this driver. The app does not close; what was missing was handling it.

- Mitigation (`ChatFragment` + `GpuBackend.PREF_VULKAN_DRIVER_FAILED`): if the error contains `vk::`/Vulkan in the middle of
  inference, it records that the driver failed on this device and, if no text has been shown yet, **retries on CPU only**.
  Manually choosing a backend in Local AI → Parameters clears that flag.
- **[not verified]** whether the failure disappears with another llama.cpp version or other Vulkan parameters (see the dedicated section).

## Two llama.cpp versions

| | Version | Status |
|---|---|---|
| Default | `e1a1abb` (b10194, 2026-07-30) + patch `llama-engine/patches/kairos-tools-cmake-b10194.patch` | In the APK |
| New | `6184e92` (upstream 2026-10-09) | Tag `kairos-new-6184e92` in the vendor clone + `llama-engine/patches/jni-wrapper-api-6184e92.patch` (2 wrapper signatures) |

Measured distance: **1,338 commits and 300 files** between them in ~10 weeks (≈19 commits/day); upstream changed the
versioning scheme (no longer `bNNNN`, now `0.6.0-dev`). The new one builds with Vulkan and loads Gemma on the Adreno 642L, but it was **not**
tested whether it fixes the pipeline error: the same Vulkan failure was reported with it. Among its changes are
Vulkan fixes for Adreno (`argsort`, KHR cooperative matrices) and for Samsung/Intel/AMD GPUs.
Being able to choose between the two inside the app requires packaging two sets of libraries with different names
(both would use `libllama.so`/`libggml*.so`); left for the dedicated section.

Engine context: [`llama-cpp-local-engine.md`](llama-cpp-local-engine.md).

## Dedicated "Vulkan Compatibility" section (pending)

Ideas, all **not implemented**: choose the llama.cpp version (previous/new); a list of devices/GPUs with their known
configuration (layers on GPU, `n_ubatch`, flash-attention, cooperative matrices); a Vulkan smoke test
on first use that decides CPU/GPU per device; collect `vk::` errors with model, prompt and driver to patch.

## How it was diagnosed (to repeat it)

`adb logcat -b all` filtering `[ggml]`: lines `sched_reserve: Vulkan_Host compute buffer size`, `offloaded N/M layers`,
`ActivityManager: ... mem-pressure-event`; `adb logcat -b crash` for the SIGSEGV with the native trace; compare with
`llama-server -lv 4` launched by hand from `$PREFIX/lib` (otherwise "no backends are loaded").
