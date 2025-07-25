# Refactor of the `Roster` usage on the platform Code

## Motivation

* Too many abstractions and entry points with repeated operations
* Navigation Cost: Current implementation requires O(n) operations to retrieve a node information from a roster.
* Lacks support for more expressive or directly usable types (e.g., returning `Certificate` or `Address` instead of raw bytes).
* No memoization or internal caching to avoid repeated parsing or conversion costs.

## Description of the current code and how `Roster` is used

The platform's state contains two core components for handling rosters:

* "ROSTER_STATES": A singleton object.

* "ROSTERS": A key-value state (map-like structure).

### RosterStates (Singleton)

The RosterStates object is composed of:

* Candidate Roster Hash (currently unused).

* List of Round-to-Roster-Hash Pairs:

  * Behaves like a stack, always containing two elements during runtime:
    * The first (newest) element: Represents the active roster.

    * The second (older) element: Represents the previous roster.

### Rosters (Key-Value State)

The Rosters object is a map from a roster hash to a roster object.

Each `Roster` object is a pbj object containing:

* A list of RosterEntries. Each roster entry includes:
  * A node ID (long).
  * A certificate (as a byte[]).
  * A list of addresses, each consisting of:
    - An address (byte[] representing an ipv4 address).
    - A port (int).
    - A domain name (String).

### Diagram

![img_3.png](img_3.png)

### Accessing State – Readable and Writable Stores

* Readable Store: - Allows querying-
  * Active Roster
  * Previous Roster
  * Candidate Roster
  * History (list of round-to-roster-hash pairs)

![img_4.png](img_4.png)

* Writable Store: - supports -
  * Setting the Active Roster (thus setting the previous roster too and updating the history)
  * Setting the Candidate Roster

![img_5.png](img_5.png)

### Platform

The platform receives both `Roster` pbj objects and `RosterHistory` from the application in the start-up phase, or from the state
in the restart.
Consumes Roster via different utility classes:

* `RosterUtils`: A collection of static methods. Provides navigation of roster elements by node and updates the state.
  ![img.png](img.png)

* `RosterHistory`: A POJO. returns the roster applicable to a given round. also returns previous and active roster.

![img_1.png](img_1.png)

* `RosterRetriever`: Provides access to active, previous, and candidate rosters, also allows to modify the state. It's mostly used by services but is owned by platform.
  ![img_2.png](img_2.png)

### Where rosters are used in platform code

- ReconnectStateLoader: Converts a roster (current roster) to json and logs it
- SignedStateFileWriter: Converts a roster to json and writes it in a file, The roster used is read directly from the state
- IssMetrics: Requires iterating over the list of nodes in the roster. Requires each node's weight. Requires the roster total weight.
- DefaultEventSignatureValidator: Needs a Certificate from a round and Roster
- ReconnectStateLoader, ReconnectLearner, ReconnectLearnerFactory: use the current roster to send it to the DefaultSignedStateValidator
- DefaultSignedStateValidator: Requires each node's gossip certificate
- ConsensusRound/StreamedRound: holds the roster of that particular consensus round
  - used by UptimeTracker to iterates over all entries and adds their weight and the total weight of the roster to update metrics
  - used by ISSTestingToolConsensusStateEventHandler to iterates over all entries and adds their weight and the total weight of the roster
- SyncGossipModular: creates a PeerInfo object out of each RosterEntry. The used roster is the current roster.
- IssDetector: Requires the node's weight. Requires the roster's total weight. receives the current roster
- DefaultBranchReporter: Requires the node's weight. Requires iterating over the list of nodes in the roster. The used roster is the current roster.
- ConsensusImpl: Number of participants and each participant's weight. stronglySeeP uses an index instead of nodeId's to retrieve the participant's weight. The used roster is the current roster.
- Platform#getCurrentRoster() a method that is only used in tests and test applications and should be removed
  - SwirldsStateManagerTests: number of entries in the roster
  - WinTabAddresses: iterate over the roster entries, needs id, host, name, port.
  - SwirldsGui: number of entries in the roster
  - StressTestingToolMain: number of entries in the roster
  - StatsSigningTestingToolMain: number of entries in the roster
  - StatsDemoMain: number of entries in the roster
  - PlatformTestingToolMain: number of entries in the roster
  - PlatformTestingToolConsensusStateEventHandler: Heavily uses the roster to index transformation
  - MigrationTestingToolMain: number of entries in the roster.
  - CryptocurrencyDemoState: iterate over the entries in the roster, retrieve node id, number of entries in the roster.
  - TransactionGenerator: number of entries in the roster.

