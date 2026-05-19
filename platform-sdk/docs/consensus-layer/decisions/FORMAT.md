# Decisions — Entry Format

Specification for `decisions/ADR-NNN-short-slug.md` entries. This file defines the
structure only; the chronological catalog of allocated IDs, titles, dates, statuses,
and one-line summaries lives in `decisions/README.md`.

## File naming

- `decisions/ADR-NNN-short-slug.md`
- `NNN` is zero-padded to three digits (`ADR-001`, `ADR-027`).
- The slug is a short kebab-case phrase that hints at the subject (e.g.,
  `pces-state-snapshot-coordination`).
- Cross-references from other files use the ID only (e.g., "See ADR-007").
- Allocate the next free `NNN` from `decisions/README.md` and add its index row in
  the same change.

## Index entry (in `README.md`)

ADRs do not carry YAML frontmatter. The queryable metadata lives as a row in the
`decisions/README.md` index table. Every new ADR adds exactly one row:

|  Column   |                                          Content                                           |
|-----------|--------------------------------------------------------------------------------------------|
| `ID`      | Markdown link to the file, label is the bare ID (e.g., `[ADR-007](ADR-007-...md)`).        |
| `Title`   | The same descriptive title used in the document's `# ADR:` heading.                        |
| `Date`    | ISO `YYYY-MM-DD` of the decision (the day it was accepted or last materially changed).     |
| `Status`  | One of `Proposed`, `Accepted`, `Superseded`, `Deprecated`. Matches the body's `## Status`. |
| `Summary` | One or two sentences capturing what was decided and why, readable on its own.              |

Field discipline:

- **`Status`** — every entry is born `Proposed`. It becomes `Accepted` once the
  deciders have signed off. `Superseded` marks a decision replaced by a later ADR
  (name the successor in the body). `Deprecated` marks a decision that no longer
  applies because the underlying code or design has changed but no replacement
  decision exists. Tools and readers treat this field as load-bearing — keep the
  README row and the body's `## Status` in sync.
- **`Date`** — the decision date, not the file-creation date. If a decision is
  materially revised, update both this date and the body, and note the change in
  the body so the trail is preserved.
- **`Summary`** — written so a reader scanning the index can decide whether to open
  the file. State the decision, not the problem.

## Body

Sections appear in the order below. The first four (`Status`, `Context`,
`Decision`, `Consequences`) and the closing two (`References`, `Authors /
Deciders`) are mandatory. `Alternatives Considered` is mandatory whenever
realistic alternatives existed (almost always). The remaining sections are
optional and appear only when they have content.

### `# ADR: <Title>`

The H1 line. `<Title>` is a short, declarative phrase naming what the ADR decides
— the same string used in the README index row. Do not embed the `ADR-NNN`
identifier in the heading; the filename and the README row carry it.

### `## Status`

One word on its own line: `Proposed`, `Accepted`, `Superseded`, or `Deprecated`.
If `Superseded`, add the successor ID on the next line (e.g., `Superseded by
ADR-014`). Keep this in sync with the README row.

### `## Context`

The forces in play: the system as it stands, the problem or pressure that
prompted the decision, the constraints. State the discriminating facts a reader
needs to evaluate the decision — what is true today, what was observed, what is
required. Subsections (`###`) are allowed when the context has distinct parts.
This section is descriptive, not prescriptive: no decision verbs here.

### `## Decision`

What was decided, in imperative voice ("Do X", "Block Y until Z"). Lead with the
decision itself in one or two sentences a reader can quote, then add the
mechanism — how the decision is realized in code, configuration, or process. Be
specific enough that an implementer can act on it without re-deriving the
reasoning.

### `## Temporary Nature` (optional)

Include only if the decision is known to be temporary. State the condition that
will retire it (e.g., a future capability, a planned migration) and what should
replace it. A decision without a stated retirement condition is not temporary —
omit this section.

### `## Limitations` (optional)

Residual risks or guarantees that the decision does **not** provide. Use this to
make the boundary of the decision explicit, so a future reader does not assume
properties that were never claimed. If there are no notable limitations beyond
those captured under `### Negative` consequences, omit.

### `## Consequences`

What follows from the decision, grouped into three required subsections:

- **`### Positive`** — what the decision buys, in concrete terms (specific
  property preserved, work avoided, risk eliminated). Avoid generic praise.
- **`### Negative`** — what the decision costs, including the failure modes that
  are now accepted. Each item should be acknowledgeable: a future reader can
  point to it and say "yes, we knew."
- **`### Neutral`** — facts that are neither wins nor losses but a reader needs
  to know (soft conventions established, future expectations created, properties
  that are now assumed but not enforced).

Empty subsections may be marked `None.` rather than deleted; the three-way split
is part of the format.

### `## Alternatives Considered`

Numbered subsections (`### 1. <name>`, `### 2. <name>`, …). For each, state the
alternative in one or two sentences, then `**Rejected because:**` (or
`**Selected because:**` for the chosen option) followed by a bulleted list of
reasons. Include the status-quo / do-nothing option explicitly when it was on the
table — readers should see it was weighed, not ignored. The chosen alternative
appears as the last numbered entry and points back to `## Decision` rather than
restating it.

### `## References`

Links to related documents, code paths, ADRs, and specs that the reader may need
to follow up on. Use relative paths for in-repo links. Annotate each link with a
short note on what the reader will find there — bare links rot fastest.

### `## Authors / Deciders`

Bulleted list of `Full Name (@github-handle)` for the people responsible for the
decision. Both authorship and accountability live here; if the two differ in a
meaningful way, note it inline. This list is the human provenance trail — keep
it accurate.

## Skeleton

```markdown
# ADR: <Title>

## Status

Accepted

## Context

...

## Decision

...

## Consequences

### Positive

- ...

### Negative

- ...

### Neutral

- ...

## Alternatives Considered

### 1. <alternative>

...

**Rejected because:**

- ...

### 2. Status quo — <what doing nothing looks like>

...

**Rejected because:**

- ...

### 3. <chosen option>

See **Decision** above.

## References

- ...

## Authors / Deciders

- Full Name (@github-handle)
```
