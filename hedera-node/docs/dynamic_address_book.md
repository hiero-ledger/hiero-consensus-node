## Background
This document describes how the dynamic address book feature is implemented in the system.
### Terminology

| Term | Definition |
| --- | --- |
| **Genesis** | The initial startup of the network when no prior state exists.  |
| **Node Store** | A persistent store containing the full configuration of all nodes in the network (e.g., account ID, keys, endpoints, certificates). |
| **Roster Store** | A gossip-specific store that contains only the fields needed for gossip and consensus. |
| **Candidate Roster**  | The candidate roster is the next roster that may be adopted at the time of the next network upgrade event. |
| **Dynamic Address Book** | The feature that enables nodes to be added, updated and removed dynamically via node transactions.|
| **NodeCreateTransaction** | A transaction that adds a node to the address book. |
| **NodeUpdateTransaction** | A transaction that modifies one or more node properties (e.g., rotating keys, updating service endpoints, changing gossip configuration). |
| **NodeDeleteTransaction** | A transaction that marks a node as deleted or removes it from the active node store. |
| **Genesis File** | A configuration file that defines the initial Node Store and Roster Store at genesis. |
---
### What is the Node Store?

The **Node Store** is the persistent data store for all node-level configuration required for the network. Changes made to all node properties are written to consensus node state immediately after a given node transaction is successful. All node properties are adopted by the network immediately except for changes to the gossip endpoints and gossip CA certificate. 
Please see roster store below. Detailed descriptions can be found [here](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hapi/hedera-protobuf-java-api/src/main/proto/services/state/addressbook/node.proto).

**Each node record includes:**

| Field |Description|
| --- | --- |
| `account_id` | Unique identifier for the node (e.g., `0.0.x`). |
| `admin_key` |An administrative key controlled by the node operator. |
| `description` | Optional text describing the node. |
| `service_endpoints` | A list of service endpoints for client calls |
| `gossip_endpoints` | A list of service endpoints for gossip. |
| `gossip_ca_certificate` | A certificate used to sign gossip events. |
| `grpc_certificate_hash` |  The hash of the node gRPC certificate. |
| `grpc_proxy_endpoint` |  A web proxy for gRPC from non-gRPC clients. |
| `decline_reward` | Flag indicating whether the node opts out of node rewards. |

---

### What is the Roster Store?

