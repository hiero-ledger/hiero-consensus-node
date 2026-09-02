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
- `servicePort` (integer, optional): TCP port for the Block Node to access service-related APIs such as server status. (Note: this is defaulted to the streaming port)
- `priority` (integer, required): Lower numbers are higher priority. Nodes with smaller priority values are preferred for selection. Among nodes with the same priority, selection is randomized.
- `messageSizeSoftLimitBytes` (integer, optional): Desired maximum per-request payload size in bytes for this node. If omitted, the default is 2,097,152 bytes (2 MB).
- `messageSizeHardLimitBytes` (integer, optional): Absolute maximum per-request payload size in bytes, which a request may "burst" up to when a single block item exceeds the soft limit. Must be greater than or equal to the soft limit. If omitted, the default comes from `blockNode.defaultMessageHardLimitBytes`.
- `clientHttpConfig` (object, optional): Overrides for the Helidon HTTP/2 client used for this node. See `HelidonHttpConfig` in the proto.
- `clientGrpcConfig` (object, optional): Overrides for the Helidon gRPC client used for this node. See `HelidonGrpcConfig` in the proto.
- `streamingTls` (object, optional): TLS settings for the streaming (publish) API on `streamingPort`. See [TLS](#tls) below.
- `serviceTls` (object, optional): TLS settings for the service API on `servicePort`. See [TLS](#tls) below.

### Example

```json
{
  "nodes": [
    {
      "address": "localhost",
      "streamingPort": 50051,
      "servicePort": 50052,
      "priority": 0,
      "messageSizeSoftLimitBytes": 1500000
    },
    { "address": "pbj-unit-test-host", "streamingPort": 8081, "priority": 1 }
  ]
}
```

### TLS

Each Block Node API is secured independently, because a Block Node may require TLS on some APIs and not
others. `streamingTls` applies to the publish API on `streamingPort`; `serviceTls` applies to the service
API (server status) on `servicePort`. Both are optional, and an absent block means the Consensus Node
connects to that endpoint using plaintext.

A TLS block has two fields:

- `enabled` (boolean): whether the Consensus Node uses TLS for this endpoint.
- `certificateSha384` (string, optional): hex-encoded SHA-384 fingerprint of the certificate the endpoint
  presents. 96 hexadecimal characters, case-insensitive, optionally colon-separated (the form emitted by
  `openssl x509 -noout -fingerprint -sha384`). Must not be set unless `enabled` is `true`.
  SHA-384 is the same digest the network uses for `grpc_certificate_hash` on a node, so one algorithm
  describes every certificate in the network. The hash is taken over the certificate's DER encoding.

|           `streamingTls` / `serviceTls`            |                                                                                                                 Behavior                                                                                                                 |
|----------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| absent                                             | Plaintext.                                                                                                                                                                                                                               |
| `{ "enabled": false }`                             | Plaintext.                                                                                                                                                                                                                               |
| `{ "enabled": true }`                              | TLS. The certificate is verified against the platform default trust store, with hostname verification. Use this for certificates signed by a public or system-trusted CA.                                                                |
| `{ "enabled": true, "certificateSha384": "..." }`  | TLS. The certificate is accepted if and only if its SHA-384 hash matches. Neither the trust store nor hostname verification is consulted, which is what allows a self-signed certificate to be used without distributing trust material. |
| `{ "enabled": false, "certificateSha384": "..." }` | Rejected as contradictory; the node is skipped with a warning.                                                                                                                                                                           |

#### When both APIs share a port

`servicePort` defaults to `streamingPort`, in which case a single listener serves both APIs and cannot be both
TLS and plaintext. So when the two ports are the same:

- an omitted `serviceTls` **inherits** `streamingTls`, the same way an omitted `servicePort` inherits
  `streamingPort`. Declaring only `streamingTls` therefore secures both APIs rather than leaving the service
  API dialling a TLS listener in plaintext.
- a `serviceTls` that is present and differs from `streamingTls` is rejected as contradictory, and the node is
  skipped with a warning.

Securing only the publish API (`streamingTls` on, `serviceTls` off) therefore requires giving the service API
its own `servicePort`.

The Consensus Node only verifies the Block Node's identity; it does not present a client certificate, so
mutual TLS is not supported. TLS on the Block Node side is expected to be terminated in front of the Block
Node itself.

Note that TLS is configured per Block Node, so it can be enabled for one Block Node while others in the same
file stay plaintext.

The fingerprint of a certificate can be obtained with:

```bash
openssl x509 -in blocknode.crt -noout -fingerprint -sha384
```

#### Example: TLS on the publish API only, with a self-signed certificate

```json
{
  "nodes": [
    {
      "address": "blocknode.example.com",
      "streamingPort": 8443,
      "servicePort": 8080,
      "priority": 0,
      "streamingTls": {
        "enabled": true,
        "certificateSha384": "3A:1F:...:9C"
      }
    }
  ]
}
```

#### Example: TLS on all APIs, with a CA-signed certificate

```json
{
  "nodes": [
    {
      "address": "blocknode.example.com",
      "streamingPort": 8443,
      "servicePort": 8444,
      "priority": 0,
      "streamingTls": { "enabled": true },
      "serviceTls": { "enabled": true }
    }
  ]
}
```

### Selection behavior

- Nodes are grouped by `priority` and considered from lowest value to highest.
- Within a priority group, selection is randomized among nodes that are not already connected.
- If multiple nodes are configured, the manager can switch to the next available node when latency limits or other criteria indicate it should.

### Defaults and missing values

- If `messageSizeSoftLimitBytes` is omitted, the effective per-request limit defaults to 2,097,152 bytes (2 MB).
- If `servicePort` is omitted, it defaults to `streamingPort`.
- If `streamingTls` or `serviceTls` is omitted, that endpoint is contacted using plaintext.

### Live reload behavior

- The `block-nodes.json` file is watched for create/modify/delete events.
- On change, the manager reloads the file, shuts down any existing connections, and restarts with the new nodes.
- If the contents are unchanged, no restart is performed.
- If the file is missing or the contents fail to parse, the manager logs the issue and will not establish block node connections until a valid file is present again.

### Validation notes

- `priority` should be a non-negative integer. Use `0` for the highest priority.
- A node whose TLS settings are not internally consistent (for example a `certificateSha384` on an endpoint
  that is not using TLS) is skipped with a warning; the other nodes in the file are still loaded.
- `address` must be resolvable by the OS DNS stack or be a valid IP address. If resolution fails, the active-connection-IP metric will report `-1` for that node.

### Related configuration (outside this file)

While the JSON file declares the set of nodes (and optional per-node message size), general streaming behavior is configured via the `blockNode` section in the application configuration (e.g. `blockNode.blockNodeConnectionFileDir`, backoff limits, latency thresholds, etc.).
