# Quiescence

---

## Summary

Quiescence is a feature that stops event creation when there are no transactions. The purpose of this is to reduce the
amount of data produced and bandwidth used by low volume networks.

| Metadata           | Entities             | 
|--------------------|----------------------|
| Designers          | Lazar Petrović       |
| Functional Impacts | Consensus, Execution |
| Related Proposals  | N/A                  |
| HIPS               | N/A                  |

---

## Purpose and Context

The purpose of this is to reduce the amount of data produced and bandwidth used by low volume networks. When there are
no transactions being submitted to the network, the network can stop creating events and thus stop gossipping and
producing blocks. This can drastically reduce the amount of data that needs to be stored long term as well as the amount
of bandwidth used by the network.

### Requirements

- When transactions stop being submitted to the network, the network should stop producing events in a timely manner.
- The amount of time it takes the network to stop should be close to the C2C time of the network.
- Once a transaction is submitted to the network, the network should start producing events and reach consensus on this
  newly submitted transaction.
- No existing functionality should be affected.
- Metrics should not produce misleading information due to the pause in event creation.

---

## Quiescence mechanisms

The quiescence feature can be broken up as follows:

- Detecting when to quiesce (quiescence conditions)
- Quiescing
- Breaking quiescence
- Side effects of quiescence

### Quiescence conditions

In its simplest form, detecting when to quiesce is done by counting non-ancient transactions. If there are no
non-ancient transactions, there is nothing to reach consensus, so we can stop creating events. Additionally, we should
also check if there are any pending transactions, if there are, we should not quiesce.

The only complication comes from state/block signature transactions. These transactions do not need to reach consensus.
However, they do need to be gossiped (for block signatures it has not yet been decided if they need to reach consensus
as of this writing, this document will proceed assuming they don't need to). If we want a fully signed state/block,
we need to create an event with this transaction and then stop creating events immediately after. If we create too many
events, we can reach consensus on another round. If we reach consensus again, we will have another state/block to sign.
The consensus module will need to be able to distinguish between signature transactions and normal transactions. An
additional API is needed for this.

### Quiescing

Once all the conditions are met, we can quiesce. This is done by simply stopping event creation. The consensus module
will stay in this state until:
- A transaction is submitted to the node that needs to be put into an event
- OR a node sends us an event with transactions that need to reach consensus

If either of these conditions is met, we will break quiescence.

### Breaking quiescence

### Side effects of quiescence

- old state detector
- platform status

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

Describe any new or modified behavior. What are the new or modified algorithms and protocols? Include any diagrams that
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
