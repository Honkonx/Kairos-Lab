# GPU Acceleration in Mini PC

GPU acceleration is what makes a graphical desktop inside Kairos (native or with distro) feel
usable instead of slow — without it, all rendering falls back to pure software. This document
summarizes how Kairos detects and configures the GPU for Mini PC; the full detail of
installation checkpoints lives in `../modulos/entorno.en.md` ("GPU detection and driver
options" section) — this document covers the architectural level: why a different driver exists
per manufacturer and how it's applied to the desktop.

## 1. Automatic detection by SoC

Kairos identifies the manufacturer of the device's graphics chip (Adreno from Qualcomm, Mali
from MediaTek, Xclipse from Samsung Exynos, or generic if the model isn't recognized) and
installs the matching Mesa driver automatically, without the user needing to know which GPU
their phone has. Full table of SoCs, packages and default method: `../modulos/entorno.en.md`
section 4.

| Manufacturer | Default acceleration method |
|---|---|
| Adreno (Qualcomm) | Zink (a Gallium software-GL-over-Vulkan driver) — Turnip (native Vulkan) available as a configurable alternative |
| Mali (MediaTek) | VirGL + ANGLE |
| Xclipse (Samsung Exynos) | VirGL |
| Generic / unrecognized | Software rendering (llvmpipe) |

## 2. How it's applied to the desktop

The chosen method (automatic or manual) translates into Mesa environment variables
(`GALLIUM_DRIVER`, `MESA_GL_VERSION_OVERRIDE`, and other method-specific ones) that are exported
before launching any graphical application — both on the native path and inside a distro
(`proot-distro`), where the same variables are forwarded into the distro's login environment.

## 3. Manual configuration and diagnostics

The user can change the GPU method manually at any time from the app, without reinstalling
anything — useful when the automatic method doesn't give the best result on a particular device.
The app also includes real GPU diagnostics (not just the configured method): it reports the
effective OpenGL renderer and the detected Vulkan device, using real tools (`glxinfo`/`glxgears`)
installed alongside the rest of the GPU packages.

## See also

- `../modulos/entorno.en.md` — full GPU detection table, packages installed per manufacturer,
  and screen controls.
- `arquitectura-mini-pc.en.md` — how GPU acceleration coordinates with the X11 server and
  proot-distro within Mini PC's overall architecture.
