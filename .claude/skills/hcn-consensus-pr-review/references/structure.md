# Structure

How classes relate, what they own, and what they expose. Module-level structure (module categories, dependency
direction, `internal` packages, test fixtures) is defined in [`platform-sdk/CLAUDE.md`](/platform-sdk/CLAUDE.md); this
file covers what sits below it.

## Module rules

Check every change to `module-info.java` and `build.gradle.kts` against the modularization rules in
[`platform-sdk/CLAUDE.md`](/platform-sdk/CLAUDE.md#modularization-rules). Don't copy the rules here.

The build enforces only part of them:

- **Enforced:** a package that `module-info.java` doesn't export can't be used from another module (JPMS). Don't report
  this; the build fails first.
- **Not enforced:** the rules about which module categories may depend on which (rules 1–4, 6, 7). No build check or
  architecture test covers them. Report every violation.

## Topics

Each topic gives the rule, when it is a finding, and when it isn't. The examples come from past reviews.

### 1. Construction and ownership

**Rule:**

- A class doesn't construct a collaborator that could be passed in.
- Two classes don't hold references to each other unless the design needs it. If it does, the javadoc of both says why.

**Finding when:**

- A constructor or method creates an object the caller already has or could supply.
- Two classes reference each other, and neither javadoc explains why.

**Example:** #27973. `FalconSharedTipsets` created `FalconSharedTipsetTracker`, and each held the other. The fix was to
construct the tracker with the tipsets passed in and register it with them.

### 2. Configuration access

Applies to production code and test tooling.

**Rule:**

- Pass `Configuration` to the constructor. Read the values there into fields.
- Don't define a tunable that already exists in another config class. Use the existing one.

**Finding when:**

- A new config class or constant copies a value already defined elsewhere.
- Config values are read in some other way, such as a static lookup or reading on every call.

**Example:** #27792. `SimulatedNetworkConnectivityConfig` repeated values from `BroadcastConfig`. The reviewer wrote:
"Usually, we pass `Configuration` in the constructor and read what we need into constants."

### 3. Duplication and reuse

**Rule:** before adding a helper, collection or field, check whether one already exists. Reuse it.

**Finding when:** the skill can point to existing code that does the same thing, so the new code can call it as it is.
Name it, with its location.

**Question, not a finding, when:** existing code is similar but not the same, and reuse would mean making it more
generic. Sometimes that's the right call and sometimes it isn't. Name the existing code and its location, and ask
whether it should be extended to cover the new use.

**Not a finding:** a suspicion of duplication with no existing code to point to.

**Examples:**

- #27936: a separate list of nodes, when `topology().allNodes()` already returns them.
- #28081: `OutputQueueBench` kept its own copy of the backoff logic it was meant to measure.
- #27822: `createSnapshot()` could reuse a helper added in the same PR.

### 4. API surface and visibility

**Rule:**

- An API tells the reader everything it is responsible for, simply, and is easy to use correctly.
- It is no larger than that.

The first part is a judgment call. Use the cold reader from
[clarity-and-naming.md](clarity-and-naming.md) to make it concrete:

- **Finding when:** the cold reader, reading only the API (types, signatures, names, javadoc), misses a responsibility
  the implementation has, or describes a use the implementation doesn't support. Name what was missed or misread.
- **Question, not a finding, when:** the cold reader understood the API, but using it looks harder than it needs to be.
  Examples: a call order that callers must know but the API doesn't show, or parameters that only make sense with
  knowledge of the implementation. Ask whether it could be simpler.

**For the second part, a finding when:**

- An interface method exists only for debugging (#27959).
- A method returns an internal collection type that callers then depend on, such as `ArrayDeque` (#27605).
- A class, method or field is more visible than its callers need, such as a `public` test class (#27838).
- `module-info.java` exports test-fixture code to production modules, with no comment saying why (#27960).
- A change adds a transitive dependency much heavier than the code uses. This applies to test code as well. Example:
  #27945 pulled all of JMH into every Otter test for a few utilities.

### 5. Speculative generality

**Rule:** no configuration, modes, methods or abstractions without a current use. Add them when something needs them.

**Finding when:** the PR adds one of these and nothing in the codebase or the PR uses it.

**Examples:**

- #27792: four new configurable values. Reviewer: "Is there really a need to configure these four values?"
- #27815: an `unsubscribe()` that nothing called.
- #27936: a "pre-setup mode" that allowed some operations only before setup, which added complexity for no current use.

### 6. Layering and responsibilities

**Finding when:**

- A method only forwards to a method one layer down, adding nothing (#27815: "No need to go up a layer").
- A public method is left over after its callers moved to something else (#27605).
- A class has taken on separate responsibilities (#27790: `SimulatedGossip` "feels a bit overloaded").

A responsibility finding must name the groups of fields and methods that belong apart and propose the split. Signs of a
class taking on too much:

- groups of fields and methods that never use each other
- parts that change for unrelated reasons
- a class name that no longer describes everything the class does

Without named groups, it isn't a finding.

### 7. Test hooks in production classes

Soft rule. Raise it as a question in reviewer mode; it is never a finding.

**Question when:** a production class gains a constructor, overload, setter or hook that only tests use. Ask whether the
test could build its dependencies explicitly instead.

**Example:** #27959. A reviewer suggested a test-only overloaded constructor. The author declined, preferring "a test to
be explicit about its setup."

### 8. Leftovers from a refactor

**Rule:** after a PR replaces something, nothing from the old approach remains.

**Finding when:** anywhere in the codebase, not only in the diff, any of these remain:

- a method left with no callers
- a parameter no longer used
- a call to, or reference to, the replaced library or design

Stale comments and names belong in [clarity-and-naming.md](clarity-and-naming.md).

**Example:** #27571 replaced BouncyCastle. Four files still referred to it: comments, a provider registration call, and a
test certificate authority.

### 9. Immutability and records

**Observed practice** in in-scope `main` code:

- Records are the usual form for passive data: 192 records, 40 of them `@ConfigData`.
- Record components carry `@NonNull` or `@Nullable` and are documented with `@param`. Example: `ConsensusEngineOutput`.
- 36 records hold a collection or an array. None of them make a defensive copy. Data handed to another component is
  treated as immutable by convention, not enforced.

**Rule:**

- An object passed to another component (over a wire, in a record, or as a return value) isn't changed afterwards.
- A record has no array component. Record `equals()` and `hashCode()` compare arrays by reference, so two records with
  equal contents aren't equal.

**Finding when:**

- Code changes a collection or object after passing it on.
- A new record has an array component.

**Strong preference, not a rule:** new passive data types are records rather than classes with getters. A new class
that only holds data gets a suggestion to make it a record, not a finding.

**Not a finding:** a record that holds a collection without copying it. Existing code doesn't copy either.

### 10. What isn't a finding

**Reviewer mode:** every structure finding names a concrete cost:

- a dependency that shouldn't exist
- duplicated logic, with the location of the original
- a larger API surface
- a state that can be gotten wrong

"I would have done it differently", with no named cost, is dropped. Example: #27936, "I probably would have used
`SimulatedNetwork` directly, but this certainly works as well."

**Author mode:** suggestions without a named cost are shown in a separate optional section, after the findings.
