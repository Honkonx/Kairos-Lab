# Kairos as a pocket homelab — roadmap

This document summarizes the vision of Kairos as a "mini PC"/pocket homelab: what typical
homelab needs it already covers, what's missing, and which concrete improvements are proposed to
close that gap.

## Guiding principle

Kairos offers advanced capabilities (containers, Linux distros, network services) as
installable, optional modules, never as a forced mode that changes the face of the whole app. A
user who just wants an AI assistant in the terminal shouldn't feel like the app "is for
homelabs" — it should still feel simple to them. A user who wants to build a complete home
server in their pocket finds everything they need without leaving the app. Every new capability
is integrated as a module with its own switch and screen, not as a global "homelab mode."

## What Kairos already covers

| Homelab need | Status |
|---|---|
| Background services that survive restarts | Covered — persistent sessions + modules with their own switch |
| Secure remote access | Covered — tunnels (Cloudflare/ngrok) with a custom token and domain |
| Monitoring of what's running | Covered at a basic level, with room to improve |
| Backups | Partially covered |
| Persistent databases | Covered — database module (MySQL/Postgres/SQLite) |
| Automation/orchestration | Covered — n8n module |
| Development/testing environments | Covered — stack presets (Python, PHP, React+Vite) |
| Storage shared between host and distros | Missing |
| Remote graphical interface | Covered — embedded X11 + tunnel, VNC as an additional layer |
| Event notifications | Missing |
| One-tap installable app catalog | Covered for Kairos's own modules; missing for apps inside a distro |

The honest takeaway: Kairos already covers most of what a small homelab needs. What's missing is
mostly polish (richer monitoring, notifications, complete backups) plus two concrete new
features — storage shared between the host and distros, and an app catalog installable inside a
distro with automatic graphical launcher generation.

## Why not QEMU or a real Docker daemon

Adding QEMU or a full Docker daemon as a general isolation mechanism was explicitly evaluated
and ruled out: both require a full software CPU emulation layer (Android doesn't expose x86
virtualization acceleration to user apps, and the Android kernel doesn't have native
cgroups/namespaces support for a real Docker daemon either), which means significantly higher
battery, memory, and startup-time overhead than the alternatives Kairos already uses (`udocker`
for containers, `proot-distro` for full Linux distributions), with no real gain for the primary
use case. A niche use case — running full VM images for CTF-style security challenges packaged
as `.vmdk`/`.ova` — could justify a standalone module in the future, but not as the platform's
central mechanism.

## Storage shared between the host and distros

Pattern identified as directly portable: using `proot-distro`'s native flags (`--shared-home`,
`--shared-tmp`) together with automatic detection of external storage (SD cards, mounted USB)
that gets bound automatically in every session, without requiring the user to configure mount
paths by hand every time they enter a distro.

## App catalog inside a distro, with automatic launcher generation

The pattern consists of generating per-distro install commands (`<distro> install <package>`)
that, besides installing the package inside the container, automatically detect the `.desktop`
file it ships with and copy it into the host desktop's application menu — with a wrapper that
makes that launcher enter the right distro and run the command there. An equivalent native
screen would let users list the `.desktop`-capable packages already installed inside the active
distro, with a button to add them to the menu.

## Other proposed improvements

- **Notifications** (for example, via Telegram) — a homelab without an alert for "service X went
  down" is only half a homelab. Identified as the highest-value, lowest-effort item still
  pending.
- **Live install verification** — confirming that an installed binary actually works (not just
  that the install command exited without error) before marking a module as installed, so the
  foundation new features are built on is trustworthy.
- **Enriched monitoring dashboard** — a single panel with RAM/disk status, which modules are
  running, and the state of databases, distros, and active desktops.
- **Full configuration export/import** — a single file with the module registry and each
  module's configuration, for migrating devices or having a complete backup of how the homelab
  is set up.

## Priority table

| Feature | Value | Effort | Risk |
|---|---|---|---|
| Storage shared between host and distros | High | Medium | Low |
| Notifications (Telegram) | High | Low | Low |
| App catalog inside a distro + launcher | High | High | Medium |
| Live install verification | High | Medium | Low |
| Enriched monitoring dashboard | Medium | Medium | Low |
| Full configuration export/import | Medium | Medium | Low |
| QEMU / real Docker as a central mechanism | Low | High | — (not recommended) |
