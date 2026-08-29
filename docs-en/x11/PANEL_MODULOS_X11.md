# Module menu inside the graphical desktop

## Goal

Give installed Kairos modules (AI agents, development tools, etc.) a real access point from
inside the embedded X11 graphical desktop — a start menu, similar to the Windows "Start" menu,
rather than just a set of loose icons on the wallpaper. The goal is to be able to launch any
installed module (for instance, a terminal-based AI agent) inside the desktop, while the rest of
the graphical environment keeps working normally (browser open, other windows active), both in
native mode and inside a Linux distro running via proot.

## State before this feature

Kairos already generated `.desktop` files (freedesktop.org format, Desktop Entry Specification)
for installed modules, but only in the `~/Desktop` folder — the folder that `xfdesktop` (XFCE4's
desktop icon manager, and its LXQt/MATE equivalents) scans to draw icons over the wallpaper. This
is the equivalent of the Windows desktop, not the Start menu. Real consequences of this
limitation:

- Modules were only visible if the user could see the wallpaper — with a maximized window
  covering it, there was no way to reach them without minimizing everything.
- There was no native application menu (e.g. the "Applications" button on the XFCE4 panel)
  showing modules grouped by category.
- The `Categories=` field of each `.desktop` file was already being written correctly, but had no
  effect because the file lived in the wrong path for an application menu to read it.

## How XFCE4/LXQt/MATE build their real application menu

All three desktop environments (and virtually any freedesktop.org-compliant environment) follow
the same standard: the **XDG Desktop Menu Specification** plus the **Desktop Entry
Specification**.

- The "Applications" menu is built by scanning `.desktop` files in a fixed set of directories, in
  priority order:
  1. `~/.local/share/applications/` — per-user, no special permissions required.
  2. `/usr/share/applications/` — system-wide, installed by packages.
  3. Other paths from `$XDG_DATA_DIRS`.
- The `Categories=` field decides which submenu/category each entry appears in, following the
  spec's standard categories (`Development`, `Utility`, `System`, `Network`, etc.).
- The menu regenerates automatically when files are added or removed in
  `~/.local/share/applications/` — no need to restart the desktop or run any manual refresh
  command.

## Solution adopted

The `.desktop` file generation Kairos already performed (for CLIs with their own wrapper, for the
generic module catalog, and for applications installed inside a proot distro) was extended to
also write the same content into `~/.local/share/applications/`, in addition to `~/Desktop`. This
is a low-effort extension of a function that already existed and already worked, with no new
dependencies or additional bug surface — it reuses the same `.desktop` content Kairos already knew
how to build. The same mechanism applies equally inside a Linux distro running via proot (the
distro's `$HOME` has its own XDG applications directory, written by the same mechanism Kairos
already uses to enter that distro).

Two more expensive alternatives evaluated in the initial investigation were dropped:

- **A custom native panel/launcher (C/Xlib)**, `dmenu`/`rofi`-style built from scratch — would
  give full design control, but means writing a complete X11 client from the ground up (event
  parsing, drawing with no toolkit, syncing with the module catalog from a separate C binary),
  with new native bug surface and no reusable project as a base. Dropped due to high effort with
  no guarantee of beating the UX the desktop's native menu already offers for free.
- **An embedded HTML/JS panel** — also dropped for the same reason: the desktop's native menu
  already solves the real problem with zero new dependencies.

## Real per-module categorization

Each module in the Kairos catalog has a real category (AI, development, languages, security,
database, system, tools), translated into the corresponding standard XDG categories
(`Utility;Development;`, `Development;`, `System;Network;`, etc.) when the `.desktop` file is
generated. The default application menu that ships with XFCE4 (with no extensions needed) already
groups/filters by those categories in submenus — functionally equivalent to a one-click category
filter, instead of having to scan a long list of ungrouped icons.

GUI applications installed inside a proot distro (freely chosen by the user, not coming from
Kairos's curated module catalog) use a generic category, since there's no curated source to
derive a more specific one from.

## Refreshing the launchers

Launchers are regenerated automatically when the desktop starts, and also via a manual "Update
launchers" action from the graphical environment's configuration screen. A module installed while
the desktop is already open won't appear in the menu until the next regeneration — a known
limitation, with a low-effort future improvement path (triggering regeneration automatically when
a module installation finishes, not only when the desktop opens).

## See also

- [X11_EMBEBIDO.md](X11_EMBEBIDO.md) — the embedded X11 server, the display context this
  mechanism relies on.
