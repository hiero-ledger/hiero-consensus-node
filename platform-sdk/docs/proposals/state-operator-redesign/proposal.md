# State Operator: redesign the validator for validate billion-entry states

---

## Summary

This proposal outlines a significant update to the State Operator's validation functionality that optimizes execution time by traversing state data only once for a category of suitable validators.

|      Metadata      |                 Entities                 |
|--------------------|------------------------------------------|
| Designers          | [@thenswan](https://github.com/thenswan) |
| Functional Impacts | State Operator                           |

---

## Purpose and Context

The current validator implementation has a performance issue. Most of the validators perform their own complete traversal of the state data. With states containing billions of key-value pairs and an increasing number of validators, this approach becomes prohibitively slow and resource-intensive. Multiple validators that could conceptually share data access instead perform redundant, expensive operations.

This proposal addresses it by introducing a validation engine that eliminates the JUnit dependency and optimizes state traversal through validator grouping.

### Requirements

The new design must satisfy the following requirements:

1. **Remove JUnit Dependency**: Eliminate all JUnit framework dependencies from the production validation tool.
2. **Optimize State Traversal**: Traverse state data once for all compatible validators instead of performing redundant full traversals.
3. **Support Diverse Validators**: The design must accommodate different types of validators, distinguishing between those that can participate in a shared traversal and those that have unique data processing logic.

### Design Decisions

1. **Dedicated Validation Orchestrator**: A new `ValidationEngine` class is introduced to be the central orchestrator. It is responsible for loading the state, identifying which validators to run, managing their lifecycle, and executing them in the most optimal way.

2. **Specialized Validator Interfaces**: To enable optimization, a set of clear, role-based interfaces for validators is introduced:

   * `Validator`: The base interface defining a common lifecycle (`initialize`, `validate`).
   * `PathValidator`: For validators that operate on each individual path.
   * `KeyValueValidator`: For validators that operate on the key and value of each leaf.
   * `StateValidator`: For "independent" validators that have their own unique traversal logic.
3. **Single-Pass Data Access**: The engine performs one traversal over state data, dispatching each element to all compatible validators, eliminating redundant data access.
4. **Custom Assertions and Exceptions**: A `ValidationAssertions` utility class and a `ValidationException` class are introduced. This removes the dependency on JUnit's `Assertions`.
5. **Listener-Based Reporting**: Decouple reporting from test framework listeners with custom `ValidationListener` interface.

---

## Changes

### Architecture and/or Components

The architecture is refactored from a test-runner model to a dedicated engine model.

**Old Architecture:**
`JUnit Platform Launcher -> (discovers and runs) -> @Test methods in Validator Classes (each traverses state)`

**New Architecture:**
`ValidateCommand -> ValidationEngine -> (separate execution) -> [StateValidators] -> (single traversal) -> dispatches to [Index/KV Validators]`

**New Components:**
- **ValidationEngine**: Central orchestrator managing validator execution and optimization
- **Validator Interface Hierarchy**:
- `Validator`: Base interface defining common lifecycle patterns (`initialize`, `validate`)
- `PathValidator`: Specialized interface for path-based validation operations
- `KeyValueValidator`: Specialized interface for data content validation operations
- `StateValidator`: Interface for validators with unique traversal requirements
- `ValidationListener`: Event-driven reporting interface replacing testing framework listeners
- `ValidationAssertions`, `ValidationException`: A custom assertion and exception framework.

**Modified Components:**
- **ValidateCommand**: Is now a simple client that instantiates and invokes the `ValidationEngine`.
- **Individual Validators**: Refactored from test classes to implement appropriate validator interfaces, with all testing framework dependencies removed
- **Error Handling**: Replaced JUnit assertions with custom validation assertions

### Core Behaviors

The fundamental change in behavior is the execution model. Instead of multiple, heavyweight state traversals, the new model performs one shared traversal for all compatible validators. The `ValidationEngine` inspects the type of each selected validator and groups them into "traversal-based" or "independent" validators, executing each group in the most efficient manner.

**Execution Flow Changes:**
1. **Validator Discovery and Classification**: The engine categorizes validators by their data access patterns rather than relying on test framework annotations
2. **Optimized Data Access**: Single traversal with intelligent dispatching replaces multiple independent traversals
3. **Coordinated Lifecycle Management**: Unified `initialize` → `process` → `validate` lifecycle across all validators
4. **Structured Error Propagation**: Error handling with continued execution capability

### Performance

**This is the primary motivation for the proposal.** The performance improvement is substantial.

**Mathematical Model:**
* **Before:** The execution time was **`T × N`**, where `T` is the time for one full state traversal and `N` is the number of validators being run.
* **After:** For the group of validators, which can share state traversal, the execution time is **`T`**. The execution time for independent validators remains **`T × M`**, where `M` is the number of independent validators.

**Concrete Example:**
Running 10 validators, 8 of which can share state traversal and 2 of which must traverse state independently:
- **Current approach**: 10 × 5 minutes = 50 minutes total
- **New approach**: 5 minutes + (2 × 5 minutes) = 15 minutes total (70% time reduction)
