# 🦦 Otter Test Framework

A comprehensive Java-based testing framework for the Consensus Module, supporting both simulated and containerized environments. More environments will be added in the future.

## 🚀 Quick Start

https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-otter-tests/src/test/java/org/hiero/otter/test/DocExampleTest.java#L19-L42

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
