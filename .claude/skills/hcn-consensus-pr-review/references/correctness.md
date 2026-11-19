# Correctness

Bugs: code that does the wrong thing under some input, timing or failure. Whether a correctness finding blocks merge
depends on its impact, not its category.

**Every finding names a concrete failure scenario:** the input, state or timing, and the wrong outcome it leads to. If
the skill can't name one, it isn't a finding. If the risk is real but the scenario depends on something the skill can't
check, raise a question instead.

## Impact

The failure scenario says how the bug happens. The impact says how much it costs. Answer these before deciding the
severity:

- **Scope:** a one-off transient, or a lasting effect? Which inputs or states reach it?
- **Bound:** the worst case, in units the reader cares about: time, events, bytes, rounds.
- **Reach:** which callers or tests actually reach it? Check them; don't assume.
- **Baseline:** is it worse than the code before the PR? If the old code behaved the same or worse, it isn't a
  regression.

**Blocks merge only when** the worst case is at least one of:

- data lost or corrupted, or a duplicate the receiver keeps
- a consensus, determinism or safety property broken: nodes disagree, or a seeded run is no longer reproducible
- a crash, hang, deadlock or resource leak
- a regression in behaviour that callers or tests rely on

**Test fixtures** (`testFixtures`, `src/test`, Otter, Turtle, Falcon): a bug blocks merge only when it can make a test
pass when it should fail, fail when it should pass, or report a misleading result. A timing or ordering skew in a
simulator that no test's conclusion depends on doesn't block merge.

Otherwise it's a **non-blocking correctness finding**: real, reported, and put in the follow-up block.

**Example:** #28053 (commit `3c60ed28`), `LinkStates.java` and `EventRouter.java` in the Turtle simulated network. When
a partition healed, links came up one pair at a time, so a held copy could be routed through a link whose reconnect
delay wasn't set yet. The review marked it blocking. It wasn't: it affected only events already waiting at the heal,
it delayed them by at most one reconnect delay, nothing was lost, runs stayed deterministic, the receiver dropped the
extra duplicates, no test measures recovery speed, and the old code had the same delay.

## 1. Error paths and cleanup

**Finding when:**

- state changed for an operation isn't restored if a later step throws. Example: #27835, where `createLearnerAsync()`
  ran outside the `try`, so a failure left the socket timeout changed.
- a failure path doesn't release what the other side is waiting on. Example: #27835, where the learner didn't
  disconnect, so the teacher kept its reserved state until its own timeout.
- a flag or field claims ownership has passed before it actually has, so a failure in between leaks the resource.
  Example: #27835, `learnerHandedOver`.
- a `catch` is narrower than what the javadoc promises. Example: #27835 caught `RuntimeException` where the javadoc
  promised cleanup on any failure, so an `Error` leaked the copy.

## 2. Resource ownership and lifetime

**Rule:** whoever creates a resource closes it, unless ownership is explicitly handed over and the javadoc says so.

**Finding when:**

- a stream, channel, executor or file is not closed on every path, including failures. Example: #27768, a stream left
  open after a library change.
- a reserved or reference-counted object can still be used after release, or is never released. For signed states, see
  [RUL-001](/platform-sdk/docs/consensus-layer/rules/RUL-001-signed-state-reservations.md); that rule is checked through
  [consensus.md](consensus.md).

## 3. Concurrency

**Finding when:**

- shared mutable state is read and written from more than one thread without a happens-before relationship
- a check and the action that depends on it aren't atomic. Example: #27815, `unsubscribe` racing a concurrent
  `subscribe`.
- an operation that must always be able to run, such as `cancel()` or `close()`, waits on a lock held during long work
- after `close()`, a method can still accept work or spin forever. Examples: #28095, where `supply()` could be accepted
  after `close()`, and `hasNext()` could spin and ignore interrupts.
- an interrupt is swallowed without restoring the interrupt flag or ending the work
- one field is guarded in two different ways, such as `synchronized` plus `volatile` (#27736), with no comment saying
  why

**Question when** a concurrent class doesn't document a property callers will rely on, such as the order in which
waiting threads are served (#28081). Missing javadoc in general is covered by
[clarity-and-naming.md](clarity-and-naming.md).

## 4. Behaviour changes

**Finding when:**

- input the old code accepted is now rejected or handled differently, and the PR doesn't say this was intended.
  Example: #27571, where certificate subjects with more than one part stopped working.
- a check reports success but the operation fails later. Example: #27796, where a key was reported as supported but
  using it threw.
- a config value allowed by its validation annotation turns off a safety mechanism. Example: #28042, where
  `flushInterval=0` passed `@Min(0)` and removed all backpressure.

## 5. Tests that can pass for the wrong reason

**Finding when a test:**

- waits a fixed time (`sleep`, `join(timeout)` followed by `isAlive()`) instead of for the state it needs. Examples:
  #28095, #27835.
- asserts something too weak to catch the bug it targets. Examples: #27571 asserted `"EdDSA"`, which Ed448 also passes;
  #27536 used `assertEquals` where the point was the same instance.
- calls AssertJ `.as(...)` after the assertion, so the description is never used (#27815)
- shares static state with other tests in a way that can leak between them (#27815)
- claims an environment it doesn't require. Example: #27790, a test named for Turtle that also ran in the container
  environment, where its assertions failed. Otter tests state what they need with `Capability`.

**Not a finding:** missing tests in general. Test coverage is the reviewer's call.

## 6. Release risk

**Question when** a change to production behaviour is risky and can't easily be turned off. Ask whether it should go
behind a feature flag until it has passed performance and longevity testing. Example: #27835, where reconnect's early
state copy went behind `reconnect.copyLearnerStateEarly`, off by default, so the release team could reduce the risk of
the release.
