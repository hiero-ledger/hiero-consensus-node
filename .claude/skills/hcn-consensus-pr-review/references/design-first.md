# Design first

Some changes should be discussed among humans before code is written: a design, feedback from the team, and agreement
that it is good enough to build. With AI it is easy to skip that and send reviewers a large PR instead. They then have
to work out what the change is, why it is made, whether it is a good idea and what else was considered. A review is the
wrong place for that discussion: by then the code exists, and changing course is expensive.

This check runs before the lenses. When it fires, the review stops.

## Is the change significant?

A change is significant when it makes at least one of these decisions:

**Structure**, only when its scope and impact are large: it changes how other modules or teams use the code, or what
they can depend on. Adding a module, a dependency or an export isn't significant by itself.

- adds, removes or splits a module, or changes the dependencies between modules
- changes what a module exports, or the public API of a functional-api module
- adds a component, a wiring connection or a thread
- changes the consensus/execution boundary

**Behaviour:**

- changes behaviour that a KB invariant, rule or ADR describes
- changes a wire format, serialization or state
- changes a config default
- removes a feature
- changes the behaviour of shared test infrastructure (Turtle, Otter, Falcon, the simulated network) that other tests
  rely on

**Shape of the PR**, which makes the decisions above harder to see:

- it adds an ADR or KB decision together with the code that the decision is about
- it makes several of the decisions above, or mixes one with unrelated work
- its title or description doesn't mention a decision the diff makes, such as a behaviour change in a "chore"

Size alone isn't a decision. A large PR that makes none of them isn't significant.

## Was the design agreed?

Evidence that it was, any of:

- a linked issue that describes the design, with discussion on it
- an ADR in `platform-sdk/docs/consensus-layer/decisions/` or a proposal in `platform-sdk/docs/proposals/` merged
  earlier
- an earlier PR in the stack that holds the design

A linked issue that only states the goal is not evidence of an agreed design. Agreement in a meeting can't be seen, so
the report asks rather than assumes.

## When it fires

A significant change with no evidence of agreement. The report lists the decisions the PR makes, with `file:line`, and
asks where they were discussed ([output.md](output.md#design-not-agreed)). If the answer is that they were, the review
continues. If not, the decisions are what the team should discuss first, possibly as separate PRs.

In author mode the report goes to the author before review is requested.

## Example

#28209, "chore: Use Falcon to measure simulated C2C for different network configurations". 1,333 lines added in 16
files, stacked on #28053, which is also open. The linked issue, #28190, is two sentences: move `NetworkSimulationTest`
to Falcon mini-nodes so the `consensus-network-simulation` module can be removed. It has no discussion. The PR makes
decisions the issue doesn't mention, and doesn't touch that module:

- the simulated network, which Turtle shares, now delivers each hop one tick earlier. Every Turtle test changes timing;
  `DocExamplesTest` reaches round 69 instead of 68.
- a new creation-to-consensus latency benchmark and its recorder, about 1,200 lines in Otter.
