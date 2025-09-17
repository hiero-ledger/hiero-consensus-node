# 🐢 Turtle Environment Guide

Deep dive into the Turtle simulated testing environment for fast, deterministic consensus testing.

## Table of Contents

- [🎯 Overview](#-overview)
- [🏗️ Network and Node Management](#-network-and-node-management)
- [🌐 Network Simulation](#-network-simulation)
- [⏱️ Time Management](#-time-management)
- [🎲 Deterministic Testing](#-deterministic-testing)
- [📝 Per-Node Logging](#-per-node-logging)

## 🎯 Overview

The Turtle environment provides a **fast, simulated testing environment** that enables deterministic consensus testing without real network delays or Docker overhead. It's ideal for:

- **Development**: Fast iteration cycles during development
- **CI/CD**: Rapid test execution in build pipelines
- **Regression Testing**: Deterministic results for catching regressions
- **Algorithm Validation**: Testing consensus behavior under controlled conditions

### Turtle Environment Components

The following diagram illustrates the Turtle environment's core architecture:

```mermaid
classDiagram
    class TestEnvironment {
        <<interface>>
    }
    class TurtleTestEnvironment {
    }
    class TurtleNetwork {
    }
    class TurtleNode {
    }
    class TurtleTimeManager {
    }
    class TurtleTransactionGenerator {
    }
    class Randotron {
    }
    TestEnvironment <|-- TurtleTestEnvironment
    TurtleTestEnvironment "1" --* "1" TurtleNetwork
    TurtleNetwork "1" --* "0..*" TurtleNode
    TurtleTestEnvironment "1" --* "1" TurtleTimeManager
    TurtleTestEnvironment "1" --* "1" TurtleTransactionGenerator
    TurtleTestEnvironment "1" --* "1" Randotron
```

The `TurtleTestEnvironment` is the main container that owns a single `TurtleNetwork`, `TurtleTimeManager`, `TurtleTransactionGenerator`, and `Randotron`. The `TurtleNetwork` can contain zero or more `TurtleNode` instances. `TurtleTestEnvironment` manages all the core components needed to run deterministic consensus tests with multiple nodes in a simulated, single-JVM environment.

## 🏗️ Network and Node Management

The following diagram shows the key interfaces and classes for managing networks and nodes in the Turtle environment:

```mermaid
classDiagram
    class Network {
        <<interface>>
        +nodes(): List~Node~*
        +addNodes(int) List~Node~*
        +addInstrumentedNode() InstrumentedNode*
        +start(): void*
        +freeze(): void*
        +shutdown(): void*
        +withTimeout(Duration): AsyncNetworkActions*
        +setVersion(SemanticVersion): void*
        +bumpConfigVersion(): void*
        +getConsensusResults(): MultipleNodeConsensusResults*
        +getLogResults(): MultipleNodeLogResults*
        +getPlatformStatusResults(): MultipleNodePlatformStatusResults*
        +getPcesResults(): MultipleNodePcesResults*
    }

    class AbstractNetwork {
        <<abstract>>
        #state: State
        #timeManager(): TimeManager*
        #transactionGenerator(): TransactionGenerator*
        #createFreezeTransaction(Instant): byte[]*
        #allNodesInStatus(PlatformStatus): BooleanSupplier
        #throwIfInState(State, String): void
        +withTimeout(Duration): AsyncNetworkActions
        +start(): void
        +freeze(): void
        +shutdown(): void
        +setVersion(SemanticVersion): void
        +bumpConfigVersion(): void
        +getConsensusResults(): MultipleNodeConsensusResults
        +getLogResults(): MultipleNodeLogResults
        +getPlatformStatusResults(): MultipleNodePlatformStatusResults
        +getPcesResults(): MultipleNodePcesResults
    }
    Network <|-- AbstractNetwork

    class TimeTickReceiver ["TurtleTimeManager.TimeTickReceiver"] {
        <<interface>>
        +tick(Instant): void*
    }

    class TurtleNetwork {
        #timeManager(): TurtleTimeManager
        #transactionGenerator(): TransactionGenerator
        #createFreezeTransaction(Instant): byte[]
        +nodes(): List~Node~
        +addNodes(int): List~TurtleNode~
        +addInstrumentedNode(): InstrumentedNode
        +tick(Instant): void
        ~destroy()
    }
    AbstractNetwork <|-- TurtleNetwork
    TimeTickReceiver <|-- TurtleNetwork

    class Node {
        <<interface>>
        +selfId(): NodeId*
        +platformStatus(): PlatformStatus*
        +version(): SemanticVersion*
        +configuration(): NodeConfiguration~?~*
        +isActive(): boolean
        +start()*
        +killImmediately()*
        +withTimeout(Duration): AsyncNetworkActions*
        +submitTransaction(byte[]): void*
        +setVerion(SemanticVersion): void*
        +bumpConfigVersion(): void*
        +getConsensusResult(): SingleNodeConsensusResult*
        +getLogResult(): SingleNodeLogResult*
        +getPlatformStatusResult(): SingleNodePlatformStatusResult*
        +getPcesResult(): SingleNodePcesResult*
    }

    class AbstractNode {
        <<abstract>>
        #selfId: NodeId
        #lifeCycle: LifeCycle
        #version: SemanticVersion
        #platformStatus: PlatformStatus
        +platformStatus(): PlatformStatus
        +selfId(): NodeId
        +version(): SemanticVersion
        +setVersion(SemanticVersion): void
        +bumpConfigVersion(): void
        #throwIfIn(LifeCycle, String): void
    }
    Node <|-- AbstractNode

    class TurtleNode {
        +killImmediately(): void
        +start(): void
        +withTimeout(Duration): AsyncNetworkActions
        +submitTransaction(byte[]): void
        +configuration(): NodeConfiguration
        +getConsensusResult(): SingleNodeConsensusResult
        +getLogResult(): SingleNodeLogResult
        +getPlatformStatusResult(): SingleNodePlatformStatusResult
        +getPcesResult(): SingleNodePcesResult
        +tick(Instant): void
        ~destroy()
    }
    AbstractNode <|-- TurtleNode
    TimeTickReceiver <|-- TurtleNode
    TurtleNetwork "1" --* "0..*" TurtleNode

    class AsyncNetworkActions {
        <<interface>>
        +start(): void*
        +freeze(): void*
        +shutdown(): void*
    }
```

The `Network` interface and its abstract implementation `AbstractNetwork` provide the foundation for managing collections of consensus nodes, with `TurtleNetwork` being a specific implementation that uses simulated time and fake network delays for testing. The `Node` interface and its `AbstractNode` base class represent individual consensus participants, with `TurtleNode` being the concrete implementation that integrates with the simulated network environment.

## 🌐 Network Simulation

### Simulated Network Delays

The `SimulatedNetwork` simulates realistic network conditions:

```mermaid
classDiagram
    class SimulatedNetwork {
        -random: Random
        -newlySubmittedEvents: Map~NodeId, List~PlatformEvent~~~
        -sortedNodeIds: List~NodeId~
        -eventsInTransit: Map~NodeId, PriorityQueue~EventInTransit~~~
        -gossipInstances: Map~NodeId, SimulatedGossip~~~
        -averageDelayNanos: long
        -standardDeviationDelayNanos: long
        +getGossipInstance(NodeId): SimulatedGossip
        +submitEvent(NodeId, PlatformEvent): void
        +tick(Instant): void
        -deliverEvents(Instant)
        -transmitEvents(Instant)
    }

    class Gossip {
        <<interface>>
        +bind(WiringModel, ...): void*
    }

    class SimulatedGossip {
        -network: SimulatedNetwork
        -selfId: NodeId
        -intakeEventCounter: IntakeEventCounter
        -eventOutput: StandardOutputWire~PlatformEvent~
        +receiveEvent(PlatformEvent)
        +bind(WiringModel, ...)
    }
    Gossip <|-- SimulatedGossip
    SimulatedNetwork "1" --* "0..*" SimulatedGossip

    class EventInTransit {
        +event: PlatformEvent
        +sender: NodeId
        +arrivalTime: Instant
    }
    EventInTransit --o "1" PlatformEvent
    SimulatedNetwork --* "0..*" EventInTransit
```

### Default Network Configuration

```java
// Default network parameters
static final Duration AVERAGE_NETWORK_DELAY = Duration.ofMillis(200);
static final Duration STANDARD_DEVIATION_NETWORK_DELAY = Duration.ofMillis(10);
```

### Event Transmission Flow

The following diagram shows how events flow through the simulated network:

```mermaid
sequenceDiagram
    box Node Alice
    participant P1 as PlatformWiring
    participant G1 as SimulatedGossip
    end

    participant TM as TimeManager

    participant SN as SimulatedNetwork

    box Node Bob
        participant G2 as SimulatedGossip
        participant P2 as PlatformWiring
    end

    P1->>G1: 1: Gossip.InputWire<PlatformEvent>
    G1->>SN: 1.1: submitEvent(submitterId, event)
    SN->>SN: 1.2: Add to newlySubmittedEvents list

    loop Time Advancement
        TM->>SN: 2: tick(now)

        SN->>SN: 2.1: deliverEvents()
        loop For each Node
            Note over SN: Check if events in eventsInTransit<br/> are due for delivery
            SN->>G2: 2.1.1: receiveEvent(event) [if due]
            G2->>P2: 2.1.2: Gossip.OutputWire<PlatformEvent>
        end

        SN->>SN: 2.2: transmitEvents()
        loop For each Node
            Note over SN: Calculate delivery times with Gaussian delay<br/>for all events in newlySubmittedEvents
            SN->>SN: 2.2.1: Add copied event to eventsInTransit queues
        end
    end
```

The simulation of network communication happens in two steps:

#### Submitting an event

1\. When a node wants to submit an event, it is received by its `SimulatedGossip` instance via the appropriate `InputWire`.

1.1 `SimulatedGossip` calls `SimulatedNetwork.submitEvent(submitterId, event)`.

1.2 The `SimulatedNetwork` adds the event to the `newlySubmittedEvents` list, which is a map of submitter IDs to the lists of events.

#### Delivering and transmitting events

2\. When the simulated time is advanced, the `TurtleTimeManager` calls `SimulatedNetwork.tick(now)`.

2.1 The `SimulatedNetwork` calls `deliverEvents(now)` to process all events that are due for delivery.

2.1.1 For each node, it checks if any events in the `eventsInTransit` queue are due for delivery based on their arrival time. If so, it calls `receiveEvent(event)` on the corresponding `SimulatedGossip` instance.

2.1.2 The `SimulatedGossip` instance then forwards the event to its `OutputWire` from which it is consumed by the platform wiring.

2.2 After delivering events, the `SimulatedNetwork` calls `transmitEvents(now)` to process newly submitted events.

2.2.1 For each node, it calculates the delivery times for all events in the `newlySubmittedEvents` list using a Gaussian distribution. It then adds these events to the `eventsInTransit` queue for that node.

## ⏱️ Time Management

The Turtle environment's time management is central to its deterministic behavior:

```mermaid
sequenceDiagram
    participant Test Method
    participant TurtleTimeManager
    participant FakeTime
    participant TurtleNetwork
    participant SimulatedNetwork
    participant TransactionGenerator
    participant TurtleNodes

    Test Method->>TurtleTimeManager: waitFor(Duration.ofSeconds(30))

    loop Every Granularity Period (10ms)
        TurtleTimeManager->>TurtleTimeManager: advanceTime(granularity)
        TurtleTimeManager->>FakeTime: tick(granularity)
        FakeTime->>FakeTime: Advance time by 10ms (configurable)
        TurtleTimeManager->>TurtleNetwork: tick(now)
        TurtleNetwork->>SimulatedNetwork: tick(now)
        SimulatedNetwork->>SimulatedNetwork: Process events in transit
        SimulatedNetwork->>TurtleNodes: Deliver events due now
        TurtleNetwork->>TransactionGenerator: tick(now, nodes)
        TransactionGenerator->>TurtleNodes: Generate transactions for active nodes
        TurtleNetwork->>TurtleNodes: tick(now)
        TurtleNodes->>TurtleNodes: Process consensus logic
    end

    TurtleTimeManager-->>Test Method: Time advancement complete
```

This sequence shows how time advancement drives the entire simulation, ensuring deterministic execution. While code is running, time does not advance. When the test calls `waitFor()` or a related method on the `TurtleTimeManager`, it advances time in fixed granularity steps (default 10ms) until the specified duration is reached. During each tick, the `SimulatedNetwork` processes events, the `TransactionGenerator` creates transactions submitting them to active nodes, and the nodes execute their consensus logic.

## 🎲 Deterministic Testing

### Randotron Usage

The Turtle environment uses `Randotron` for all randomization:

```java
final Randotron randotron = randomSeed == 0L
    ? Randotron.create()
    : Randotron.create(randomSeed);
```

The same `Randotron` instance is used throughout the test, ensuring that all random operations are consistent and reproducible. This is crucial for deterministic testing, where the same inputs should yield the same outputs across runs.

> [!NOTE]
> When `randomSeed = 0`, a truly random seed is generated for each test run.

### Reproducible Tests with Random Seeds

Use `@TurtleSpecs` to control randomness and ensure reproducible test results:

```java
@OtterTest
@TurtleSpecs(randomSeed = 42)
void testDeterministicBehavior(@NonNull final TestEnvironment env) throws InterruptedException {
    // This test will produce identical results every time
    final Network network = env.network();
    network.addNodes(4);
    network.start();

    env.timeManager().waitFor(Duration.ofSeconds(30));

    // Results will be identical across runs
    final long lastRound =
            network.newConsensusResults().results().getFirst().lastRoundNum();

    // This assertion will always pass with seed=42
    assertThat(lastRound).isEqualTo(35);
}
```

## 📝 Per-Node Logging

All Turtle nodes share one JVM, so log routing depends on the `nodeId` stored in Log4j2's
`ThreadContext`. Enter node code paths inside a scope and let the helpers propagate the MDC:

```java
try (var scope = NodeLoggingContext.install(Long.toString(nodeId.id()))) {
    scheduler = NodeLoggingContext.wrap(Executors.newSingleThreadScheduledExecutor(
            new ContextAwareThreadFactory()));
    scheduler.schedule(NodeLoggingContext.wrap(() -> logger.info("tick")), 1, TimeUnit.SECONDS);
}
```

- Wrap every `ExecutorService` / `ScheduledExecutorService` with `NodeLoggingContext.wrap(...)`
  before handing it to platform components or `CompletableFuture`.
- When a framework forces the common fork-join pool, wrap the runnable/callable itself with
  `NodeLoggingContext.wrap(...)` so the captured context is restored on execution.
- Avoid direct `new Thread(...)`; if unavoidable, build it through `ContextAwareThreadFactory` to
  apply the snapshot automatically.

With propagation in place the routing appender emits `node-${ctx:nodeId}.log` files and the
`node-unknown` fallback remains empty during normal operation.

## 🔗 Related Documentation

|                        Guide                         |        Description        |
|------------------------------------------------------|---------------------------|
| [🏁 Getting Started](getting-started.md)             | Setup and your first test |
| [🏛️ Architecture](architecture.md)                  | Framework design overview |
| [✍️ Writing Tests](writing-tests.md)                 | Test development guide    |
| [🐢 Turtle Environment](turtle-environment.md)       | Simulated testing guide   |
| [🐳 Container Environment](container-environment.md) | Docker-based testing      |
