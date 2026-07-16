# 10M Read-Pacing Matrix Experiment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate one canonical 10M ReconnectBench state, run and validate the six-cell socket-buffer/read-pacing matrix, and publish an evidence-backed analysis comparable in structure to the July 8 smoke-matrix note.

**Architecture:** Use the existing data/ReconnectBench save/restore lifecycle to generate the state once and restore it for every measured cell. Interleave the three SocketFactory buffer variants across control and binding latency legs, preserve per-cell raw artifacts before shared outputs are overwritten, then derive one durable result note and index updates from accepted runs only.

**Tech Stack:** Java 25, Gradle wrapper, JMH 1.37, ReconnectBench, macOS loopback TCP and caffeinate, Markdown evidence documents.

## Global Constraints

- Execute from /Users/thenswan/Work/LimeChain/playground/hiero-consensus-node; do not move this experiment into a new worktree because the approved saved-state and completion paths are in this workspace.
- Java 25 is required; use the repository ./gradlew wrapper and never install Gradle manually.
- Gradle commands require sandbox escalation in this workspace.
- The only approved production edit is the temporary buffer selection in platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java.
- Do not change platform-sdk/swirlds-benchmarks/settings.txt, ReconnectBench.java, or platform-sdk/swirlds-benchmarks/build.gradle.kts.
- Keep benchmark.benchmarkData=data, benchmark.saveDataDirectory=true, benchmark.verifyResult=false, and virtualMap.reconnectMode=pullTopToBottom.
- Generate 1000 * 10000 base records with seed 9823452658, add/modify/remove probabilities 0.10/0.40/0.00, maxKey=10000000, keySize=32, recordSize=128, and numThreads=32.
- Use REALISTIC, 200 Mbit/s, and one-way latency 270 us for control or 50,000 us for binding.
- Use current JVM defaults from the Gradle task: -Xms24g -Xmx24g -XX:+AlwaysPreTouch.
- Every measured cell is one fork, no warmup, three single-shot measurement iterations.
- Use caffeinate -i for generation and every measured invocation; keep the Mac powered, plugged in, open, and free from identifiable heavy concurrent work.
- Never invalidate an attempt solely because its timing is an outlier. If sleep, heavy interference, wrong state, wrong socket variant, missing iterations, or a benchmark failure invalidates a cell, retain it and rerun the whole three-iteration cell.
- Do not run clean after state generation or during the matrix.
- Leave the canonical state at platform-sdk/swirlds-benchmarks/data/ReconnectBench and restore SocketFactory.java to its committed 1 MiB form at completion.

## File And Artifact Map

- Create temporary run controls: /tmp/reconnectbench-10m-iterations.init.gradle, /tmp/reconnectbench-capture.sh, and /tmp/reconnectbench-validate-cell.sh.
- Create temporary evidence: platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/.
- Temporarily modify: platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java:83-86,128-131.
- Create after analysis: 25083-improve-reconnectbench/evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-16-read-pacing-10m-matrix.md.
- Modify after analysis: 25083-improve-reconnectbench/evidence-and-calibration/local-reconnectbench-calibration-notes/local-reconnectbench-calibration-notes.md and 25083-improve-reconnectbench/Index.md.

---

### Task 1: Freeze The Baseline And Create Run Controls

**Files:**
- Create: /tmp/reconnectbench-10m-iterations.init.gradle
- Create: /tmp/reconnectbench-capture.sh
- Create: /tmp/reconnectbench-validate-cell.sh
- Create: platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/manifest.md
- Create: platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/00-environment.txt
- Create: platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/00-loopback-test.log
- Create: platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/00-compile-jmh.log

**Interfaces:**
- Consumes: the approved experiment design and committed 1 MiB SocketFactory baseline.
- Produces: a validated workspace, a three-iteration JMH override, reusable capture/validation commands, passing preflight evidence, and an initialized manifest.

- [ ] **Step 1: Verify tracked experiment files are clean and the state root is absent**

Run:

~~~bash
git diff --exit-code --   platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java   platform-sdk/swirlds-benchmarks/settings.txt   platform-sdk/swirlds-benchmarks/build.gradle.kts   platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java
test ! -e platform-sdk/swirlds-benchmarks/data/ReconnectBench
sed -n '1,12p' platform-sdk/swirlds-benchmarks/settings.txt
~~~

Expected: git diff and test exit 0; settings show benchmarkData=data, saveDataDirectory=true, verifyResult=false, and pullTopToBottom. Stop before generation if any condition differs.

- [ ] **Step 2: Verify Java, disk, power, and host identity**

Run each command and preserve its output for Step 7:

~~~bash
java -version
sw_vers
uname -a
system_profiler SPHardwareDataType
df -h platform-sdk/swirlds-benchmarks
pmset -g batt
git rev-parse HEAD
git branch --show-current
git status --short
~~~

Expected: Java major version 25, at least 100 GiB available, AC power, and the current task branch. Record unrelated untracked files without modifying them. Omit hardware serial number, hardware UUID, and provisioning identifier from durable evidence.

- [ ] **Step 3: Create the artifact directory**

Run:

