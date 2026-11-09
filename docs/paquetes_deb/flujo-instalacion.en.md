# `.deb` installation flow (same for GitHub, local and APK-created)

Common entry point: `ModuleDebInstaller.installFromDeb(debFile, expectedSha256?, confirm, onProgress)`.
It is always called from a background thread.

```
 origin ──► [0] file hash (only if there is a trust channel)
            [1] moduledeb inspect   → reads ONLY manifest.json + SHA256SUMS.txt
            [2] confirmation         → only if there is NO external hash (local package)
            [3] moduledeb extract    → control first, then payload; everything against the .txt
            [4] moduledeb verify     → compares with the system (requirements, files)
            [5] resolve requirements → download what is missing (pkg) and re-verify
            [6] moduledeb apply      → copy what is declared, verify_cmd / patch_cmd
```

## [0] File hash

GitHub only: the `.deb` is downloaded to a **private** app folder
(`~/kairos_local/module_deb_downloads/`, not the public `Download/kairos/` folder, so another app cannot
replace it between download and check) and its sha256 is compared with the one published in the Release
(`catalog.json` or `SHA256SUMS.txt`). If it does not match, it is deleted and the install aborts. If the Release
**does not publish** the hash, it is not installed.

## [1] `moduledeb inspect` — the first thing read

`dpkg-deb -e` extracts **only the control area**; the payload is not touched and nothing from the package is
executed. It checks (rc 2 = do not install, with the reasons):

- `DEBIAN/manifest.json` **and** `DEBIAN/SHA256SUMS.txt` exist;
- the manifest is valid JSON and carries `kairos_package:true`, `id` and `schema >= 2`;
- the hash of `manifest.json` matches its line in the `.txt`.

It returns a JSON with what the package declares: `id`, `version`, `files_count`, `declared_paths`, `requires`
(each requirement with its `status`: `present`/`missing`/`unknown`) and `runs_commands` (whether it runs `verify_cmd`/
`patch_cmd`, and the `install_hint`s of type `other`).

## [2] Confirmation

A local file has no external hash. The Store shows a dialog with the summary (number of files, missing
requirements, whether it runs verification/repair commands and the warning that there is no hash from a trust
channel). Without "Install" it does not continue. A GitHub origin with a matching hash does not ask.

## [3] `moduledeb extract`

1. Extracts the control area to a temporary directory and repeats the check from step 1.
2. Only then extracts the payload and requires it to be **exactly** what the `.txt` lists (same files,
   same hashes, same symbolic links) and each path to comply with the policy in [`formato-v2.en.md`](formato-v2.en.md).
3. Leaves a `.kairos_checked` marker; `apply` refuses to work on a directory without it.

Any failure deletes the temporary directory and aborts with the reason.

## [4]–[5] Compare with the system and resolve requirements

`verify` evaluates each `check_cmd` and compares files (`install` / `overwrite` / `skip_identical`). If
requirements are missing:

- `kind: package` → `pkg install -y <names>` (validated names, no shell);
- `kind: other` → its `install_hint`, only because confirmation or a verified origin already happened.

It verifies again. If they are still missing, it aborts and nothing is copied.

## [6] `moduledeb apply`

Copies the payload (already verified, therefore only what is declared) to its real path and runs `verify_cmd`; if it fails,
`patch_cmd` and verifies again. Emits the `moduledeb:apply_done` event.

## What still depends on trusting the author

`verify_cmd`, `patch_cmd` and `check_cmd` are executed with `eval`/`bash -c`: they are manifest commands. With a
hash published by the Release the risk is that of trusting the repo; with a local file, the dialog in step 2 is
the only barrier. This is a known design limitation (modules need, e.g., `patchelf`).
