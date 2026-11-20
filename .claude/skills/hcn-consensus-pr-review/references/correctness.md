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

## 2. Hidden failures

**Rule:** a failure is visible where it happens. A state that "can't happen" throws.

**Finding when:**

- an exception is caught and dropped, or rethrown without its cause. Example: #12366, "This may be extremely difficult
  to debug."
- a null check or an early `return` skips a state the code assumes can't happen, instead of throwing
- a test fixture logs, or returns null or a default, where it should fail the test. Examples: #20250, #21422 ("This
  should fail the test IMO").
- a no-op implementation hides a misconfiguration: a call that should have an effect is silently ignored (#11398)
- an exception is turned into a log message at a lower level than the failure deserves. Example: #14694, "Why was this
  downgraded from an exception to a warning?"

## 3. Resource ownership and lifetime

**Rule:** whoever creates a resource closes it, unless ownership is explicitly handed over and the javadoc says so.

**Finding when:**

- a stream, channel, executor or file is not closed on every path, including failures. Example: #27768, a stream left
  open after a library change.
- a reserved or reference-counted object can still be used after release, or is never released. For signed states, see
  [RUL-001](/platform-sdk/docs/consensus-layer/rules/RUL-001-signed-state-reservations.md); that rule is checked through
  [consensus.md](consensus.md).
- a reservation that used try-with-resources is changed to an explicit `release()`, and the PR doesn't say why. Example:
  #22882: "Before the try-with-resources method, we had a lot of very difficult to find state reference leaks."
- an explicit `release()` has no comment saying where the matching reservation was taken

## 4. Concurrency

**First, check how the code is run.** A component driven by the wiring framework runs on the thread its scheduler gives
it. A sequential or direct scheduler never runs it on two threads at once, so its fields need no synchronization. Read
the component's scheduler type before reporting a race. About 85% of the race findings on wiring components were
rejected for this reason. Examples: #13457 (a direct scheduler called from a sequential one), #18937 ("Generally our
components don't have to worry about concurrency, this is by design").

**Finding when:**

- shared mutable state is read and written from more than one thread without a happens-before relationship
- a check and the action that depends on it aren't atomic. Example: #27815, `unsubscribe` racing a concurrent
  `subscribe`.
- an operation that must always be able to run, such as `cancel()` or `close()`, waits on a lock held during long work
- after `close()`, a method can still accept work or spin forever. Examples: #28095, where `supply()` could be accepted
  after `close()`, and `hasNext()` could spin and ignore interrupts.
- an interrupt is swallowed without restoring the interrupt flag or ending the work. Catching `InterruptedException`
  clears the flag; it doesn't survive the `catch`. In #18051 the author believed it did.
- one field is guarded in two different ways, such as `synchronized` plus `volatile` (#27736), with no comment saying
  why

**Question when** a concurrent class doesn't document a property callers will rely on, such as the order in which
waiting threads are served (#28081). Missing javadoc in general is covered by
[clarity-and-naming.md](clarity-and-naming.md).

## 5. Behaviour changes

**Finding when:**

- input the old code accepted is now rejected or handled differently, and the PR doesn't say this was intended.
  Example: #27571, where certificate subjects with more than one part stopped working.
- a check reports success but the operation fails later. Example: #27796, where a key was reported as supported but
  using it threw.
- a config value allowed by its validation annotation turns off a safety mechanism. Example: #28042, where
  `flushInterval=0` passed `@Min(0)` and removed all backpressure.

**Behaviour lost in a refactor, move or rewrite.** When a PR moves, splits or rewrites code, compare the new code with
the old, statement by statement. These are real bugs, and reviewers found them by reading the old code next to the new.

**Finding when** the old code did something the new code no longer does, and the PR doesn't say so:

- a `synchronized`, lock or `volatile` dropped. Example: #12502, "Did you intentionally remove `synchronized` here?" —
  "This was an oversight."
- a lifecycle call no longer made. Example: #11950, where the `recycleBin` was no longer started.
- a default, constant or offset changed. Examples: #22297 and #21668, where the `+ 1` on the round a roster applies
  from was dropped or misplaced.
- a rewrite of consensus logic that doesn't match the old code expression by expression. Example: #22877, "The original
  code called `firstSee` on the other parent, not the `candidate`" — "GREAT CATCH".

**Other paths not updated.** When a PR changes one path, look for its siblings: other branches of the same `switch` or
`if`, builders, copy constructors, serialization, test generators, and the other implementations of an interface.

**Finding when:**

- a sibling path needs the same change and didn't get it. Examples: #11825, a class-ID check applied in one branch
  only; #12744, a running hash not set in the recovery path.
- copied code still names what it was copied from. Example: #25292, a message naming a `tag` that doesn't exist.

## 6. Wiring

Applies to code that builds or changes the wiring between components.

**Finding when:**

- a component is created twice, so one copy never gets the inputs the other gets. Example: #26134, "the one
  instantiated in the module never is informed about reconnect states or states loaded from disk." — "Wow, impressive!"
- a component, or an input or output, is created but never wired. Example: #23455, "I don't see any code wiring this
  to the nexus."
- a wire is bound to the wrong method or input, or a component is given the wrong scheduler type, so the wiring diagram
  shows something that isn't true (#22591)
- a component calls another directly instead of through a wire, so the wiring model no longer shows the data flow

**Question when** a PR changes the wiring and doesn't show the diagram before and after. The change can't be checked
from the diff alone.

## 7. Logging

Applies to production code. Log levels and messages in test fixtures and tooling are the author's call: questions
about them were rejected every time (#20228).

**Finding when:**

- a message is logged with a marker or level that is off in production, so it is never seen. Examples: #12265 ("logs
  unfortunately won't make it to the file without markers"), #21617.
- placeholder arguments are in the wrong order (#10057)
- a message is false on some path. Example: #16025 logged "completed migration" when the migration had failed.
- an exception message about a mismatch doesn't give the expected and the found values (#16917)
- a message on a path that can repeat quickly, such as an error loop, has no rate limit (#12635)

## 8. Tests

**Finding when a test:**

- waits a fixed time (`sleep`, `join(timeout)` followed by `isAlive()`) instead of for the state it needs. Examples:
  #28095, #27835.
- asserts something too weak to catch the bug it targets. Examples: #27571 asserted `"EdDSA"`, which Ed448 also passes;
  #27536 used `assertEquals` where the point was the same instance.
- calls AssertJ `.as(...)` after the assertion, so the description is never used (#27815)
- shares static state with other tests in a way that can leak between them (#27815)
- claims an environment it doesn't require. Example: #27790, a test named for Turtle that also ran in the container
  environment, where its assertions failed. Otter tests state what they need with `Capability`.

**Finding when** a test is deleted, or is left behind when the code it tests moves, and nothing replaces it. Authors
restored the test every time. Examples: #26042 ("I am uncomfortable deleting this test without a replacement"),
#11811, #25726.

**Question when:**

- a bug fix has no test that fails without the fix. Example: #19287, "there should be a new test that … should fail with
  the previous" code.
- a new public class, or a new branch in existing logic, has no test. Examples: #22369, #15519.

Authors added the test about 80% of the time.

**Not a finding:** test coverage in general. It is the reviewer's call.

## 9. Release risk and verification

Questions for the author. None of them is a finding.

**Ask whether a change should go behind a flag** when a change to production behaviour is risky and can't easily be
turned off, until it has passed performance and longevity testing. Example: #27835, where reconnect's early state copy
went behind `reconnect.copyLearnerStateEarly`, off by default, so the release team could reduce the risk of the release.

**Ask how a runtime change was verified** when unit tests can't show it works: startup, upgrade, loading a state saved
by the previous version, performance, or behaviour over a long run. Ask which Otter test, performance run or manual run
showed it. Upgrade with real tooling is covered by the one remaining JRS test; everything else by Otter tests.

**Ask who outside the code depends on what changed** when the PR renames or removes a log marker or message, a metric
name, a config key, or a file written to disk. Operators, dashboards and devops pipelines may read them. First check
whether the item is meant for anyone outside the code: in #26134 the author pushed back, because those config keys
"should never be overridden by users".

**Ask whether the fix treats the symptom** when a fix makes a failure stop without explaining what caused it. Ask for
the cause, or a ticket to find it, and that the PR doesn't close the original issue. Example: #22297, where a reviewer
asked how a test still passed after an incorrect change; the author reverted it and opened #22318 to find out.

## 10. Performance

**Question, never a finding,** and only for code that runs per event, per transaction or per round. Name the cost: an
allocation, a copy, a lookup, a lock. Elsewhere, performance suggestions were rejected as premature optimization
(#13231).
