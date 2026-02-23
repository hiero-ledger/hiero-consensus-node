# Generating Saved States for Otter Tests

## Overview

Saved states allow tests to bootstrap from a previously generated network state rather than starting from genesis. This is useful for migration testing and scenarios where you want to start from a specific network configuration.

## Generating a State

Use the `GenerateStateTool` command-line tool to create a new saved state:

```bash
./gradlew :consensus-otter-tests:run --args="org.hiero.otter.fixtures.tools.GenerateStateTool"
```

This tool:
1. Creates a 4-node network using a deterministic seed
2. Runs the network for 10 seconds
3. Freezes the network to create a consistent snapshot
4. Cleans up unnecessary files (keeping only the latest round and PCES directory)
5. Moves the state to `platform-sdk/consensus-otter-tests/saved-states/previous-version-state`

The generated state is a **freeze state**, making it safe for:
- Migration tests that load a state from a previous software version
- Tests that make roster changes (node additions/removals)
- Any test requiring a known starting state with existing rounds

## Using a Saved State in Tests

Reference the saved state directory in your test:

```java
@OtterTest
@OtterSpecs(randomNodeIds = false)
void myTest(@NonNull final TestEnvironment env) {
    final Network network = env.network();

    network.addNodes(numberOfNodes);
    network.savedStateDirectory(Path.of("previous-version-state"));
    network.version(targetVersion);
    network.start();

    // ... test assertions
}
```

The network automatically handles:
- Loading the saved state from `saved-states/previous-version-state`
- Synchronizing FakeTime to the state's WALL_CLOCK_TIME + 1 hour if running in the Turtle environment to ensure time does not go backwards
- Managing roster changes if the test uses different node counts

## Git Management

The `.gitignore` at the repository root ignores all `.swh` files:

```
*.swh
```

After generating a new state, **manually `git add` the `.swh` file** to include it in version control:

```bash
cd platform-sdk/consensus-otter-tests/saved-states/previous-version-state
git add -f OtterApp/0/hiero/*/SignedState.swh
```

The `-f` flag is required because the file matches the gitignore pattern.

## Migration Testing

> TODO: Document migration testing workflow when we have tests that load states generated from previous software versions. Include version handling and any specific considerations for testing state format compatibility.
