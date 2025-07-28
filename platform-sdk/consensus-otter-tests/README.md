# 🦦 Otter Test Framework

A comprehensive Java-based testing framework for the Consensus Module, supporting both simulated and containerized environments. More environments will be added in the future.

## 🚀 A first Otter Test

This example demonstrates a simple test that checks if consensus is reached and the logs contain no error messages.

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

        // 5. Verify consensus was reached and advanced past round 2
        assertThat(network.getConsensusResults()).haveEqualCommonRounds().haveAdvancedSinceRound(2);

        // 6. Check for no error-level log messages
        assertThat(network.getLogResults()).haveNoErrorLevelMessages();
    }
```

For the full description of the test, see [🏁 Getting Started](docs/getting-started.md).

## ✨ Key Features

- **🎯 Unified Testing API** - Write tests once, run in multiple environments
- **🌐 Network Simulation** - Configurable network delays and failures
- **😈 Malicious Node Testing** - Simulate and test malicious node behavior
- **🔧 Special Purpose API** - Optimized for the simple definition of typical scenarios
- **⏱️ Time Management** - Precise control over simulated and real time
- **🔍 Consensus Validation** - Built-in assertions for consensus behavior
- **🔄 Transaction Generation** - Automated transaction creation and submission

## ⚡ Quick Commands

```bash
# Run Turtle tests (fast, simulated)
./gradlew testTurtle

# Run specific Turtle test
./gradlew testTurtle --tests "org.hiero.otter.test.HappyPathTest"

# Run Container tests (production-like)
./gradlew testContainer
```

## 📁 Project Structure

```
platform-sdk/consensus-otter-tests/
├── docs/                      # 📚 Documentation
├── src/testFixtures/          # 🔧 Framework implementation
├── src/test/                  # ✅ Example tests
├── build.gradle.kts           # 🏗️ Build configuration
└── README.md                  # 📖 This file
```

## 📚 Documentation

|                           Guide                           |          Description          |
|-----------------------------------------------------------|-------------------------------|
| [🏁 Getting Started](docs/getting-started.md)             | Setup and your first test     |
| [🏛️ Architecture](docs/architecture.md)                  | Framework design overview     |
| [✍️ Writing Tests](docs/writing-tests.md)                 | Test development guide        |
| [✅ Assertions API](docs/assertions-api.md)                | Validation capabilities       |
| [⚙️ Configuration](docs/configuration.md)                 | Environment and node settings |
| [🐢 Turtle Environment](docs/turtle-environment.md)       | Simulated testing guide       |
| [🐳 Container Environment](docs/container-environment.md) | Docker-based testing          |
| [🔧 Troubleshooting](docs/troubleshooting.md)             | Common issues and debugging   |
