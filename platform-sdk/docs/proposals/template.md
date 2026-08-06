# Proposal Title Goes Here

---

## Summary

Give a 1-3 sentence summary of the capability in the design proposal.

| Metadata           | Entities                            | 
|--------------------|-------------------------------------|
| Designers          | John, Mary, alpha-team              |
| Functional Impacts | Services, DevOps, Mirror Node, etc. |
| Related Proposals  | Proposal-1, Proposal-2              |
| HIPS               | HIP-1, HIP-2,                       |

---

## Purpose and Context

Why is the design proposal necessary? Describe the background and purpose of the design proposal at a high level.

### Dependencies, Interactions, and Implications

What are the dependencies and pre-requisites of this proposal?  
What other proposals, capabilities, and teams are impacted by this proposal?
What does the proposal enable once it is completed?

Remove this section if not applicable.

### Requirements

Describe the requirements and acceptance criteria that the design proposal must satisfy.

### Design Decisions

Describe the decisions made and the reasons why.

#### Alternatives Considered

Describe any alternatives considered and why they were not chosen.

If possible, provide a table illustrating the options, evaluation criteria, and scores that factored into the decision.


---

## Changes

### Architecture and/or Components

Describe any new or modified components or architectural changes. This includes thread management changes, state
changes, disk I/O changes, platform wiring changes, etc. Include diagrams of architecture changes.

Remove this section if not applicable.

### Module Organization and Repositories

Describe any new or modified modules or repositories.

Remove this section if not applicable.

### Core Behaviors

Describe any new or modified behavior. What are the new or modified algorithms and protocolFactories? Include any diagrams that
help explain the behavior.

Remove this section if not applicable.

### Public API

Describe any public API changes or additions. Include stakeholders of the API.

Examples of public API include:

* Anything defined in protobuf
* Any functional API that is available for use outside the module that provides it.
* Anything written or read from disk

Code can be included in the proposal directory, but not committed to the code base.

Remove this section if not applicable.

### Configuration

Describe any new or modified configuration.

Remove this section if not applicable.

### Metrics

Are there new metrics? Are the computation of existing metrics changing? Are there expected observable metric impacts
that change how someone should relate to the metric?

Remove this section if not applicable.

### Performance

Describe any expected performance impacts. This section is mandatory for platform wiring changes.

Remove this section if not applicable.

---

## Test Plan

### Unit Tests

Describe critical test scenarios and any higher level functionality tests that can run at the unit test level.

Examples:

* Subtle edge cases that might be overlooked.
* Use of simulators or frameworks to test complex component interaction.

Remove this section if not applicable.

### Integration Tests

Describe any integration tests needed. Integration tests include migration, reconnect, restart, etc.

Remove this section if not applicable.

### Performance Tests

Describe any performance tests needed. Performance tests include high TPS, specific work loads that stress the system,
JMH benchmarks, or longevity tests.

Remove this section if not applicable.

---

## Implementation and Delivery Plan

How should the proposal be implemented? Is there a necessary order to implementation? What are the stages or phases
needed for the delivery of capabilities? What configuration flags will be used to manage deployment of capability? 
