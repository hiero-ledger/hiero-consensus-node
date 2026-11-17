# Local environment for replay investigations

Most lost time in past investigations was environment, not analysis. Check this list before the first long run.

## Common to all platforms

**Tools**
- Java **25** (Temurin is what CI uses: `java -version`). The validator jar targets it.
- `gh` logged in with `repo` scope, with SSO authorized for the `hashgraph` org: `gh auth status` shows the scopes. A classic PAT without scopes can't be refreshed with `gh auth refresh`; use `gh auth login -h github.com -p https -w -s repo`.
- Google Cloud CLI (`gcloud`), logged in interactively: `gcloud auth login <you>@hashgraph.com`. Claude can't complete this step; the user has to paste the verification code. Check it with `gcloud auth print-access-token >/dev/null && echo OK`.
- `python3` and `curl`.

**GCS access**
- Block streams are requester-pays, so downloads need `BILLING_PROJECT`: a GCP project the user's account may bill. **Ask the user which project to use.** Don't copy CI's value or guess one; the user's account may not have access to it, and billing is the user's decision.
- If `gcloud storage` fails with `403 … serviceusage.services.use`, the gcloud config's `billing/quota_project` isn't usable by the user's account. **Ask the user** which project to use as the quota project. Then override it for the run only, not in the gcloud config: `export CLOUDSDK_BILLING_QUOTA_PROJECT=<project the user named>`.

**Disk and memory** (observed, Nov 2026)

| | Snapshot (each) | Blocks in window | Notes |
|---|---|---|---|
| mainnet | ~13 GB | ~8.5k files | origin + target + replay state + applied state: plan for **≥ 100 GB free** |
| previewnet | ~0.3–0.5 GB | ~7.6k files | |

- `replay-pces` used ~8 GB RSS on previewnet. For mainnet, plan for a machine with **≥ 32 GB RAM**. If the JVM runs out of heap, pass `JAVA_TOOL_OPTIONS=-Xmx…` (this is unverified on mainnet-sized runs).
- Raise the open-file limit for the run shell: `ulimit -n 65536`.

**Run hygiene**
- Use **one new directory per run**. The script resolves everything relative to the working directory, including `./<round>` snapshots, `./out`, `./data/saved` and `settingsUsed.txt`.
- Known script traps (see `known-causes.md`):
  - a failed snapshot download leaves an empty `./<round>` that the next run treats as present;
  - the platform's `./data/saved` PCES database survives between runs, which gives "Upper bound must never decrease".

  Before re-running in the same directory, delete only the artifacts *this* run created; check their timestamps.
- `output-record-compare` always exits 0: read its output.
- Long commands (downloads, replay, Gradle) need network and **loopback** sockets. Run them in a Terminal panel tab or the user's own shell so the user can watch and stop them; some sandboxed command runners can't open loopback connections and fail with `Unable to establish loopback connection`.

## macOS (primary)

`validate-replay.sh` uses bash ≥ 4 features and GNU tools. Stock macOS has bash 3.2 and BSD `find`/`mv`, so it fails in step 2/3 without these:

```bash
brew install bash coreutils findutils gh
# Java 25 and the Google Cloud CLI: use your team's usual installer (Homebrew cask, SDKMAN, or vendor installer).
```

Then put the GNU tools first on the PATH **for the run shell** (`scripts/run-replay.sh` does this automatically when Homebrew is present):

```bash
export PATH="$(brew --prefix)/opt/coreutils/libexec/gnubin:$(brew --prefix)/opt/findutils/libexec/gnubin:$(brew --prefix)/bin:$PATH"
bash --version   # must be ≥ 4 (mapfile)
find --version   # must be GNU find (-printf)
```

Notes:
- **APFS is case-insensitive by default.** `out/replay/blockstream` and `out/replay/blockStreams` are the *same* directory on a Mac but different on Linux CI. A directory-name mismatch between the jar and the script can pass locally and fail in CI. Check names with `ls` on CI logs, not by local success.
- `sed -i` needs `sed -i ''` on BSD sed; with gnubin first on the PATH, GNU `sed` is used.
- Apple Silicon: the validator jar runs on arm64 JDKs. If a native library fails to load, note the exact error; it hasn't been seen so far.
- Keep the Mac awake during long runs (`caffeinate -dimsu <command>`).

## Linux

Native Linux (or a Linux VM) matches CI most closely. Install Java 25, `gh`, Google Cloud CLI, `python3`. No further special handling is known.

## Windows

Run the **pipeline** (`validate-replay.sh` / `run-replay.sh`, downloads) in **WSL** (Ubuntu), not in Git Bash or PowerShell. The script, `gcloud` and the jar paths assume Linux. Prefer **WSL 2**: WSL 1 has slower `/mnt/c` I/O and the hang below.

The **analysis** commands run fine with Windows Java 25 from Git Bash, using relative or `C:/…` paths: `output-record-compare`, `tx-dump`, `introspect`, `javap`, and the helper scripts. There's no need to switch to WSL for those. Add `-Dstdout.encoding=UTF-8` to `java` if the console shows `�` instead of the em-dash. In Git Bash use `python` when `python3` is the Microsoft Store stub.

- **`gcloud storage` / `gsutil -m` can hang on exit under WSL 1.** The download is complete but worker processes never exit. Use one process with threads: `export CLOUDSDK_STORAGE_PROCESS_COUNT=1 CLOUDSDK_STORAGE_THREAD_COUNT=24`. To check a "stuck" download, compare `du -sb <dir>` with `gcloud storage du -s <gs-uri>`, and re-read the log before killing anything: the log you look at may lag.
- **Line endings:** with `core.autocrlf=true`, shell scripts get CRLF and fail in WSL with `$'\r': command not found`. This skill's `.gitattributes` forces LF for its scripts. For other scripts, use `tr -d '\r' < in.sh > out.sh`.
- From Claude Code on Windows, start long runs in a Terminal panel tab:
  `wsl.exe -d Ubuntu -e bash /mnt/c/<path>/run.sh`. The user logs in to gcloud once inside WSL.
- Windows `java` with `-cp` needs Windows paths (`C:/tmp/x.jar;C:/tmp/classes`). Git-Bash style `/c/tmp/...` inside a classpath string is not translated.
- Prefer `curl` over Python `urllib` for the mirror node if Python's TLS connection gets reset.
- WSL needs its own `gcloud` login, separate from Windows `gcloud`.
- `run-replay.sh` downloads `validate-replay.sh` with `gh`. If `gh` isn't installed in WSL, download the script on the Windows side and pass it in:
  `gh api -H "Accept: application/vnd.github.raw" "repos/hashgraph/hedera-state-validator/contents/.github/scripts/validate-replay.sh?ref=main" > validate-replay.sh`,
  then `VALIDATE_REPLAY=/mnt/c/<path>/validate-replay.sh`.
