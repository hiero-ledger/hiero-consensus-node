## Block Nodes JSON configuration

This document describes the `block-nodes.json` file used to configure which Block Nodes a Consensus Node can connect to, along with an optional per-node message size limit.

Note: The canonical definition of this structure is the HAPI proto located at `hapi/hapi/src/main/proto/network/block_node_connections.proto`. The `block-nodes.json` file uses the PBJ JSON encoding of that schema.

The file is read from the directory configured by `blockNode.blockNodeConnectionFileDir` and must be named `block-nodes.json`.

### Top-level structure

The file is a JSON object with a single field:

- `nodes`: array of Block Node entries

### Node entry schema

Each element of `nodes` has the following fields:

- `address` (string, required): Hostname or IPv4/IPv6 address of the Block Node (e.g. "localhost", "10.0.0.5").
- `streamingPort` (integer, required): TCP port for the Block Node to receive blocks from the Consensus Node.
- `servicePort` (integer, required): TCP port for the Block Node to access service-related APIs such as server status. (Note: this is defaulted to the streaming port)
- `priority` (integer, required): Lower numbers are higher priority. Nodes with smaller priority values are preferred for selection. Among nodes with the same priority, selection is randomized.
- `maxMessageSizeBytes` (integer, optional): Maximum per-request payload size in bytes for this node. If omitted, the default is 2,097,152 bytes (2 MB).

### Example

```json
{
  "nodes": [
    {
      "address": "localhost",
      "streamingPort": 50051,
      "servicePort": 50052,
      "priority": 0,
      "maxMessageSizeBytes": 1500000
    },
    { "address": "pbj-unit-test-host", "streamingPort": 8081, "priority": 1 }
  ]
}
```

### Selection behavior

- Nodes are grouped by `priority` and considered from lowest value to highest.
- Within a priority group, selection is randomized among nodes that are not already connected.
- If multiple nodes are configured, the manager can switch to the next available node when latency limits or other criteria indicate it should.

### Defaults and missing values

- If `maxMessageSizeBytes` is omitted, the effective per-request limit defaults to 2,097,152 bytes (2 MB).

### Live reload behavior

- The `block-nodes.json` file is watched for create/modify/delete events.
- On change, the manager reloads the file, shuts down any existing connections, and restarts with the new nodes.
- If the contents are unchanged, no restart is performed.
- If the file is missing or the contents fail to parse, the manager logs the issue and will not establish block node connections until a valid file is present again.

### Validation notes

- `priority` should be a non-negative integer. Use `0` for the highest priority.
- `address` must be resolvable by the OS DNS stack or be a valid IP address. If resolution fails, the active-connection-IP metric will report `-1` for that node.

### Related configuration (outside this file)

While the JSON file declares the set of nodes (and optional per-node message size), general streaming behavior is configured via the `blockNode` section in the application configuration (e.g. `blockNode.blockNodeConnectionFileDir`, backoff limits, latency thresholds, etc.).
