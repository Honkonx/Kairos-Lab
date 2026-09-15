# Reusable UI components

Inventory of the shared helpers that avoid reimplementing the same control on every module
screen.

## `BaseModuleFragment` — helpers shared by module screens

Base class that most module screens (Ollama, n8n, Claude Code, etc.) extend — its helpers are
available directly without reimplementing anything.

### `createActionButton(text, style, onClick): View`

Generic action button, 3 styles:

- **`PRIMARY`**: rounded-corner background, blue accent fill at 14% opacity plus a border at
  43%, ripple effect, bold blue text.
- **`DANGER`**: subdued red background, red accent text.
- **`GHOST`** (default): neutral background, standard text — the palette's "neutral" button.

### `pill(text, isActive): View`

Small status badge (pill) — subdued green background if active, matching text. Used to show a
short status (e.g. "● Running"/"○ Stopped") next to a section title, not as an interactive
button.

### `switchRow(label, initialOn, onToggled): SwitchRow`

Row with a switch on the right — the simplest pattern, a single labeled toggle.

### `dropdownRow(label, options, initialIndex, onOptionChosen): DropdownRow`

Row that opens a dropdown menu with options — no switch, just selection among several
alternatives.

### `dropdownSwitchRow(label, options, initialIndex, initialOn, onOptionChosen, onSwitchToggled): DropdownSwitchRow`

Combination of the two above — an options dropdown plus a switch in the same row (for example
"Target: Native/Distro/Container" with an on/off switch). The most complete component, used when
a module needs to pick a variant and toggle it on/off in the same control.

### `setupTabs(tabNames, parent, tabMode): TabbedSectionsBuilder`

Helper for screens with category tabs (`TabLayout` plus per-tab content). Rebuilds the active
tab's content on every switch instead of keeping all views alive simultaneously — simpler to
maintain for most cases.

**Real signature:**

```kotlin
protected fun setupTabs(
    tabNames: List<String>,
    parent: LinearLayout = container,
    tabMode: Int = TabLayout.MODE_SCROLLABLE
): TabbedSectionsBuilder

protected inner class TabbedSectionsBuilder {
    fun tab(index: Int, block: (LinearLayout) -> Unit): TabbedSectionsBuilder
    fun build(initialIndex: Int = 0): TabbedSections
}

protected inner class TabbedSections {
    val activeIndex: Int
    fun render(index: Int)       // clears the content and rebuilds tab [index]
    fun renderActive()           // shortcut: render(activeIndex) — state refreshes
}
```

**Usage:**

```kotlin
setupTabs(listOf("Engines", "SQLite", "Backups"), tabMode = TabLayout.MODE_FIXED)
    .tab(0) { parent -> buildEnginesTab(parent) }
    .tab(1) { parent -> buildSqliteTab(parent) }
    .tab(2) { parent -> buildBackupsTab(parent) }
    .build()
```

`addCard(title, parent, block)` accepts an optional `parent` (defaults to the main container),
which lets you add cards inside each tab's own container instead of always in the root
container.

For screens with a lot of live state interleaved across tabs (status polling, switches that
update from a background thread), there is also an alternative pattern that builds all tabs once
and toggles their visibility instead of rebuilding them — useful when rebuilding the whole view
on every tab switch would be more fragile than just hiding it.

#### Grouping consolidated modules into tabs

The shared base of the "Languages" and "Packages" screens (grouped development tools) supports
an optional hook that, when a subclass implements it, groups its items into real tabs instead of
a single card. For example, the Packages screen groups its npm tools into "Formatting and
linting" / "Servers and tunnels" / "Deploy and frameworks".

## `InlineThemePicker`

Standalone component (independent of `BaseModuleFragment`) that replaces the classic
modal-dialog-with-option-list pattern with an anchored popup menu that applies the change on
tap, with no modal dialog. It takes a generic list of options, decoupled from either of the two
theme systems — so the same component serves both the main panel's theme picker and the
integrated IDE's theme picker ("one component, two instances").

## Opening a TUI in a chosen folder

Another standalone `Fragment` extension (same approach as `InlineThemePicker` — it doesn't live
inside `BaseModuleFragment`) that, before opening a CLI's terminal, shows a 2-option dialog:
"Default folder (~)" or "Choose folder…". If the user picks the second option, it reuses the same
folder browser from the Files screen (Termux home + internal storage) and starts the command by
prepending `cd '<folder>' &&`, instead of always starting in the home directory.

Not every module with its own terminal uses this helper: some (Claude Code, Codex CLI, Antigravity
CLI, OpenClaw, Hermes) solve the same problem with two separate buttons on their own screen — one
for the default folder and one to pick an already-imported project — instead of a single 2-option
dialog. Both patterns coexist in the app and reach the same functional result.
