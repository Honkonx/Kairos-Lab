# AUDITORIA_ESTUDIO_PROFUNDA_2026-08-19.md — Real bugs found and fixed in Studio

This document summarizes an in-depth code review of Studio's module (code editor, file tree, Git
panel, search, keyboard, command palette, settings, and AI integration), focused on finding real
logic bugs, not just style issues.

## Fixed

### Duplicate rows in the file tree from double-tapping during load

**The bug**: expanding a folder that had never been loaded before triggers a background content
load (potentially slow, since it involves real I/O against the file provider). While that load
was in progress, there was no state reflecting "load in progress" — if the user tapped the same
folder a second time before the first load finished (double tap, a folder with many files, a slow
device), a **second**, concurrent load would fire. When both loads finished, every file/subfolder
ended up visually duplicated in the tree (the real filesystem was unaffected, only the on-screen
list).

**The fix**: an explicit "load in progress" state was added per tree node, blocking any further
tap on the same folder while the first load is still pending. A second tap during loading now
does nothing, instead of triggering a duplicate load.

### Filenames with Unicode characters showed up as octal-escaped in the Git panel

**The bug**: by Git's default configuration, any filename with "unusual" non-ASCII characters
(accents, ñ, non-Latin characters) is returned in `git status --porcelain` output quoted and
octal-escaped byte by byte — for example, a file named `café.txt` literally appeared as
`"caf\303\251.txt"`. Studio's Git panel didn't unescape that format, so the user saw the filename
replaced by that raw escape sequence. Filenames with spaces weren't affected — Git doesn't treat
those as "unusual" by default.

**The fix**: the `core.quotePath=false` option is now forced on the Git status command Studio
uses, so Git returns filenames as raw UTF-8, with no quoting or escaping.

### Files in a merge conflict were labeled "Deleted"/"New"/"Changed" instead of "Conflict"

**The bug**: the function that translates `git status --porcelain` status codes into a readable
label had no branch at all for the status combinations Git reports during a merge/rebase with
real conflicts — for example, when both sides of a merge deleted the same file, or when both
sides added it. Since labels were evaluated in order and the first match won, those cases fell
into generic branches and showed actively misleading labels ("Deleted", "New"), suggesting the
file was already resolved when it actually required user intervention to resolve the conflict.

**The fix**: an explicit **"Conflict"** label was added for the seven real "unmerged" status
combinations Git reports, evaluated before any other branch.

## Documented, then fixed

### Closing a tab with unsaved changes discarded them with no warning at all

This was the most serious finding of the review. Closing a tab (via the "✕" button or the
corresponding keyboard shortcut) removed the file from the list of open tabs without checking
whether it had unsaved changes — the in-memory content was lost with no confirmation dialog and
no auto-save. On top of that, the active tab's "unsaved changes" indicator was only recalculated
when switching tabs or right before saving, never when closing a tab directly — so even a simple
check of that indicator could give a false negative if the user typed and closed the same tab
without switching focus first.

**The fix applied**: when closing a tab, the unsaved-changes indicator is first synced reliably
(if it's the active tab); if there are unsaved changes, a dialog with three options is shown —
"Save and close", "Close without saving", "Cancel" — before removing the tab. If there are no
unsaved changes, it closes directly, same as before. Both the close button and the keyboard
shortcut go through the same code path, so the fix covers both cases without duplicating logic.

### A "locked" keyboard modifier could stay active indefinitely

The virtual keyboard bar lets you lock a modifier (Ctrl/Alt/Shift) with a long press, so you don't
have to hold it down for every combination. That lock only released if the user tapped it again
manually — there was no point in the screen's lifecycle that reset it automatically. If the user
locked a modifier and navigated to another screen (Git panel, build, settings) without noticing it
was still active, the next key they tapped could combine with that "phantom" modifier and trigger
a shortcut instead of typing the expected character.

**The fix applied**: locked modifiers now reset automatically when the app goes to the background
and when a new project is opened — both are points where "starting fresh" is already the expected
behavior of the rest of the interface. They deliberately do not reset just from switching tabs or
from returning to the foreground, so as not to break the legitimate use case of keeping a
modifier locked while reviewing several files in a row.

## Documented, pending a broader design decision

These findings don't represent corrupted data or broken behavior, but they identify real
improvements that require a product decision before being implemented:

- **Project-wide search**: the safety limits (result cap, file-size cap) cut off cleanly, without
  corrupting results, but don't stop the file-tree traversal or the current file's read early once
  the cap is reached — on a very large project, this still wastes extra time after the limit is
  hit. Files of unknown size (typical of some cloud storage providers) are silently excluded from
  search, with no notice to the user.
- **File tree**: folder-read operations aren't wrapped in error handling for the real possibility
  that folder-access permission gets revoked mid-session — this could interrupt the loading thread
  with no clear notice to the user. Fixing it properly requires deciding what message to show and
  whether the project should be automatically removed from the recent list.

## Confirmed with no findings

The review also confirmed, without finding bugs:

- Every command registered in the command palette points to real, existing functionality — none
  are placeholders.
- The AI client correctly distinguishes "no local engine is responding" from "the local engine
  responded but returned an error", with no ambiguity in the retry logic between providers.
- Editor settings (font size, tab size) don't allow out-of-range values to be saved through the
  UI.
