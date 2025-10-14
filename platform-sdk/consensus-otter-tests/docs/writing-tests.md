# ✍️ Writing Tests Guide

Comprehensive guide to authoring effective consensus tests with the Otter framework.

## Hello World Example

Let's start with a simple "Hello World" example that demonstrates the basic structure of an Otter test:

```java

@OtterTest
void testConsensus(@NonNull final TestEnvironment env) throws InterruptedException {
    // 1. Get the network and time manager
    final Network network = env.network();
    final TimeManager timeManager = env.timeManager();

    // 2. Create a 4-node network
    network.addNodes(4);

    // 3. Start the network
    network.start();

    // 4. Wait 30 seconds while the network is running
    timeManager.waitFor(Duration.ofSeconds(30));

    // 5. Check for no error-level log messages
    assertThat(network.newLogResults()).haveNoErrorLevelMessages();
}
```

This test creates a 4-node consensus network, runs it for 30 seconds, and verifies that no error-level log messages were
generated.

### Breaking Down the Example

#### The `@OtterTest` Annotation

```java

@OtterTest
void testConsensus(@NonNull final TestEnvironment env) throws InterruptedException {
```

The `@OtterTest` annotation marks this method as an Otter test. It replaces the standard JUnit `@Test` annotation and
tells the framework to:

- Inject a `TestEnvironment` parameter
- Set up the appropriate testing environment (Turtle or Container)
- Handle resource cleanup automatically

#### Getting the Network

```java
final Network network = env.network();
final TimeManager timeManager = env.timeManager();
```

The `TestEnvironment` provides access to the core framework components. The `network()` method returns a `Network`
interface that allows you to:

- Add nodes to create a consensus network
- Start and stop the network
- Access results and status information

The `timeManager()` method provides a `TimeManager` interface that controls time progression in the test. It allows you
to:

- Wait for specific durations
- Wait for conditions to be met

#### Creating Nodes

```java
network.addNodes(4);
```

This creates 4 consensus nodes that will participate in the network. The nodes are not started yet - they're just
created and configured. The number 4 provides a good balance for basic testing:

- Enough nodes to demonstrate consensus behavior
- Not so many as to slow down the test unnecessarily

#### Starting the Network

```java
network.start();
```

This starts all nodes in the network and begins the consensus process. The nodes will:

- Initialize their platform components
- Begin gossiping with each other
- Start producing consensus rounds
- Progress through platform status states (OBSERVING → CHECKING → ACTIVE)

#### Waiting for Execution

```java
timeManager.waitFor(Duration.ofSeconds(30));
```

The `TimeManager` controls time in the test. This call advances time by 30 seconds, allowing the network to:

- Reach a stable state
- Produce multiple consensus rounds

The behavior depends on the environment:

- **Turtle environment**: Simulated time advances in controlled increments
- **Container environment**: Real time passes

#### Validating Results

```java
assertThat(network.newLogResults()).haveNoErrorLevelMessages();
```

This assertion checks that no ERROR-level log messages were produced during the test execution. It's a good basic health
check that verifies:

- The network started successfully
- No critical errors occurred during consensus
- All nodes operated normally

The `assertThat()` method comes from `OtterAssertions` and provides a fluent API for validating consensus behavior
similar to the popular AssertJ library.

### What Happens Behind the Scenes

When this test runs, the framework:

1. **Environment Selection**: Chooses Turtle or Container environment based on configuration
2. **Resource Setup**: Creates the testing infrastructure
3. **Test Execution**: Runs your test method
4. **Automatic Cleanup**: Destroys all nodes and releases resources

The entire process is managed automatically - you focus on the test logic, and the framework handles the infrastructure.

## TestEnvironment Setup

### Configuration

Test environments can be configured using environment-specific annotations. The Turtle environment supports
`@TurtleSpecs` for deterministic testing configuration, while Container environments will support `@ContainerSpecs` in
future versions.