## Proposed changes

We need new abstractions built from `Roster` objects that provide better search capabilities.
We also need to provide the possibility for all components to cache data they need to access from a roster for a particular round.
This logic will be repeated for different use cases in the platform code, so it's better to have it as centralized piece that can be reused.
We need to make sure that each component have its own local copy of these elements and we don't have a single shared data structure that needs to deal with concurrent changes.

### New Abstractions

![roster-data-all.png](roster-data-all.png)

* `RosterData` class:
  Encapsulates roster information as an immutable data structure:
  Provides nodeIds(), weight(NodeId), getTotalWeight(), size(), index(NodeId), and lookup utilities.
  Implements equality, hashing, JSON serialization, and utility methods for integration.

* `RosterDataEntry` record: Immutable record representing a single node's details like weight, gossip certificate, and endpoints.

* `RosterHistory`: Tracks the history of rosters across rounds and allows retrieval based on round numbers or hashes. A replacement for `org.hiero.consensus.roster.RosterHistory`.
  It will be mutable, it will return RosterData objects, it will allow components to maintain their own history and manage its updates, it will cache values.

* `CustomRosterDataMap`: mutable data map with custom transformation support and caching of information retrieved from a roster, a nodeId and a round number.

* `WeightMap`: Specialized CustomRosterDataMap providing fast access to node weights.

* `SignaturesMap`: Specialized CustomRosterDataMap providing cached Signature instances by node and round

### Roster's updated interactions

#### List of tasks previous to DAB

##### Create all new objects

Create all the new objects according to the class diagram and description on top. Place all new objects on `consensus-model` module.
* `RosterData`
* `RosterDataEntry`
* `RosterHistory`
* `RosterHistoryBuilder`
* `CustomRosterDataMap`
* `WeightMap`
* `SignaturesMap`

##### Change all uses of `Roster` to `RosterData`

Change all protobuf `Roster` usages to `RosterData`. After this task is completed platform should not use Roster protobuf object directly.

###### Affected pieces

* Components:
  * `org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator`
  * `com.swirlds.platform.ConsensusImpl`
  * `com.swirlds.platform.gossip.SyncGossipModular`
  * `com.swirlds.platform.event.validation.DefaultEventSignatureValidator`
  * `com.swirlds.platform.event.branching.DefaultBranchReporter`

```Note
after this change, `ConsensusImpl` will not require a node-to-roster-index map anymore and will be able to use the roterData index operation with the same O(1) complexity
```

* Platform support code:
  * `com.swirlds.platform.builder.PlatformBuilder`
  * `com.swirlds.platform.system.Platform`
  * `com.swirlds.platform.ReconnectStateLoader`
  * `com.swirlds.platform.state.address.RosterMetrics`
  * `com.swirlds.platform.recovery.internal.EventStreamRoundIterator`
  * `com.swirlds.platform.recovery.internal.StreamedRound`
  * `com.swirlds.platform.uptime.UptimeTracker`
* TipsetEventCreator support code:
  * `org.hiero.consensus.event.creator.impl.tipset.TipsetWeightCalculator`
  * `org.hiero.consensus.event.creator.impl.tipset.TipsetTracker`
  * `org.hiero.consensus.event.creator.impl.tipset.Tipset`
  * `org.hiero.consensus.event.creator.impl.tipset.TipsetMetrics`
* ConsensusImpl support code:
  * `com.swirlds.platform.consensus.ConsensusRounds`
  * `org.hiero.consensus.model.hashgraph.ConsensusRound`
  * `org.hiero.consensus.model.hashgraph.Round`
* SyncGossipModular support code
  * `com.swirlds.platform.Utilities`
  * `com.swirlds.platform.gossip.DefaultIntakeEventCounter`
* DefaultSignedStateValidator support code
  * `com.swirlds.platform.reconnect.DefaultSignedStateValidator`
  * `com.swirlds.platform.state.signed.SignedStateValidator`
  * `com.swirlds.platform.state.signed.SignedState`
  * `com.swirlds.platform.state.signed.SignedStateInfo`
  * `com.swirlds.platform.state.signed.SignedStateValidationData`
  * `com.swirlds.platform.metrics.IssMetrics`
