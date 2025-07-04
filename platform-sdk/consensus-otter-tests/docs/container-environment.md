# 🐳 Container Environment Guide

[Home](../README.md) > Container Environment

Comprehensive guide to Docker-based testing with the Container environment for realistic consensus validation.

## Table of Contents

- [🎯 Overview](#-overview)
- [🏗️ Network and Node Management](#-network-and-node-management)
- [🌐 Network Simulation](#-network-simulation)
- [⏱️ Time Management](#-time-management)
- [🎲 Deterministic Testing](#-deterministic-testing)

## 🎯 Overview

The Container environment provides **realistic testing conditions** using actual Docker containers running consensus nodes. This environment is ideal for:

- **Integration Testing**: Validate real network communication and Docker deployment
- **Production Validation**: Test scenarios closer to production deployment

### Container Environment Components

The following diagram illustrates the Container environment's core architecture:

```mermaid
classDiagram
    class TestEnvironment {
        <<interface>>
    }
    class ContainerTestEnvironment {
    }
    class ContainerNetwork {
    }
    class ContainerNode {
    }
    class RegularTimeManager {
    }
    class ContainerTransactionGenerator {
    }
    TestEnvironment <|-- ContainerTestEnvironment
    ContainerTestEnvironment --* "1" ContainerNetwork
    ContainerNetwork --* "0..*" ContainerNode
    ContainerTestEnvironment --* "1" RegularTimeManager
    ContainerTestEnvironment --* "1" ContainerTransactionGenerator
```

The `ContainerTestEnvironment` is the main container that owns a single `ContainerNetwork`, `RegularTimeManager`, and `ContainerTransactionGenerator`. The `ContainerNetwork` can contain zero or more `ContainerNode` instances. `ContainerTestEnvironment` manages all the core components needed to run production-like tests with multiple nodes in a Docker container network.

## 🏗️ Network and Node Management

The following diagram shows the key interfaces and classes for managing networks and nodes in the Container environment:

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

    class ContainerNetwork {
        #timeManager(): TimeManager
        #transactionGenerator(): TransactionGenerator
        #createFreezeTransaction(Instant): byte[]
        +nodes(): List~Node~
        +addNodes(int): List~Node~
        +addInstrumentedNode(): InstrumentedNode
        ~destroy()
    }
    AbstractNetwork <|-- ContainerNetwork

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

    class ContainerNode {
        +killImmediately(): void
        +start(): void
        +withTimeout(Duration): AsyncNetworkActions
        +submitTransaction(byte[]): void
        +configuration(): NodeConfiguration
        +getConsensusResult(): SingleNodeConsensusResult
        +getLogResult(): SingleNodeLogResult
        +getPlatformStatusResult(): SingleNodePlatformStatusResult
        +getPcesResult(): SingleNodePcesResult
        ~destroy()
    }
    AbstractNode <|-- ContainerNode
    ContainerNetwork "1" --* "0..*" ContainerNode

    class AsyncNetworkActions {
        <<interface>>
        +start(): void*
        +freeze(): void*
        +shutdown(): void*
    }
```

The `Network` interface and its abstract implementation `AbstractNetwork` provide the foundation for managing collections of consensus nodes, with `ContainerNetwork` being a specific implementation that uses Testcontainers to run nodes in Docker containers. The `Node` interface and its `AbstractNode` base class represent individual consensus participants, with `ContainerNode` being the concrete implementation that integrates with the containerized network environment.

## Docker Integration

### Image Building

The framework automatically builds Docker images from the DockerApp implemented in the module `consensus-otter-docker-app`. The Dockerfile is generated based on the contents of the `apps` and `lib` directories in the module. The image is built using the Eclipse Temurin base image for Java 21.:

```dockerfile
FROM eclipse-temurin:21
RUN mkdir /opt/DockerApp && \
    mkdir /opt/DockerApp/apps && \
    mkdir /opt/DockerApp/lib
COPY apps/* /opt/DockerApp/apps/
COPY lib/* /opt/DockerApp/lib/
EXPOSE 8080
CMD ["java", "-jar", "/opt/DockerApp/apps/DockerApp.jar"]
```

### Container Startup Process

When a `ContainerNode` is created, it starts a Docker container running the `DockerApp`. The container is configured to expose port 8080 for gRPC communication. The `DockerApp` initializes a gRPC server and listens for incoming connections from the `ContainerNode`.

The following sequence diagram shows the container startup process:

```mermaid
sequenceDiagram
    participant Test as Test
    participant ContainerNetwork
    participant ContainerNode
    participant GenericContainer
    participant Container as Docker Container
    participant DockerMain
    participant DockerManager
    participant ConsensusNodeManager
    participant Platform

    Test->>ContainerNetwork: addNodes(4)

    loop For each node
        ContainerNetwork->>ContainerNode: new ContainerNode()
        activate ContainerNode
        ContainerNode->>GenericContainer: new GenericContainer()
        activate GenericContainer
        GenericContainer->>Container: Start container
        activate Container
        ContainerNode->>Container: start()
        Container->>DockerMain: java -jar DockerApp.jar
        activate DockerMain
        DockerMain->>DockerManager: Initialize gRPC server
        activate DockerManager
        ContainerNode->>DockerManager: Establish gRPC connection
    end

    Test->>ContainerNetwork: start()

    loop For each node
        ContainerNetwork->>ContainerNode: start()
        ContainerNode->>DockerManager: Send StartRequest
        DockerManager->>ConsensusNodeManager: new ConsensusNodeManager()
        activate ConsensusNodeManager
        ConsensusNodeManager->>Platform: Initialize Platform
        activate Platform
        Note over ContainerNode,Platform: 🔄 Ongoing consensus and event streaming
    end
```

When nodes are added to the `ContainerNetwork`, a `ContainerNode` is created for each. A `ContainerNode` creates a new Docker container using the `GenericContainer` class from Testcontainers. The container runs the `DockerApp`, which initializes a gRPC server for communication. The `ContainerNode` then establishes a connection to this server, allowing it to send requests and receive events.

This process ensures each container runs an independent consensus node with real network communication.

### gRPC Protocol

Container nodes use gRPC for control and monitoring.

```protobuf
// Service definition for controlling tests.
service TestControl {
  // RPC to start the platform and stream event messages.
  rpc Start(StartRequest) returns (stream EventMessage);

  // RPC used by the test harness to submit a transaction to the running
  // platform. Returns an TransactionRequestAnswer weather the platform accepted the transaction or not.
  rpc SubmitTransaction(TransactionRequest) returns (TransactionRequestAnswer);

  // RCP to signal a kill of the app
  rpc KillImmediately(KillImmediatelyRequest) returns (google.protobuf.Empty);
}

// Wrapper for different types of event messages.
message EventMessage {
  // Oneof field to represent different event types.
  oneof event {
    // Platform status change event.
    PlatformStatusChange platform_status_change = 1;
    // Log entry event.
    LogEntry log_entry = 2;
    // Consensus rounds event.
    ProtoConsensusRounds consensus_rounds = 3;
  }
}

// Request to start the remote platform.
message StartRequest {
  ...
}

// Wrapper for a transaction submission request.
message TransactionRequest {...}

// Response to a transaction submission request.
message TransactionRequestAnswer {...}

// Request to kill the application immediately.
message KillImmediatelyRequest {...}
```

### Event Streaming Flow

```mermaid
sequenceDiagram
    participant ContainerNode
    participant DockerManager
    participant ConsensusNodeManager
    participant Platform

    ContainerNode->>DockerManager: StartRequest via gRPC
    DockerManager->>ConsensusNodeManager: new ConsensusNodeManager()
    activate ConsensusNodeManager
    ConsensusNodeManager->>Platform: Initialize Platform
    activate Platform

    loop Event Streaming
        Platform->>ConsensusNodeManager: Event via OutputWire
        ConsensusNodeManager->>DockerManager: Notify event
        DockerManager->>ContainerNode: Stream EventMessage
    end
```

Once a connection between the `ContainerNode` and the `DockerManager` is established, it can receive events. The `Platform` notifies the `ConsensusNodeManager` of various events such as status changes, log entries, and consensus rounds. The `ConsensusNodeManager` then relays these events to the `DockerManager`, which streams them back to the `ContainerNode` as `EventMessage` objects.

### Debugging Container Tests

#### 1. Container Logs

Access container logs for debugging:

```bash
# Find container names
docker ps

# View logs from specific container
docker logs <container_name>

# Follow logs in real-time
docker logs -f <container_name>
```

#### 2. Network Inspection

```bash
# List Docker networks
docker network ls

# Inspect test network
docker network inspect <network_name>

# Check container connectivity
docker exec <container_name> ping node-2
```

#### 3. Resource Monitoring

```bash
# Monitor container resource usage
docker stats

# Inspect specific container
docker inspect <container_name>
```

## 🔗 Related Documentation

|          Topic          |                                   Link                                   |
|-------------------------|--------------------------------------------------------------------------|
| **Environment Details** | [Turtle](turtle-environment.md) \| [Container](container-environment.md) |
| **Test Development**    | [Writing Tests Guide](writing-tests.md)                                  |
| **API Reference**       | [Assertions API](assertions-api.md)                                      |
| **Configuration**       | [Configuration Guide](configuration.md)                                  |