*For a complete running example, see the code example in the
document [Turtle Environment](turtle-environment.md#-deterministic-testing).*

### Capabilities

Environment capabilities define what testing features each environment supports. All available capabilities are listed
in the `Capability` enum, and each environment specifies which capabilities it provides.

Test developers specify required capabilities using `@OtterTest.requires()`:

```java
// This test requires the capability to reconnect nodes
@OtterTest(requires = Capability.RECONNECT)
void testSimpleNodeDeathReconnect(@NonNull final TestEnvironment env) throws InterruptedException {

    // ... test logic here ...
}
```

Tests only execute on environments supporting all required capabilities. Tests that require capabilities not supported
by the selected environment will be treated as disabled.

When running the Gradle `test` task, the framework automatically selects the fastest environment that supports all
required capabilities. This will be evaluated for each test method individually, allowing you to run tests in different
environments based on their requirements.

### Parameterized Tests

The `@OtterTest` annotation supports parameterized tests using JUnit 5's `@ParameterizedTest` feature. You can use
`@ValueSource`, `@EnumSource`, or any other JUnit 5 source to run the same test logic with different parameters.

> **Please note:** The `TestEnvironment` parameter must always be the last parameter in the method signature. This is a JUnit 5 limitation.

```java
@OtterTest
@ParameterizedTest
@MethodSource("provideWeightDistributions")
void testWithDifferentWeightDistributions(@NonNull final List<Long> weightDistribution, @NonNull final TestEnvironment env) {
    final Network network = env.network();
    weightDistribution.forEach(weight -> network.addNode().weight(weight));
    network.start();

    env.timeManager().waitFor(Duration.ofSeconds(5));

    assertThat(network.newLogResults()).haveNoErrorLevelMessages();
}

private static Stream<Arguments> provideWeightDistributions() {
    return Stream.of(
            Arguments.of(List.of(1L, 1L, 1L, 1L)), // 4 nodes, equal weights
            Arguments.of(List.of(5L, 5L, 5L, 15L)), // 4 nodes, one dominant weight
            Arguments.of(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L)) // 9 nodes, increasing weights
    );
}
```

## Node Configuration

Individual nodes can be configured while the network is not running to customize their behavior for specific test
scenarios:

```java
@OtterTest
void testNodeConfiguration(@NonNull final TestEnvironment env) throws InterruptedException {
    final Network network = env.network();
    final List<Node> nodes = network.addNodes(4);

    // Set the rounds non-ancient and expired to smaller values to allow nodes to fall behind quickly
    for (final Node node : nodes) {
        node.configuration()
                .set(ConsensusConfig_.ROUNDS_NON_ANCIENT, 5L)
                .set(ConsensusConfig_.ROUNDS_EXPIRED, 10L);
    }

    network.start();

    // ... more test logic here ...
}
```

### Breaking Down the Configuration

#### Node Configuration API

```java
node.configuration()
        .set(ConsensusConfig_.ROUNDS_NON_ANCIENT, 5L)
        .set(ConsensusConfig_.ROUNDS_EXPIRED, 10L);
```

The `configuration()` method returns a `NodeConfiguration` interface that allows you to override platform properties.
The API uses method chaining, so you can set multiple properties in sequence.

#### Configuration Timing

> [!IMPORTANT]
> All node configuration must be done while the node is not running. Configuration changes of a running node fail
> with an `Exception`.

The configuration is applied when the node initializes its platform, which happens during `network.start()`. Once a node
is running, its configuration cannot be changed without restarting it.

## Result Types and Assertions

The Otter framework provides a rich assertion system inspired by the [AssertJ](https://assertj.github.io/doc/) library,
offering fluent and readable test validations.

### Result Types

The framework collects various types of results during test execution:

|      Result Type      |           Description           |           Single Node            |               Network                |
|-----------------------|---------------------------------|----------------------------------|--------------------------------------|
| **Consensus Results** | Consensus rounds and validation | `node.newConsensusResult()`      | `network.newConsensusResults()`      |
| **Log Results**       | Log messages and analysis       | `node.newLogResult()`            | `network.newLogResults()`            |
| **Platform Status**   | Status progression tracking     | `node.newPlatformStatusResult()` | `network.newPlatformStatusResults()` |
| **PCES Results**      | Pre-consensus event storage     | `node.newPcesResult()`           | `network.newPcesResults()`           |

Each result type is available for individual nodes or the entire network, allowing targeted validation at different scopes.

### Filtering and Management

Results can be filtered to focus on specific aspects:

```java
    // Ignore specific nodes
    network.newLogResults().suppressingNode(problematicNode);

    // Filter out expected log markers
    network.newLogResults().suppressingLogMarker(STARTUP);

    // Get a results object and clear the accumulated data
    final MultipleNodeConsensusResults consensusResults = network.newConsensusResults();
    consensusResults.clear();
```

The `clear()` method removes all data collected so far, useful for focusing assertions on specific test phases.

### Snapshot Assertions

Standard assertions validate results at a specific point in time:

```java
@OtterTest
void testWithRegularAssertion(@NonNull final TestEnvironment env) throws InterruptedException {
    final Network network = env.network();
    network.addNodes(4);
    network.start();

    final TimeManager timeManager = env.timeManager();
    timeManager.waitFor(Duration.ofSeconds(30));

    // Fluent assertion with method chaining
    assertThat(network.newConsensusResults())
            .haveEqualCommonRounds()
            .haveAdvancedSinceRound(2);

    assertThat(network.newLogResults().suppressingLogMarker(STARTUP))
            .haveNoErrorLevelMessages();
}
```

### Continuous Assertions

Continuous assertions monitor conditions throughout test execution, failing fast when violations occur:

```java
@OtterTest
void testWithContinuousAssertion(@NonNull final TestEnvironment env) throws InterruptedException {
    final Network network = env.network();
    network.addNodes(4);

    // Set up monitoring before starting the network
    assertContinuouslyThat(network.getConsensusResults())
        .haveEqualRounds();

    assertContinuouslyThat(network.getLogResults())
        .suppressingLogMarker(STARTUP)
        .haveNoErrorLevelMessages();

    network.start();
    timeManager.waitFor(Duration.ofSeconds(30));
}
```

Continuous assertions automatically validate throughout execution. They provide immediate feedback when errors occur,
rather than waiting until the end of the test.

Another benefit of continuous assertions is that they can suppress assertions over a period of time during a test.

```java
    // Continuous assertion with that checks no errors are written to the log
    final MultipleNodeLogResultsContinuousAssert assertion =
            assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();

    // Suppress RECONNECT log marker during the test
    assertion.startSuppressingLogMarker(RECONNECT);

    // ... test logic that is expected to generate RECONNECT error messages

    // Stop suppressing the RECONNECT log marker
    assertion.stopSuppressingLogMarker(RECONNECT);
```

## Test Output

Tests generate detailed logs at environment-specific locations.

### Turtle Environment Logs

The logs can be found in the `build/turtle/` directory:

```
build/turtle/
├── node-0/
│   └── output
│       ├── swirlds-hashstream
│       │   └── swirlds-hashstream.log
│       └── swirlds.log
└── node-1/
    └── output
        ├── swirlds-hashstream
        │   └── swirlds-hashstream.log
        └── swirlds.log
```

### Container Environment Logs

Container tests run in Docker, and logs are accessible via Docker commands:

```
docker logs <container-id>      # Container environment
```

After the test run, the logs are also copied to `build/container/`:

```
build/container/
├── node-0/
│   └── output
│       ├── swirlds-hashstream
│       │   └── swirlds-hashstream.log
│       └── swirlds.log
└── node-1/
    └── output
        ├── swirlds-hashstream
        │   └── swirlds-hashstream.log
        └── swirlds.log
```

## 🔗 Related Documentation

|                        Guide                         |        Description        |
|------------------------------------------------------|---------------------------|
| [🏁 Getting Started](getting-started.md)             | Setup and your first test |
| [🏛️ Architecture](architecture.md)                  | Framework design overview |
| [✍️ Writing Tests](writing-tests.md)                 | Test development guide    |
| [🐢 Turtle Environment](turtle-environment.md)       | Simulated testing guide   |
| [🐳 Container Environment](container-environment.md) | Docker-based testing      |