* ReconnectStateLoader support code
  * `com.swirlds.platform.ReconnectStateLoader`
  * `com.swirlds.platform.reconnect.ReconnectLearnerFactory`
  * `com.swirlds.platform.reconnect.ReconnectLearner`

#### Change`RosterData` to `RosterHistory` as component's construction parameter

Components needing a `RosterData` will receive a `RosterHistory` instance instead.
`PlatformComponentBuilder` will receive a `RosterHistoryBuilder` initialized with the list of roundRoster pairs and the list of rosters.
At the moment of creation of each component, the `PlatformComponentBuilder` will use the `RosterHistoryBuilder` to create a new instance of the rosterHistory and provide it to the components.
The old RosterHistory object is removed from platformBuildingBlocks, each component will have their own `RosterHistory` in order to avoid making that class thread-safe.

When using the rosterHistory to retrieve a RosterData object we can still use `getCurrentRoster` at this stage.

After this task, the old RosterHistory object will be deleted.

##### Affected pieces

* `com.swirlds.platform.event.branching.DefaultBranchReporter`
* `com.swirlds.platform.ConsensusImpl`
* `com.swirlds.platform.state.iss.DefaultIssDetector`
* `org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator`
* `com.swirlds.platform.gossip.SyncGossipModular`
* `com.swirlds.platform.reconnect.DefaultSignedStateValidator`

##### Removing from scope:

As Tipset objects store `roster` objects, is still unknown what should happen with old tips when we implement DAB, possibly they need to be recalculated, so there is no much benefit on changing these classes to use the roster history
* `org.hiero.consensus.event.creator.impl.tipset.TipsetWeightCalculator`
* `org.hiero.consensus.event.creator.impl.tipset.TipsetTracker`
* `org.hiero.consensus.event.creator.impl.tipset.Tipset`
* `org.hiero.consensus.event.creator.impl.tipset.TipsetMetrics`

#### retrieve `RosterData` form `RosterHistory` using `RosterHistory#getRosterForRound`

For DAB, all accesses to the RosterHistory needs to provide the round to get the applicable roster.

* `com.swirlds.platform.event.validation.DefaultEventSignatureValidator`: is already accessing using the history and the round, so no impact here.
* `com.swirlds.platform.event.branching.DefaultBranchReporter`: `#reportBranch` will use the event's birth round. `#updateEventWindow` will use the event's window latestConsensusRound. The constructor will still use getCurrentRoster.
* `com.swirlds.platform.ConsensusImpl`: In all places where consensus is using the roster we have access to the event's birth round
* `org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator`: `#registerEvent` can use the event's birth round. TipsetEventCreator's constructor will still use  `rosterHistory.getCurrentRoster()` for the creation of tipsetTracker, tipsetMetrics, tipsetWeightCalculator. networkSize field can be removed and calculated dynamically using the event window.
* `com.swirlds.platform.state.iss.DefaultIssDetector`: `#handlePostconsensusSignature`:can access roster using the round in the signaturePayload; `#shiftRoundDataWindow` can access the roster using the roundNumber parameter.
* `com.swirlds.platform.gossip.SyncGossipModular` The constructor will still use  `rosterHistory.getCurrentRoster()` unless we can create the component sending the round as another parameter.

#### Modify components to a subtype of `CustomRosterDataMap`

- Use a `WeightMap` in:
  - `IssDetector`
  - `DefaultBranchReporter`
  - `ConsensusImpl`
- Use a `CustomRosterDataMap` that allows to create `Signature` in `DefaultEventSignatureValidator`.
- Use a `CustomRosterDataMap` that allows to create `PeerInfo` instances in `SyncGossipModular` (this will be a future task given that is only possible if Gossip processes some sort of round)

#### List of tasks after DAB

After DAB the relationship between the components will be as follows:
![roster-updates.png](roster-updates.png)

#### Consensus needs to inform all the other components about new rosters for future rounds

#### Components should update their local copy of the RosterHistory

#### Tipsets needs to be refreshed when a new RosterData applies to the current Round

#### Gossip needs to update its internal state according to the roster for newer rounds

#### Event window processing should clean their local copy of the `RosterHistory`