~~~bash
mkdir -p platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16
~~~

- [ ] **Step 4: Create the JMH iteration override**

Use apply_patch to create /tmp/reconnectbench-10m-iterations.init.gradle with exactly:

~~~groovy
gradle.projectsEvaluated {
    gradle.rootProject.project(":swirlds-benchmarks").tasks.named("jmhReconnect").configure {
        iterations.set(3)
    }
}
~~~

Copy it into the artifact directory:

~~~bash
cp /tmp/reconnectbench-10m-iterations.init.gradle   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/00-iterations.init.gradle
~~~

- [ ] **Step 5: Create the artifact capture helper**

Use apply_patch to create /tmp/reconnectbench-capture.sh with exactly:

~~~zsh
#!/bin/zsh
set -euo pipefail

if [[ $# -ne 1 ]]; then
    print -u2 "usage: reconnectbench-capture.sh <artifact-prefix>"
    exit 2
fi

prefix="$1"
root="platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16"

cp platform-sdk/swirlds-benchmarks/build/results/jmh/results-reconnect.txt "$root/$prefix-jmh-result.txt"
cp platform-sdk/swirlds-benchmarks/build/reconnectbench-gc.log "$root/$prefix-gc.log"
cp platform-sdk/swirlds-benchmarks/settingsUsed.txt "$root/$prefix-settingsUsed.txt"
cp platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java "$root/$prefix-SocketFactory.java"
~~~

Run:

~~~bash
chmod +x /tmp/reconnectbench-capture.sh
~~~

- [ ] **Step 6: Create the per-cell validator**

Use apply_patch to create /tmp/reconnectbench-validate-cell.sh with exactly:

~~~zsh
#!/bin/zsh
set -euo pipefail

if [[ $# -ne 3 ]]; then
    print -u2 "usage: reconnectbench-validate-cell.sh <cell-log> <expected-latency-nanos> <socket-source-snapshot>"
    exit 2
fi

log="$1"
latency="$2"
source_snapshot="$3"
prep="platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/prep-1m-control-270us.log"

test -f "$log"
test -f "$source_snapshot"
rg -q '# Warmup: <none>' "$log"
rg -q '# Measurement: 3 iterations, single-shot each' "$log"
rg -q '# Fork: 1 of 1' "$log"
[[ "$(rg -o '^[0-9]+[,.][0-9]+ s/op' "$log" | wc -l | tr -d ' ')" == "3" ]]
[[ "$(rg -c 'Restoring map from data/ReconnectBench/teacher/saved0' "$log")" == "1" ]]
[[ "$(rg -c 'Restoring map from data/ReconnectBench/learner/saved0' "$log")" == "1" ]]
[[ "$(rg -c 'ReconnectBench state:' "$log")" == "3" ]]
[[ "$(rg -c 'Socket transport diagnostics:' "$log")" == "3" ]]
[[ "$(rg -c 'Socket read pacing:' "$log")" == "3" ]]
[[ "$(rg -c 'Reconnect stats:' "$log")" == "3" ]]
[[ "$(rg -c 'Network teacherToLearner:' "$log")" == "3" ]]
[[ "$(rg -c 'Network learnerToTeacher:' "$log")" == "3" ]]
[[ "$(rg -c "configuredLatencyNanos=$latency" "$log")" == "3" ]]
[[ "$(rg -c 'configuredBandwidthBytesPerSecond=25000000' "$log")" == "3" ]]
rg -q 'BUILD SUCCESSFUL' "$log"

normalized_states="$(
    rg --no-filename 'ReconnectBench state:' "$prep" "$log"         | sed -E 's/^.*ReconnectBench state:/ReconnectBench state:/'         | sort -u
)"
[[ "$(print -r -- "$normalized_states" | wc -l | tr -d ' ')" == "1" ]]
[[ "$normalized_states" == *"learnerSize=9999999"* ]]

if rg -n 'BUILD FAILED|OutOfMemoryError|Reconnect benchmark was interrupted|ERROR.*FAIL|System sleep|Wake reason' "$log"; then
    exit 1
fi

print -r -- "$normalized_states"
~~~

Run:

~~~bash
chmod +x /tmp/reconnectbench-validate-cell.sh
~~~

- [ ] **Step 7: Initialize the manifest and environment evidence**

Use apply_patch to create manifest.md with exactly:

~~~markdown
# 2026-07-16 ReconnectBench 10M Read-Pacing Run Manifest

Canonical state: not generated

| Run | Buffer | One-way latency | Purpose | Status |
|---|---|---:|---|---|
| prep | 1 MiB | 270 us | generate and save canonical state | pending |
| cell 1 | unset | 270 us | control | pending |
| cell 2 | 32 KiB | 50,000 us | binding | pending |
| cell 3 | 1 MiB | 270 us | control | pending |
| cell 4 | unset | 50,000 us | binding | pending |
| cell 5 | 32 KiB | 270 us | control | pending |
| cell 6 | 1 MiB | 50,000 us | binding | pending |
~~~

Use apply_patch to create 00-environment.txt from the exact Step 2 commands and outputs, excluding the identifiers named in Step 2. Each later task replaces its pending status with accepted or superseded plus an evidence-backed reason, and records start/end times, child exit status, and artifact filenames below the table.

- [ ] **Step 8: Validate the init script without running the benchmark**

Run with Gradle sandbox escalation:

~~~bash
./gradlew :swirlds-benchmarks:help --task jmhReconnect   --init-script /tmp/reconnectbench-10m-iterations.init.gradle   --console=plain
~~~

Expected: BUILD SUCCESSFUL and no ReconnectBench state directory.

- [ ] **Step 9: Run the socket transport unit preflight**

Run with Gradle sandbox escalation:

~~~bash
script -eFq   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/00-loopback-test.log   ./gradlew :swirlds-benchmarks:test   --tests com.swirlds.benchmark.reconnect.network.LoopbackSocketTransportTest   --console=plain
~~~

Expected: child exit 0, BUILD SUCCESSFUL, all selected tests pass.

- [ ] **Step 10: Compile JMH sources**

Run with Gradle sandbox escalation:

~~~bash
script -eFq   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/00-compile-jmh.log   ./gradlew :swirlds-benchmarks:compileJmhJava --console=plain
~~~

Expected: child exit 0 and BUILD SUCCESSFUL.

---

### Task 2: Generate And Validate The Canonical 10M State

**Files:**
- Create: platform-sdk/swirlds-benchmarks/data/ReconnectBench/teacher/saved0/**
- Create: platform-sdk/swirlds-benchmarks/data/ReconnectBench/learner/saved0/**
- Create: platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/prep-1m-control-270us*
- Modify: platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/manifest.md

**Interfaces:**
- Consumes: Task 1 controls, absent state root, and committed 1 MiB SocketFactory.
- Produces: one validated saved learner/teacher pair and its exact state signature.

- [ ] **Step 1: Recheck the state boundary and host readiness**

Run:

~~~bash
test ! -e platform-sdk/swirlds-benchmarks/data/ReconnectBench
git diff --exit-code -- platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java
pmset -g batt
ps -axo pid,pcpu,pmem,etime,command | sort -k2 -nr | head -n 20
~~~

Expected: absent state root, clean SocketFactory, AC power, no identifiable heavy competing workload.

- [ ] **Step 2: Generate and save the canonical state**

Run with sandbox escalation. Do not use the iteration init script for preparation:

~~~bash
script -eFq   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/prep-1m-control-270us.log   caffeinate -i ./gradlew :swirlds-benchmarks:jmhReconnect   -PnumFiles=1000 -PnumRecords=10000   -PrandomSeed=9823452658   -PteacherAddProbability=0.10 -PteacherModifyProbability=0.40 -PteacherRemoveProbability=0.00   -PmaxKey=10000000 -PkeySize=32 -PrecordSize=128 -PnumThreads=32   -PnetworkProfile=REALISTIC -PnetworkBandwidthMegabitsPerSecond=200   -PnetworkLatencyMicroseconds=270 --console=plain
~~~

Expected: child exit 0, successful generation/save, one discarded reconnect score, and BUILD SUCCESSFUL. If this fails, preserve the log and stop; do not delete or replace a partial state without new user approval.

- [ ] **Step 3: Preserve shared preparation outputs**

Run:

~~~bash
/tmp/reconnectbench-capture.sh prep-1m-control-270us
~~~

Expected: four preparation artifacts exist beside the raw log.

- [ ] **Step 4: Validate saved directories and exact state bounds**

Run:

~~~bash
test -d platform-sdk/swirlds-benchmarks/data/ReconnectBench/teacher/saved0
test -d platform-sdk/swirlds-benchmarks/data/ReconnectBench/learner/saved0
rg -n 'Building a state of size 10000000|Saved map to data/ReconnectBench/(teacher|learner)/saved0|ReconnectBench state:'   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/prep-1m-control-270us.log
rg 'ReconnectBench state:'   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/prep-1m-control-270us.log   | sed -E 's/.*learnerSize=([0-9]+), teacherSize=([0-9]+).*/\1 \2/'   | head -n 1   | awk '$1 == 9999999 && $2 >= 10990000 && $2 <= 11010000 { exit 0 } { exit 1 }'
~~~

Expected: both saved directories exist, learner size is 9,999,999, teacher size is within 10,990,000–11,010,000.

- [ ] **Step 5: Record the canonical state signature**

Run:

~~~bash
rg 'ReconnectBench state:'   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/prep-1m-control-270us.log   | sed -E 's/^.*ReconnectBench state:/ReconnectBench state:/'   | head -n 1
~~~

Use apply_patch to put that exact line in manifest.md, replace Canonical state: not generated with Canonical state: generated and validated, and mark prep accepted with observed times, exit 0, and artifact filenames.

---

### Task 3: Run Cell 1 — Unset Buffer, 270 us Control

**Files:**
- Modify: platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java:83-86,128-131
- Create: artifact files with prefix cell-01-unset-control-270us
- Modify: manifest.md

**Interfaces:**
- Consumes: Task 2 canonical state and committed 1 MiB source form.
- Produces: accepted unset/control evidence and unset source form.

- [ ] **Step 1: Apply the unset source form**

Use apply_patch so the server experiment block is exactly:

~~~java
//        final int reconnectBufferBytes = 1 << 20; // 1MiB
//        final int reconnectBufferBytes = 32768;
//        serverSocket.setReceiveBufferSize(reconnectBufferBytes);
~~~

and the client experiment block is exactly:

~~~java
//        final int reconnectBufferBytes = 1 << 20; // 1MiB
//        final int reconnectBufferBytes = 32768;
//        clientSocket.setReceiveBufferSize(reconnectBufferBytes);
//        clientSocket.setSendBufferSize(reconnectBufferBytes);
~~~

Keep all diagnostics unchanged.

- [ ] **Step 2: Inspect source and host readiness**

~~~bash
sed -n '80,136p' platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java
pmset -g batt
ps -axo pid,pcpu,pmem,etime,command | sort -k2 -nr | head -n 20
~~~

Expected: three experimental setters commented, AC power, no identifiable heavy workload.

- [ ] **Step 3: Run cell 1**

Run with sandbox escalation:

~~~bash
script -eFq platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-01-unset-control-270us.log   caffeinate -i ./gradlew :swirlds-benchmarks:jmhReconnect   --init-script /tmp/reconnectbench-10m-iterations.init.gradle   -PnumFiles=1000 -PnumRecords=10000 -PrandomSeed=9823452658   -PteacherAddProbability=0.10 -PteacherModifyProbability=0.40 -PteacherRemoveProbability=0.00   -PmaxKey=10000000 -PkeySize=32 -PrecordSize=128 -PnumThreads=32   -PnetworkProfile=REALISTIC -PnetworkBandwidthMegabitsPerSecond=200   -PnetworkLatencyMicroseconds=270 --console=plain
~~~

- [ ] **Step 4: Capture, validate, and record cell 1**

~~~bash
/tmp/reconnectbench-capture.sh cell-01-unset-control-270us
/tmp/reconnectbench-validate-cell.sh   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-01-unset-control-270us.log   270000   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-01-unset-control-270us-SocketFactory.java
~~~

Expected: validator exits 0 and prints one canonical state line. Mark the manifest row accepted with times, exit, and files. If invalid, mark this attempt superseded with the exact reason and rerun Task 3 under a cell-01-unset-control-270us-attempt-2 prefix.

---

### Task 4: Run Cell 2 — 32 KiB Buffer, 50,000 us Binding

**Files:**
- Modify: platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java:83-86,128-131
- Create: artifact files with prefix cell-02-32k-binding-50000us
- Modify: manifest.md

**Interfaces:**
- Consumes: canonical state and Task 3 unset source form.
- Produces: accepted 32 KiB/binding evidence and 32 KiB source form.

- [ ] **Step 1: Apply the 32 KiB source form**

Use apply_patch so the server block is:

~~~java
//        final int reconnectBufferBytes = 1 << 20; // 1MiB
        final int reconnectBufferBytes = 32768;
        serverSocket.setReceiveBufferSize(reconnectBufferBytes);
~~~

and the client block is:

~~~java
//        final int reconnectBufferBytes = 1 << 20; // 1MiB
        final int reconnectBufferBytes = 32768;
        clientSocket.setReceiveBufferSize(reconnectBufferBytes);
        clientSocket.setSendBufferSize(reconnectBufferBytes);
~~~

- [ ] **Step 2: Inspect source and host readiness**

Run:

~~~bash
sed -n '80,136p' platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java
pmset -g batt
ps -axo pid,pcpu,pmem,etime,command | sort -k2 -nr | head -n 20
~~~

Expected: 32768 and all three setters active, AC power, and no identifiable heavy workload.

- [ ] **Step 3: Run cell 2**

Run with sandbox escalation:

~~~bash
script -eFq platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-02-32k-binding-50000us.log   caffeinate -i ./gradlew :swirlds-benchmarks:jmhReconnect   --init-script /tmp/reconnectbench-10m-iterations.init.gradle   -PnumFiles=1000 -PnumRecords=10000 -PrandomSeed=9823452658   -PteacherAddProbability=0.10 -PteacherModifyProbability=0.40 -PteacherRemoveProbability=0.00   -PmaxKey=10000000 -PkeySize=32 -PrecordSize=128 -PnumThreads=32   -PnetworkProfile=REALISTIC -PnetworkBandwidthMegabitsPerSecond=200   -PnetworkLatencyMicroseconds=50000 --console=plain
~~~

- [ ] **Step 4: Capture, validate, and record cell 2**

~~~bash
/tmp/reconnectbench-capture.sh cell-02-32k-binding-50000us
/tmp/reconnectbench-validate-cell.sh   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-02-32k-binding-50000us.log   50000000   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-02-32k-binding-50000us-SocketFactory.java
~~~

Expected: validator exits 0. Mark accepted or retain and supersede the entire attempt before rerunning with an incremented artifact prefix.

---

### Task 5: Run Cell 3 — 1 MiB Buffer, 270 us Control

**Files:**
- Modify: platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java:83-86,128-131
- Create: artifact files with prefix cell-03-1m-control-270us
- Modify: manifest.md

**Interfaces:**
- Consumes: canonical state and Task 4 32 KiB source form.
- Produces: accepted 1 MiB/control evidence and committed source form.

- [ ] **Step 1: Apply the 1 MiB source form**

Use apply_patch so the server block is:

~~~java
        final int reconnectBufferBytes = 1 << 20; // 1MiB
//        final int reconnectBufferBytes = 32768;
        serverSocket.setReceiveBufferSize(reconnectBufferBytes);
~~~

and the client block is:

~~~java
        final int reconnectBufferBytes = 1 << 20; // 1MiB
//        final int reconnectBufferBytes = 32768;
        clientSocket.setReceiveBufferSize(reconnectBufferBytes);
        clientSocket.setSendBufferSize(reconnectBufferBytes);
~~~

- [ ] **Step 2: Verify committed source form and host readiness**

~~~bash
git diff --exit-code -- platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java
pmset -g batt
ps -axo pid,pcpu,pmem,etime,command | sort -k2 -nr | head -n 20
~~~

Expected: source diff 0 and host readiness passes.

- [ ] **Step 3: Run cell 3**

Run with sandbox escalation:

~~~bash
script -eFq platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-03-1m-control-270us.log   caffeinate -i ./gradlew :swirlds-benchmarks:jmhReconnect   --init-script /tmp/reconnectbench-10m-iterations.init.gradle   -PnumFiles=1000 -PnumRecords=10000 -PrandomSeed=9823452658   -PteacherAddProbability=0.10 -PteacherModifyProbability=0.40 -PteacherRemoveProbability=0.00   -PmaxKey=10000000 -PkeySize=32 -PrecordSize=128 -PnumThreads=32   -PnetworkProfile=REALISTIC -PnetworkBandwidthMegabitsPerSecond=200   -PnetworkLatencyMicroseconds=270 --console=plain
~~~

- [ ] **Step 4: Capture, validate, and record cell 3**

~~~bash
/tmp/reconnectbench-capture.sh cell-03-1m-control-270us
/tmp/reconnectbench-validate-cell.sh   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-03-1m-control-270us.log   270000   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-03-1m-control-270us-SocketFactory.java
git diff --exit-code -- platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java
~~~

Expected: validator and final source-diff checks exit 0. Mark accepted or supersede and rerun the whole cell.

---

### Task 6: Run Cell 4 — Unset Buffer, 50,000 us Binding

**Files:**
- Modify: platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java:83-86,128-131
- Create: artifact files with prefix cell-04-unset-binding-50000us
- Modify: manifest.md

**Interfaces:**
- Consumes: canonical state and Task 5 committed source form.
- Produces: accepted unset/binding evidence and unset source form.

- [ ] **Step 1: Apply unset source form**

Use apply_patch so the server block is:

~~~java
//        final int reconnectBufferBytes = 1 << 20; // 1MiB
//        final int reconnectBufferBytes = 32768;
//        serverSocket.setReceiveBufferSize(reconnectBufferBytes);
~~~

and the client block is:

~~~java
//        final int reconnectBufferBytes = 1 << 20; // 1MiB
//        final int reconnectBufferBytes = 32768;
//        clientSocket.setReceiveBufferSize(reconnectBufferBytes);
//        clientSocket.setSendBufferSize(reconnectBufferBytes);
~~~

- [ ] **Step 2: Inspect source and host readiness**

Run:

~~~bash
sed -n '80,136p' platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java
pmset -g batt
ps -axo pid,pcpu,pmem,etime,command | sort -k2 -nr | head -n 20
~~~

Expected: all three experimental setters commented, AC power, and no identifiable heavy workload.

- [ ] **Step 3: Run cell 4**

Run with sandbox escalation:

~~~bash
script -eFq platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-04-unset-binding-50000us.log   caffeinate -i ./gradlew :swirlds-benchmarks:jmhReconnect   --init-script /tmp/reconnectbench-10m-iterations.init.gradle   -PnumFiles=1000 -PnumRecords=10000 -PrandomSeed=9823452658   -PteacherAddProbability=0.10 -PteacherModifyProbability=0.40 -PteacherRemoveProbability=0.00   -PmaxKey=10000000 -PkeySize=32 -PrecordSize=128 -PnumThreads=32   -PnetworkProfile=REALISTIC -PnetworkBandwidthMegabitsPerSecond=200   -PnetworkLatencyMicroseconds=50000 --console=plain
~~~

- [ ] **Step 4: Capture, validate, and record cell 4**

~~~bash
/tmp/reconnectbench-capture.sh cell-04-unset-binding-50000us
/tmp/reconnectbench-validate-cell.sh   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-04-unset-binding-50000us.log   50000000   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-04-unset-binding-50000us-SocketFactory.java
~~~

Expected: validator exits 0. Mark accepted or supersede and rerun the whole cell.

---

### Task 7: Run Cell 5 — 32 KiB Buffer, 270 us Control

**Files:**
- Modify: platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java:83-86,128-131
- Create: artifact files with prefix cell-05-32k-control-270us
- Modify: manifest.md

**Interfaces:**
- Consumes: canonical state and Task 6 unset source form.
- Produces: accepted 32 KiB/control evidence and 32 KiB source form.

- [ ] **Step 1: Apply 32 KiB source form**

Use apply_patch so the server block is:

~~~java
//        final int reconnectBufferBytes = 1 << 20; // 1MiB
        final int reconnectBufferBytes = 32768;
        serverSocket.setReceiveBufferSize(reconnectBufferBytes);
~~~

and the client block is:

~~~java
//        final int reconnectBufferBytes = 1 << 20; // 1MiB
        final int reconnectBufferBytes = 32768;
        clientSocket.setReceiveBufferSize(reconnectBufferBytes);
        clientSocket.setSendBufferSize(reconnectBufferBytes);
~~~

- [ ] **Step 2: Inspect source and host readiness**

Run:

~~~bash
sed -n '80,136p' platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java
pmset -g batt
ps -axo pid,pcpu,pmem,etime,command | sort -k2 -nr | head -n 20
~~~

Expected: 32768 and all three setters active, AC power, and no identifiable heavy workload.

- [ ] **Step 3: Run cell 5**

Run with sandbox escalation:

~~~bash
script -eFq platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-05-32k-control-270us.log   caffeinate -i ./gradlew :swirlds-benchmarks:jmhReconnect   --init-script /tmp/reconnectbench-10m-iterations.init.gradle   -PnumFiles=1000 -PnumRecords=10000 -PrandomSeed=9823452658   -PteacherAddProbability=0.10 -PteacherModifyProbability=0.40 -PteacherRemoveProbability=0.00   -PmaxKey=10000000 -PkeySize=32 -PrecordSize=128 -PnumThreads=32   -PnetworkProfile=REALISTIC -PnetworkBandwidthMegabitsPerSecond=200   -PnetworkLatencyMicroseconds=270 --console=plain
~~~

- [ ] **Step 4: Capture, validate, and record cell 5**

~~~bash
/tmp/reconnectbench-capture.sh cell-05-32k-control-270us
/tmp/reconnectbench-validate-cell.sh   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-05-32k-control-270us.log   270000   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-05-32k-control-270us-SocketFactory.java
~~~

Expected: validator exits 0. Mark accepted or supersede and rerun the whole cell.

---

### Task 8: Run Cell 6 — 1 MiB Buffer, 50,000 us Binding

**Files:**
- Modify: platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java:83-86,128-131
- Create: artifact files with prefix cell-06-1m-binding-50000us
- Modify: manifest.md

**Interfaces:**
- Consumes: canonical state and Task 7 32 KiB source form.
- Produces: accepted 1 MiB/binding evidence and final committed source form.

- [ ] **Step 1: Apply 1 MiB source form**

Use apply_patch so the server block is:

~~~java
        final int reconnectBufferBytes = 1 << 20; // 1MiB
//        final int reconnectBufferBytes = 32768;
        serverSocket.setReceiveBufferSize(reconnectBufferBytes);
~~~

and the client block is:

~~~java
        final int reconnectBufferBytes = 1 << 20; // 1MiB
//        final int reconnectBufferBytes = 32768;
        clientSocket.setReceiveBufferSize(reconnectBufferBytes);
        clientSocket.setSendBufferSize(reconnectBufferBytes);
~~~

- [ ] **Step 2: Verify committed source form and host readiness**

~~~bash
git diff --exit-code -- platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java
pmset -g batt
ps -axo pid,pcpu,pmem,etime,command | sort -k2 -nr | head -n 20
~~~

Expected: source diff 0 and host readiness passes.

- [ ] **Step 3: Run cell 6**

Run with sandbox escalation:

~~~bash
script -eFq platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-06-1m-binding-50000us.log   caffeinate -i ./gradlew :swirlds-benchmarks:jmhReconnect   --init-script /tmp/reconnectbench-10m-iterations.init.gradle   -PnumFiles=1000 -PnumRecords=10000 -PrandomSeed=9823452658   -PteacherAddProbability=0.10 -PteacherModifyProbability=0.40 -PteacherRemoveProbability=0.00   -PmaxKey=10000000 -PkeySize=32 -PrecordSize=128 -PnumThreads=32   -PnetworkProfile=REALISTIC -PnetworkBandwidthMegabitsPerSecond=200   -PnetworkLatencyMicroseconds=50000 --console=plain
~~~

- [ ] **Step 4: Capture, validate, and record cell 6**

~~~bash
/tmp/reconnectbench-capture.sh cell-06-1m-binding-50000us
/tmp/reconnectbench-validate-cell.sh   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-06-1m-binding-50000us.log   50000000   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-06-1m-binding-50000us-SocketFactory.java
~~~

Expected: validator exits 0. Mark accepted or supersede and rerun the whole cell.

- [ ] **Step 5: Verify the production-source boundary**

~~~bash
git diff --exit-code --   platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java   platform-sdk/swirlds-benchmarks/settings.txt   platform-sdk/swirlds-benchmarks/build.gradle.kts   platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java
~~~

Expected: exit 0.

---

### Task 9: Extract Statistics And Analyze Matrix Behavior

**Files:**
- Read: all accepted cell and GC logs in the artifact directory
- Create: platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/matrix-statistics.txt
- Create: platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/matrix-diagnostics.txt

**Interfaces:**
- Consumes: six accepted cells with three scores and complete diagnostics.
- Produces: descriptive statistics, diagnostic comparisons, and evidence-backed conclusions for Task 10.

- [ ] **Step 1: Revalidate all six cells**

Run:

~~~bash
/tmp/reconnectbench-validate-cell.sh platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-01-unset-control-270us.log 270000 platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-01-unset-control-270us-SocketFactory.java
/tmp/reconnectbench-validate-cell.sh platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-02-32k-binding-50000us.log 50000000 platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-02-32k-binding-50000us-SocketFactory.java
/tmp/reconnectbench-validate-cell.sh platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-03-1m-control-270us.log 270000 platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-03-1m-control-270us-SocketFactory.java
/tmp/reconnectbench-validate-cell.sh platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-04-unset-binding-50000us.log 50000000 platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-04-unset-binding-50000us-SocketFactory.java
/tmp/reconnectbench-validate-cell.sh platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-05-32k-control-270us.log 270000 platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-05-32k-control-270us-SocketFactory.java
/tmp/reconnectbench-validate-cell.sh platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-06-1m-binding-50000us.log 50000000 platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-06-1m-binding-50000us-SocketFactory.java
~~~

Confirm manifest.md has six accepted rows and no pending matrix row. Return to the corresponding cell task if any validation fails.

- [ ] **Step 2: Compute iteration values, mean, median, and warm mean**

Run:

~~~bash
for log in   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-01-unset-control-270us.log   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-02-32k-binding-50000us.log   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-03-1m-control-270us.log   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-04-unset-binding-50000us.log   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-05-32k-control-270us.log   platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-06-1m-binding-50000us.log
do
  rg -o '^[0-9]+[,.][0-9]+ s/op' "$log"     | tr ',' '.'     | awk -v file="$log" '
        { value[NR] = $1; sum += $1 }
        END {
          if (NR != 3) exit 2
          a = value[1]; b = value[2]; c = value[3]
          if (a > b) { t = a; a = b; b = t }
          if (b > c) { t = b; b = c; c = t }
          if (a > b) { t = a; a = b; b = t }
          printf "%s iter1=%.3f iter2=%.3f iter3=%.3f mean=%.3f median=%.3f warmMean=%.3f\n",
                 file, value[1], value[2], value[3], sum / 3, b, (value[2] + value[3]) / 2
        }'
done
~~~

Use apply_patch to save the exact six output lines as matrix-statistics.txt.

- [ ] **Step 3: Extract diagnostics and counter stability**

Extract diagnostics from all accepted logs:

~~~bash
for log in \
  platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-01-unset-control-270us.log \
  platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-02-32k-binding-50000us.log \
  platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-03-1m-control-270us.log \
  platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-04-unset-binding-50000us.log \
  platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-05-32k-control-270us.log \
  platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-06-1m-binding-50000us.log
do
  rg 'Socket transport diagnostics:|Socket read pacing:|Reconnect stats:|Network teacherToLearner:|Network learnerToTeacher:' "$log"
done
~~~

Normalize timestamps only for counter-uniqueness inspection:

~~~bash
for log in \
  platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-01-unset-control-270us.log \
  platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-02-32k-binding-50000us.log \
  platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-03-1m-control-270us.log \
  platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-04-unset-binding-50000us.log \
  platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-05-32k-control-270us.log \
  platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/cell-06-1m-binding-50000us.log
do
  rg 'Reconnect stats:|Network teacherToLearner:|Network learnerToTeacher:' "$log" \
    | sed -E 's/^.*(Reconnect stats:|Network teacherToLearner:|Network learnerToTeacher:)/\1/' \
    | sort -u
done
~~~

Save all extracted lines and uniqueness findings to matrix-diagnostics.txt with apply_patch. Preserve differing counters and explain them; do not collapse them.

- [ ] **Step 4: Inspect GC and fatal host/runtime evidence**

~~~bash
rg -n 'Pause Full|OutOfMemoryError|to-space exhausted|Evacuation Failure|Allocation Failure' \
  platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/*-gc.log || true
rg -n 'BUILD FAILED|Reconnect benchmark was interrupted|ERROR.*FAIL|System sleep|Wake reason' \
  platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/*.log || true
~~~

Expected: no fatal matches. Record nonfatal GC pauses or warnings that plausibly explain variation.

- [ ] **Step 5: Analyze the control leg**

Compare unset, 32 KiB, and 1 MiB control scores, means, medians, warm means, live windows, windows opened, parked time, and transfer bytes. State whether the leg remains bandwidth-governed and whether any ordering is consistent across descriptive summaries. Do not claim statistical equivalence from three iterations.

- [ ] **Step 6: Analyze the binding leg**

Test whether median wall-clock ordering is 32 KiB > unset > 1 MiB. Relate each result to lastWindowBytes, windowsOpened, totalParkedMillis, bytes transferred, and the first-order per-direction expectation min(25 MB/s, W / 0.1 s).

- [ ] **Step 7: Compare qualitatively with July 8**

Compare matrix ordering, binding max/min ratios, control-versus-binding shape, window cadence, and unset autotuning trajectory. Exclude direct absolute-time comparisons because hardware, OS, JVM patch, code, state size, and divergence differ.

---

### Task 10: Publish Results, Update Indexes, And Verify Final State

**Files:**
- Create: 25083-improve-reconnectbench/evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-16-read-pacing-10m-matrix.md
- Modify: 25083-improve-reconnectbench/evidence-and-calibration/local-reconnectbench-calibration-notes/local-reconnectbench-calibration-notes.md
- Modify: 25083-improve-reconnectbench/Index.md
- Verify unchanged: SocketFactory.java and benchmark configuration/source files

**Interfaces:**
- Consumes: Task 9 statistics, diagnostics, manifest, raw artifacts, and approved design.
- Produces: durable result note, discoverable index entries, clean source state, and documentation commit.

- [ ] **Step 1: Write the result note from accepted evidence**

Use apply_patch to create the result note with exactly these headings and observed content:

~~~markdown
# 2026-07-16 Read-Pacing Matrix (10M Base State, Fresh Local Comparison)

## Purpose And Comparison Boundary
## Environment And Code State
## Canonical State Generation And Restoration
## Fixed Parameters And Commands
## Buffer Configurations And Effective Readbacks
## Run Order And Result Matrix
## Live Pacing, Transfer, Reconnect, And GC Evidence
## Control-Leg Interpretation
## Binding-Leg Interpretation
## Qualitative Comparison With The 2026-07-08 5M Matrix
## What Changed At 10M
## Invalid Or Superseded Attempts
## Caveats And Follow-Ups
~~~

Include all 18 accepted scores, per-cell mean/median/warm mean, exact state sizes, exact commands, buffer readbacks, pacing summaries, transfer and reconnect counters, GC findings, and every invalid attempt. Link the approved design and July 8 predecessor. Do not write conclusions unsupported by raw artifacts.

- [ ] **Step 2: Register the result in the local calibration hub**

Add under Later run notes kept as separate files:

~~~markdown
- [2026-07-16-read-pacing-10m-matrix.md](2026-07-16-read-pacing-10m-matrix.md): fresh internally paired 10M-state socket-buffer/read-pacing matrix (unset / 32 KiB / 1 MiB × control / binding), including live-window diagnostics and qualitative comparison with the July 8 5M smoke matrix.
~~~

- [ ] **Step 3: Register design, plan, and result in the task index**

Under Design And Implementation, add:

~~~markdown
- [10M Read-Pacing Matrix Experiment Design](design-and-implementation/2026-07-16-read-pacing-10m-matrix-experiment-design.md) - Approved design for the fresh internally paired 10M socket-buffer/read-pacing matrix.
- [10M Read-Pacing Matrix Execution Plan](design-and-implementation/2026-07-16-read-pacing-10m-matrix-execution-plan.md) - Step-by-step state-generation, six-cell execution, validation, artifact, and analysis plan.
~~~

Under Evidence And Calibration, add:

~~~markdown
- [2026-07-16 10M Read-Pacing Matrix](evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-16-read-pacing-10m-matrix.md) - Fresh internally paired 10M socket-buffer/read-pacing result matrix and post-run analysis.
~~~

- [ ] **Step 4: Verify documentation**

~~~bash
rg -n '10M Read-Pacing|2026-07-16-read-pacing-10m'   25083-improve-reconnectbench/Index.md   25083-improve-reconnectbench/evidence-and-calibration/local-reconnectbench-calibration-notes/local-reconnectbench-calibration-notes.md   25083-improve-reconnectbench/evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-16-read-pacing-10m-matrix.md
git diff --check -- 25083-improve-reconnectbench
~~~

Expected: all links are present and diff check is silent.

- [ ] **Step 5: Verify final source and saved state**

~~~bash
git diff --exit-code --   platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java   platform-sdk/swirlds-benchmarks/settings.txt   platform-sdk/swirlds-benchmarks/build.gradle.kts   platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java
test -d platform-sdk/swirlds-benchmarks/data/ReconnectBench/teacher/saved0
test -d platform-sdk/swirlds-benchmarks/data/ReconnectBench/learner/saved0
~~~

Expected: source diff exits 0 and both canonical saved states remain.

- [ ] **Step 6: Commit only durable documentation**

~~~bash
git add   25083-improve-reconnectbench/Index.md   25083-improve-reconnectbench/evidence-and-calibration/local-reconnectbench-calibration-notes/local-reconnectbench-calibration-notes.md   25083-improve-reconnectbench/evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-16-read-pacing-10m-matrix.md
git diff --cached --check
git commit -m "docs: record 10m read-pacing matrix"
~~~

Expected: commit contains only these three durable documentation files. Do not stage state, build artifacts, settingsUsed.txt, or unrelated user files.

- [ ] **Step 7: Final evidence report**

Report all six accepted rows, the core control/binding conclusion, result-note path, raw artifact path, final commit, final SocketFactory cleanliness, and canonical state path. Explicitly call out deviations from July 8 qualitative shape and all superseded attempts.