The **Roster Store** represents the subset of node data used by the hashgraph consensus algorithm. Changes made to the roster store are stored in state immediately but do not get applied or used by the network until the next network upgrade as of consensus node release 0.68.
Detailed descriptions can be found [here](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hapi/hedera-protobuf-java-api/src/main/proto/services/state/roster/roster.proto#L34).

**It contains:**

| Field | Description |
| --- | --- |
| `gossip_endpoints` | A list of service endpoints for gossip. |
| `gossip_ca_certificate` | A certificate used to sign gossip events. |
| `nodeId` | The unique identifier of the consensus node. |
| `weight` | The consensus weight applied to the node. |

### What is the Candidate Roster?

The **Candidate Roster** represents the roster that will be applied at the next upgrade to replace the current roster.

**It contains:**

| Field | Description |
| --- | --- |
| `gossip_endpoints` | A list of the new service endpoints for gossip. |
| `gossip_ca_certificate` | The new certificate used to sign gossip events. |
| `nodeId` | The unique identifier of the consensus node. |
| `weight` | The new consensus weight applied to the node. |

---

## Deploy a Hiero Network from Genesis

### 1. Pre-Genesis State

Before **genesis** (i.e., before the network starts for the first time):

- Both the **Node Store** and **Roster Store** are empty or `null`.

---

### 2. Genesis Initialization

At **network genesis**,when the network first starts up, the system initializes both node and roster stores by reading from a predefined configuration file (ex: genesis-network.json) found in `data/config`. 

**The initialization process:**

- Loads each node’s properties from `data/config/genesis-network.json` into the **Node Store** and **Roster Store**

**Example configuration file: genesis-network.json (base64 encoded)**

```json
{
  "nodeMetadata": [
    {
      "node": {
        "accountId": {
          "accountNum": "3"
        },
        "adminKey": {
          "ed25519": "1uewl5ybyTYuWew23yDKgzAmcgSfj3BG/wvMCNlYHg0="
        },
        "description": "node 0",
        "gossipCaCertificate": "MIIDyTCCAjGgAwIBAgIIY2692yMBcSwwDQYJKoZIhvcNAQEMBQAwEjEQMA4GA1UEAxMHcy1ub2RlMTAgFw0yNDAyMDgwMzAyNTVaGA8yMTI0MDE...",
        "gossipEndpoint": [
          {
            "ipAddressV4": "wKgBCg==",
            "port": 50111
          },
          {
            "ipAddressV4": "LMk3EQ==",
            "port": 50111
          }
        ],
        "grpcCertificateHash": "hqwSJJ9e1Z9sIsVGl40IQTAGhW1nLjmAfB1prtltt9WFM/NXWOCJc9PRSO1Pi757...",
        "grpcProxyEndpoint": {
          "domainName": "grpc-proxy.test.com",
          "port": 443
        },
        "nodeId": "0",
        "serviceEndpoint": [
          {
            "ipAddressV4": "I+3ItA==",
            "port": 50211
          },
          {
            "ipAddressV4": "rBD+Aw==",
            "port": 50212
          }
        ],
        "weight": "10"
      },
      "rosterEntry": {
        "gossipCaCertificate": "MIIDyTCCAjGgAwIBAgIIY2692yMBcSwwDQ...",
        "gossipEndpoint": [
          {
            "ipAddressV4": "wKgBCg==",
            "port": 50111
          },
          {
            "ipAddressV4": "LMk3EQ==",
            "port": 50111
          }
        ],
        "nodeId": "0",
        "weight": "10"
      }
    }
  ]
}
```

---

### 3. Post-Genesis Updates

Once the network is operational, node details can change through NodeCreateTransaction, NodeUpdateTransaction, and NodeDeleteTransaction.

**What happens when updates occur?**

- The **Node Store** updates in-place for all changed fields (`service_endpoints`, `admin_key`, etc.).
- The **Roster Store** updates only if gossip-related fields are modified.
    - Contains current roster and candidate roster
- The **Candidate Roster** - a new roster that may be adopted at the next network upgrade.
    - Updated every single day when weights are changed
    - Updated when node store is updated
    - The old roster is replaced with the candidate roster at the time of a network upgrade

**When is network restart required?**
A network restart is required to adopt the new candidate roster. The current roster is being used for consensus until this new candidate roster replaces the current roster.


---

### 4. Data Flow Summary

| Stage | Node Store | Roster Store | Source |
| --- | --- | --- | --- |
| **Before Genesis** | Empty / `null` | Empty / `null` | — |
| **At Genesis** | Populated from genesis file | Populated from genesis file | `data/config/genesis-network.json` |
| **During Operation** | Updated dynamically via node transactions | Updated only for gossip changes | Node transactions |
| **Restart Required** | No | Yes | — |

---

## Example: Node and Roster Store Data Flow

This example illustrates how data in the **Node Store** and **Roster Store** changes through the network lifecycle.

---

### 1. Before Genesis

At this stage, the network has not yet started.

Both the **Node Store** and **Roster Store** are empty (`null`). The node configuration file contains the node data to be used at startup.

**Node Store**

```yaml
account_id: null
admin_key: null
description: null
service_endpoints: null
gossip_endpoints: null
gossip_ca_certificate: null
grpc_certificate: null
grpc_proxy_endpoint: null
decline_reward: null
```

**Roster Store**

```yaml
gossip_endpoints: null
gossip_ca_certificate: null
weight: null
nodeId: null 
```

---

### 2. After Genesis Initialization

At network startup, the system reads from the genesis configuration file (e.g.`data/config/genesis-network.json` and populates both roster and node store).

**Genesis File**

```json
{
  "nodeMetadata": [
    {
      "node": {
        "accountId": {
          "accountNum": "3"
        },
        "adminKey": {
          "ed25519": "1uewl5ybyTYuWew23yDKgzAmcgSfj3BG/wvMCNlYHg0="
"
        },
        "description": "node 0",
        "gossipCaCertificate": "MIIDyTCCAjGgAwIBAgIIY2692yMBcSwwDQYJKoZIhvcNAQEMBQAwEjEQMA4GA1UEAxMHcy1ub2RlMTAgFw0yNDAyMDgwMzAyNTVaGA8yMTI0MDE...",
        "gossipEndpoint": [
          {
            "ipAddressV4": "wKgBCg==",
            "port": 50111
          }
        ],
        "grpcCertificateHash": "hqwSJJ9e1Z9sIsVGl40IQTAGhW1nLjmAfB1prtltt9WFM/NXWOCJc9PRSO1Pi757...",
        "grpcProxyEndpoint": {
          "domainName": "grpc-proxy.test.com",
          "port": 443
        },
        "nodeId": "0",
        "serviceEndpoint": [
          {
            "ipAddressV4": "I+3ItA==",
            "port": 50211
          }
        ],
        "weight": "10"
      },
      "rosterEntry": {
        "gossipCaCertificate": "MIIDyTCCAjGgAwIBAgIIY2692yMBcSwwDQ...",
        "gossipEndpoint": [
          {
            "ipAddressV4": "wKgBCg==",
            "port": 50111
          }
        ],
        "nodeId": "0",
        "weight": "10"
      }
    }
  ]
}
```

**Node Store**
Node store is updated with the data from the genesis file.

```yaml
account_id: "0.0.3"
admin_key: "ed25519:1uewl5ybyTYuWew23yDKgzAmcgSfj3BG/wvMCNlYHg0="
description: "node 0"
service_endpoints: "35.237.200.180:50211"   # from I+3ItA==
gossip_endpoints: "192.168.1.10:50111"     # from wKgBCg==
gossip_ca_certificate: "-----BEGIN CERTIFICATE-----MIIDyTCCAjGgAwIBAgIIY2692yMBcSwwDQYJKoZIhvcNAQEMBQAwEjEQMA4GA1UEAxMHcy1ub2RlMTAgFw0yNDAyMDgwMzAyNTVaGA8yMTI0MDE...-----END CERTIFICATE-----"
grpc_certificate: "MIIDyTCCAjGgAwIBAgIIY2692yMBcSwwDQ...
grpc_proxy_endpoint: "grpc-proxy.test.com:443"
decline_reward: false
```

**Roster Store**
Roster store is updated with the data from the genesis file.

```yaml
gossip_endpoints: "35.237.200.180:50211"
gossip_ca_certificate: "-----BEGIN CERTIFICATE-----MIIDyTCCAjGgAwIBAgIIY2692yMBcSwwDQYJKoZIhvcNAQEMBQAwEjEQMA4GA1UEAxMHcy1ub2RlMTAgFw0yNDAyMDgwMzAyNTVaGA8yMTI0MDE...-----END CERTIFICATE-----"
weight:100
nodeId:0
```

---

## 3.Submit a NodeUpdateTransaction

A `NodeUpdateTransaction` is submitted that updates then node account ID for node 0 from `0.0.3` to `0.0.11` and the gossip endpoint to 35.146.200.180:50211,

---

### 4. Updated State in Node Store

After the transaction is applied, the Node Store reflects the new updated values for node’s account ID and gossip endpoints.

```yaml
account_id: "0.0.11"
admin_key: "ed25519:1uewl5ybyTYuWew23yDKgzAmcgSfj3BG/wvMCNlYHg0="
description: "node 0"
service_endpoints: "35.237.200.180:50211"  
gossip_endpoints: "35.146.200.180:50211"    
gossip_ca_certificate: "-----BEGIN CERTIFICATE-----MIIDyTCCAjGgAwIBAgIIY2692yMBcSwwDQYJKoZIhvcNAQEMBQAwEjEQMA4GA1UEAxMHcy1ub2RlMTAgFw0yNDAyMDgwMzAyNTVaGA8yMTI0MDE...-----END CERTIFICATE-----"
grpc_certificate: "MIIDyTCCAjGgAwIBAgIIY2692yMBcSwwDQ...
grpc_proxy_endpoint: "grpc-proxy.test.com:443"
decline_reward: false
```

---

### 4. Updated State in Roster Store

**Candidate Roster**

The candidate roster is updated with the new values and will replace the current roster at the next upgrade window. Until then, the network does not use the new gossip values.

```yaml
gossip_endpoints:"35.237.200.180:50211"
gossip_ca_certificate: "-----BEGIN CERTIFICATE-----MIIB...END-----"
weight: 100
nodeId: 0
```

**Current Roster**

The current roster used by the network remains unchanged.

```yaml
gossip_endpoints:
  - "35.237.200.180:50211"
gossip_ca_certificate: "-----BEGIN CERTIFICATE-----MIIB...END-----"
weight: 100
nodeId: 0
```

### 5. Restart the network to adopt the next candidate roster and use new gossip values
A network restart is required to adopt the next candidate roster gossip values.


---

# Resources
- https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hapi/hedera-protobuf-java-api/src/main/proto/services/state/addressbook/node.proto#L23
- https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hapi/hedera-protobuf-java-api/src/main/proto/services/state/roster/roster.proto#L34

