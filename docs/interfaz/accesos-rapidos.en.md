# Quick access outside the app

**Native Kairos feature** — two Android mechanisms to turn a favorite module on/off or open it
without opening the app first. Before this, the only path was opening Kairos, going to Modules,
and navigating to the right screen every single time.

---

**App:** Kairos (termux-app fork)
**Shared logic:** `QuickAccessPrefs.kt`

---

## 1. App shortcuts (long-press on the launcher icon)

Tapping the star (☆ → ★) that appears in the header of any module screen makes that module
available as a shortcut when long-pressing the Kairos icon on the launcher — up to 4 favorites
at once. Tapping a shortcut opens Kairos straight to that module's screen, using the same
navigation path as tapping the row by hand from the module list.

If a favorite module gets uninstalled or stops existing, its shortcut is cleaned up automatically
on the app's next launch, without affecting the rest of the favorites.

**Limitation in this first version:** the shortcut only navigates to the module's screen — it
doesn't turn it on/off by itself. Turning it on/off is still a single tap once inside the screen.

## 2. Quick Settings tile

A real tile in Android's Quick Settings panel (the one that slides down from the top of the
screen) that shows whether a chosen module is active or not, and turns it on/off with a single
tap — without opening the app at all. Which module it controls is picked from Settings → "Quick
access".

**Limitation in this first version:** one fixed tile, controlling one module at a time (Android
doesn't allow dynamic per-module tiles without declaring each one separately) — to control a
different module you go back to Settings and change the selection.

## 3. What's left for later

- A distinct icon per module in the shortcuts (today they all share a generic icon).
- Turning a module on/off directly from the launcher shortcut, without going through its screen.
- More than one simultaneous Quick Settings tile.
