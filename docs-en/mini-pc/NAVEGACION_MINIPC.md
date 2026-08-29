# Navigation design: the "More" section

When Kairos's bottom navigation reached its practical limit of visible items, the remaining
options (including, at the time, direct X11 server controls) were grouped under a "More"
section. Three different navigation patterns were evaluated for that section before deciding
which one to implement.

## Patterns evaluated

1. **Grid bottom sheet** (adopted) — the "More" section moves from a full list screen to a
   sliding sheet with options laid out in a grid, without introducing a new navigation pattern
   or touching the existing bottom menu. This is the lowest-risk change of the three: it reuses a
   component already familiar elsewhere in the app.
2. **Classic drawer (side menu)** — a hamburger-style side menu with search and categories
   (Main / Tools / System). This is the most recognizable pattern within the Android ecosystem
   and is documented as the natural candidate if the "More" section grows enough that a grid
   stops being sufficient.
3. **Persistent side navigation rail** — a collapsible/expandable vertical bar on the edge of the
   screen. Evaluated and set aside for now: it's a less common pattern on Android phones, with
   more of a learning curve for a benefit the bottom sheet already covers adequately.

## Decision

The grid bottom sheet was implemented as the lowest-change option. In addition, the X11 server
and VNC viewer controls were relocated into the Mini PC tab (see `MINIPC_TAB.md`), further
reducing what the "More" section needs to cover. The classic drawer and
the side rail remain documented as already-evaluated design alternatives, not as pending work —
they would only be revisited if the "More" section grows significantly again.
