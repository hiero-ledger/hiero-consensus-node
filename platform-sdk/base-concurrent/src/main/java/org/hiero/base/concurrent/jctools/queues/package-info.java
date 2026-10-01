// SPDX-License-Identifier: Apache-2.0
/// Vendored lock-free queues from [JCTools](https://github.com/JCTools/JCTools) (Apache-2.0).
///
/// ## Provenance
///
/// - Project: JCTools, tag `v4.0.6` (4.0.7 re-releases the same code with JPMS/OSGi metadata fixes only and has no
///   git tag).
/// - Artifacts (sources jars): `org.jctools:jctools-core:4.0.6` for the shared interfaces and utilities,
///   `org.jctools:jctools-core-jdk11:4.0.6` for the generated VarHandle queues.
/// - Only the `VarHandle` variant is vendored. The `Unsafe`-based queues must not be vendored: Unsafe
///   memory-access methods are deprecated for removal (JEP 471) and warn at runtime since JDK 24 (JEP 498). On
///   JDK 25, `OutputQueueBench` showed no measurable difference between the two variants. Unpadded variants are
///   intentionally not vendored either.
/// - The `queues` package is exported only to `com.swirlds.virtualmap`; the `util` package is not exported.
///
/// ## Vendored files
///
/// Local file (under `org/hiero/base/concurrent/jctools`) -> upstream path inside the sources jar of the artifact.
///
/// `jctools-core-jdk11`:
///
/// - `queues/MpscVarHandleArrayQueue.java` -> `org/jctools/queues/varhandle/MpscVarHandleArrayQueue.java`
/// - `queues/ConcurrentCircularVarHandleArrayQueue.java` ->
///   `org/jctools/queues/varhandle/ConcurrentCircularVarHandleArrayQueue.java`
/// - `util/VarHandleQueueUtil.java` -> `org/jctools/queues/varhandle/VarHandleQueueUtil.java`
///
/// `jctools-core`:
///
/// - `queues/IndexedQueueSizeUtil.java` -> `org/jctools/queues/IndexedQueueSizeUtil.java`
/// - `queues/MessagePassingQueue.java` -> `org/jctools/queues/MessagePassingQueue.java`
/// - `queues/MessagePassingQueueUtil.java` -> `org/jctools/queues/MessagePassingQueueUtil.java`
/// - `queues/QueueProgressIndicators.java` -> `org/jctools/queues/QueueProgressIndicators.java`
/// - `queues/SupportsIterator.java` -> `org/jctools/queues/SupportsIterator.java`
/// - `util/InternalAPI.java` -> `org/jctools/util/InternalAPI.java`
/// - `util/PortableJvmInfo.java` -> `org/jctools/util/PortableJvmInfo.java`
/// - `util/Pow2.java` -> `org/jctools/util/Pow2.java`
///
/// ## Local modifications
///
/// - Package relocation: `org.jctools.queues` and `org.jctools.queues.varhandle` to
///   `org.hiero.base.concurrent.jctools.queues`; `org.jctools.util` and
///   `org.jctools.queues.varhandle.VarHandleQueueUtil` to `org.hiero.base.concurrent.jctools.util` (in `package` and
///   `import` statements and in Javadoc link targets).
/// - License/provenance header replaced on every file.
/// - `PortableJvmInfo`: removed `CACHE_LINE_SIZE` and `RECOMENDED_POLL_BATCH` (unused).
/// - `IndexedQueueSizeUtil`: removed `IGNORE_PARITY_DIVISOR` (unused).
/// - `MessagePassingQueueUtil`: removed `fillUnbounded` (unused).
/// - `VarHandleQueueUtil.lpRefElement`: added `@SuppressWarnings("cast")`, because the build compiles with `-Werror`
///   and javac reports a redundant cast there.
/// - All Javadoc converted from HTML (`/** */`) to Markdown (`///`) so it passes the build's strict Javadoc checks.
///   The wording is upstream's, but comments are re-wrapped, HTML tags became Markdown, and `{@link}`/`@see`
///   targets point at the relocated classes. Upstream's dead link to `ConcurrentCircularArrayQueue` became plain code
///   text and `java.util.Queue` is referenced by its qualified name (so spotless removed the now-unused import from
///   `MessagePassingQueue`). The "automatically generated" notices were converted the same way.
/// - Spotless formatting (upstream generated files are not formatted with the project's style).
///
/// The code itself, including the algorithms and memory-ordering logic, is verbatim. Because of the formatting and
/// Javadoc changes the files are no longer textually diffable against upstream; compare code only, for example by
/// stripping comments and formatting both sides with spotless. The unused `long[]` helpers of `VarHandleQueueUtil`
/// are kept so that a code-only comparison stays clean.
///
/// ## Updating to a newer JCTools release
///
/// The conversion was done with throwaway scripts that are not kept in the repository. To pick up a new release:
///
/// 1. Download the `jctools-core` and `jctools-core-jdk11` sources jars of both the vendored version and the new
///    version from Maven Central.
/// 2. Diff the old and new upstream sources of the files listed above (and check whether their class closure, for
///    example via `jdeps -verbose:class -filter:none`, gained new classes).
/// 3. Port the upstream code changes by hand into the vendored files, keeping the modifications listed above.
/// 4. Update the version in this file and in the file headers, then run the module's tests.
///
/// ## Rules for maintainers
///
/// - `producerIndex`, `producerLimit` and `consumerIndex` are bound _by name_ via
///   `MethodHandles.lookup().findVarHandle(...)` in `MpscVarHandleArrayQueue`. Renaming or
///   removing these fields still compiles but fails with `ExceptionInInitializerError` at class initialization.
/// - `MpscVarHandleArrayQueue` is correct only with exactly one consumer thread. Any number of threads may
///   `offer`, but only one thread may `poll`, `peek`, `relaxedPoll`, `drain`, `clear` or iterate. A second consumer
///   silently corrupts the queue.
/// - `size()` is approximate and meant for metrics only.
/// - Do not rename the upstream classes; keeping upstream names keeps the files diffable.
package org.hiero.base.concurrent.jctools.queues;
