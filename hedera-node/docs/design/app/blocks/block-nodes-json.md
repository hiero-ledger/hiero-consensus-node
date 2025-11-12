## Block Nodes JSON configuration

This document describes the `block-nodes.json` file used to configure which Block Nodes a Consensus Node can connect to, along with optional client protocol settings and message size limit per node.

Note: The canonical definition of this structure is the HAPI proto located at `hapi/hapi/src/main/proto/network/block_node_connections.proto`. The `block-nodes.json` file uses the PBJ JSON encoding of that schema.

The file is read from the directory configured by `blockNode.blockNodeConnectionFileDir` and must be named `block-nodes.json`.

### Top-level structure

The file is a JSON object with a single field:

- `nodes`: array of Block Node entries

### Node entry schema

Each element of `nodes` has the following fields:

- `address` (string, required): Hostname or IPv4/IPv6 address of the Block Node (e.g. `"localhost"`, `"10.0.0.5"`).
- `port` (integer, required): TCP port for the node’s gRPC endpoint.
- `priority` (integer, required): Lower numbers are higher priority. Nodes with smaller priority values are preferred for selection. Among nodes with the same priority, selection is randomized.
- `http2ClientProtocolConfig` (object, optional): Overrides for the HTTP/2 client.
  - `name` (string, optional)
  - `ping` (boolean, optional)
  - `pingTimeout` (string, optional): ISO-8601 duration, e.g. `"PT0.5S"` for 500ms.
  - `flowControlBlockTimeout` (string, optional): ISO-8601 duration.
  - `initialWindowSize` (integer, optional)
  - `maxFrameSize` (integer, optional)
  - `maxHeaderListSize` (string, optional): may be `"-1"` to indicate unlimited (as supported by Helidon config).
  - `priorKnowledge` (boolean, optional)
- `grpcClientProtocolConfig` (object, optional): Overrides for the gRPC protocol.
  - `name` (string, optional)
  - `abortPollTimeExpired` (boolean, optional)
  - `heartbeatPeriod` (string, optional): ISO-8601 duration, e.g. `"PT10S"`.
  - `initBufferSize` (integer, optional)
  - `pollWaitTime` (string, optional): ISO-8601 duration.
- `maxMessageSizeBytes` (integer, optional): Maximum per-request payload size in bytes for this node. The system enforces an upper cap of 2,097,152 bytes (2 MB) due to PBJ limits; if configured above this, the effective limit will be 2 MB.
- `maxMessageSizeBytes` (integer, optional): Maximum per-request payload size in bytes for this node. If omitted, the default is 2,097,152 bytes (2 MB).

### Example

```json
{
  "nodes": [
    {
      "address": "localhost",
      "port": 50051,
      "priority": 0,
      "http2ClientProtocolConfig": {
        "name": "h2",
        "ping": true,
        "pingTimeout": "PT0.5S",
        "flowControlBlockTimeout": "PT1S",
        "initialWindowSize": 12345,
        "maxFrameSize": 16384,
        "maxHeaderListSize": "-1",
        "priorKnowledge": false
      },
      "grpcClientProtocolConfig": {
        "name": "grpc",
        "abortPollTimeExpired": false,
        "heartbeatPeriod": "PT0S",
        "initBufferSize": 1024,
        "pollWaitTime": "PT10S"
      },
      "maxMessageSizeBytes": 1500000
    },
    {
      "address": "pbj-unit-test-host",
      "port": 8081,
      "priority": 1
    }
  ]
}
```

### Selection behavior

- Nodes are grouped by `priority` and considered from lowest value to highest.
- Within a priority group, selection is randomized among nodes that are not already connected.
- If multiple nodes are configured, the manager can switch to the next available node when latency limits or other criteria indicate it should.

### Defaults and missing values

- If `http2ClientProtocolConfig` or `grpcClientProtocolConfig` are omitted, sensible defaults are used by the client.
- If `maxMessageSizeBytes` is omitted, the effective per-request limit defaults to 2,097,152 bytes (2 MB).

### Live reload behavior

- The `block-nodes.json` file is watched for create/modify/delete events.
- On change, the manager reloads the file, shuts down any existing connections, and restarts with the new nodes.
- If the contents are unchanged, no restart is performed.
- If the file is missing or the contents fail to parse, the manager logs the issue and will not establish block node connections until a valid file is present again.

### Validation notes

- Durations must be valid ISO-8601 strings (e.g. `"PT30S"`, `"PT1M"`). Invalid duration strings are ignored with a warning, and defaults apply for those fields.
- `priority` should be a non-negative integer. Use `0` for the highest priority.
- `address` must be resolvable by the OS DNS stack or be a valid IP address. If resolution fails, the active-connection-IP metric will report `-1` for that node.

### Related configuration (outside this file)

While the JSON file declares the set of nodes (and optional per-node message size and protocol overrides), general streaming behavior is configured via the `blockNode` section in the application configuration (e.g. `blockNode.blockNodeConnectionFileDir`, backoff limits, latency thresholds, etc.).
