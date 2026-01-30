---

hip: xxxx # Assigned by HIP editor.
title: Simple event broadcast # Keep concise and descriptive.
author: Artur Biesiadowski (@abies)
working-group: <optional list of key stakeholders\' real names and GitHub handles who are actively involved in shaping the HIP>
discussions-to: <URL of the GitHub Pull Request for this HIP> # This will be filled by the HIP editor upon PR creation.
type: Standards Track
category: Core
needs-hiero-approval: Yes
needs-hedera-review: Yes
status: Draft
created: <yyyy-mm-dd> # Date of first submission as a Draft.
updated: <yyyy-mm-dd> # Date of last modification.
release:
--------

## Abstract

The broadcast mechanism operates by having nodes send their self-events directly to all their connected peer nodes (neighbours). This direct broadcast approach helps ensure rapid event propagation across the network.

When a node creates a new event, instead of waiting for the standard gossip sync cycle, it immediately broadcasts that event to all its connected peers. This proactive distribution helps minimize latency in event propagation and reduce average duplicate ratio of received events.

As a reliability measure, the standard gossip synchronization protocol continues to run periodically in the background. This acts as a fallback mechanism to handle cases where events may not have reached certain nodes due to various network conditions, such as:

- Temporary node disconnections
- Network latency or congestion
- Nodes failing to broadcast events

This dual approach - combining immediate broadcast with periodic gossip sync - helps ensure both speed and reliability in the network's event distribution system.

## Motivation

Currently, event exchange mechanism consists of full reconnect (heavy synchronization of entire state, which removes the node from network for practical purposes for few minutes) and gossip sync.

The sync process requires multiple roundtrips to exchange events (sending of tipsets and event window, confirmation of tipset visibility and only then actual event exchange). When the process is running, the set of events to send is fixed, so no new events will be propagated until next sync cycle.

With broadcast, most events (barring network disruption) will be sent immediately, without even confirmation, so in around half ping latency.

## Rationale

Broadcast is implemented in the least disruptive way and as completely optional mechanism. Events sent by broadcast are interleaved with communication of normal sync on the same channel, so no extra connection is required between the nodes.

There is no confirmation or trying to figure out if the remote node might already have the event - every self event (event created on given node) is sent to all neighbours as soon as possible (except if peer is in trouble, like behind severely behind event process and in the middle of disconnect, then they are not sent to avoid spamming already overloaded node).

Following other solutions were considered and rejected for time being:
- creating separate channel just for broadcast purposes on the same port; rejected due to strong logic of allowing only one connection between given peers, disconnecting previous one immediately, which helps with stability, here would require special handling for each stream separately
- creating separate channel for broadcast on different port; would require opening firewall between every pair of nodes, which could be next to impossible to manage in high security environment
- reusing gRPC port; mixing user-facing endpoints and internal gossip methods is quite risky, as very different authentication mechanism are used; additionally, gRPC layer is very far from gossip module and broadcast events have to interoperate with gossip internals closely

Longer term, the idea of switching entire gossip to use gRPC can be considered, on the port which is currently used for gossip, but this required changing reconnect logic, which is too big to be a part of this change. After reconnect is changed to use block node, an official HIP for a gossip gRPC endpoint can be opened.

## Specification

RPC sync protocol will be extended with the message type `BROADCAST_EVENT`, with message id 7. The `GossipMessage` serialized PBJ follows immediately afterwards (reused from current spec, but provided below for completeness). It has a very similar structure to `EVENT` message, but by having a separate message id, we can:

- send it regardless of the current sync stage
- ignore blocking intake counter (currently sync will not start again until last even of previous sync is fully processed to avoid duplications, we don't want broadcast events to block sync attempts)
- allow further extensions for sync event stream (for example batching messages with compression, while broadcast is inherently single-event)

```protobuf

/**
 * An event that is sent and received via gossip
 */
message GossipEvent {

  /**
   * The core event data
   */
  EventCore event_core = 1;

  /**
   * A node signature on the event hash.<br/>
   * The signature SHALL be created with the SHA384withRSA algorithm.<br/>
   * The signature MUST verify using the public key belonging to the `event_creator`.<br/>
   * The `event_creator` public key SHALL be read from the address book that corresponds to the event's birth round.<br/>
   * The signed event hash SHALL be a SHA-384 hash.<br/>
   * The signed event hash SHALL have the following inputs, in the specified order:<br/>
   * 1. The bytes of the serialized `event_core` field<br/>
   * 2. The bytes of serialized parent event descriptors, in order they appear in 'parents' field.
   * 3. The concatenation of SHA-384 hashes of every individual transaction byte array, in the order the transactions appear in the `transactions` field
   */
  bytes signature = 2;

  /**
   * A list of serialized transactions.
   * <p>
   * This field MAY contain zero transactions.<br/>
   * Each transaction in this list SHALL be presented exactly as
   * it was supplied to the consensus algorithm.<br/>
   * This field MUST contain one entry for each transaction
   * included in this gossip event.
   */
  repeated bytes transactions = 4;

  /**
   * A list of EventDescriptors representing the parents of this event.<br/>
   * The list of parents SHALL include zero or one self parents, and zero or more other parents.<br/>
   * The first element of the list SHALL be the self parent, if one exists.<br/>
   * The list of parents SHALL NOT include more than one parent from the same creator.
   * <p>
   * NOTE: This field is currently being migrated from EventCore to GossipEvent.
   * Once the migration is complete, this field will be removed from EventCore.
   * While migration is ongoing, the expectation is that only one of the two
   * fields will be set, but not both.
   */
  repeated EventDescriptor parents = 5;
}

/**
 * Contains information about an event and its parents.
 */
message EventCore {
  /**
   * The creator node identifier.<br/>
   * This SHALL be the unique identifier for the node that created the event.<br/>
   * This SHALL match the ID of the node as it appears in the address book.
   */
  int64 creator_node_id = 1;

  /**
   * The birth round of the event.<br/>
   * The birth round SHALL be the pending consensus round at the time the event is created.<br/>
   * The pending consensus round SHALL be **one greater** than the latest round to reach consensus.
   */
  int64 birth_round = 2;

  /**
   * The wall clock time at which the latest information affecting this event was received by this node.<br/>
   * The information includes: parents, transactions and birth round<br/>
   * If the event is a genesis event, and has no transactions, this value will just be the wall clock time when creating this event.<br/>
   * If the event has a self parent, this timestamp MUST be strictly greater than the `time_created` of the self parent.
   */
  proto.Timestamp time_created = 3;

  /**
   * A random number used in the unlikely case that the consensus algorithm gets to a coin vote.<br/>
   * This number SHALL be used to break ties in the consensus algorithm.<br/>
   * The number SHALL be between 0 and the size of the roster (inclusive) for the event's birth round.<br/>
   * For example, if the roster size is 10, the minimum value is 0 and the maximum value is 10.<br/>
   */
  int64 coin = 5;
}

/**
 * Unique identifier for an event.
 */
message EventDescriptor {
  /**
   * The hash of the event.<br/>
   * The hash SHALL be a SHA-384 hash.<br/>
   * The hash SHALL have the following inputs, in the specified order:<br/>
   * 1. The bytes of the `EventCore` protobuf<br/>
   * 2. The SHA-384 hash of each individual `EventTransaction`, in the order the transactions appear in the `event_transactions` field of the `GossipEvent` protobuf
   */
  bytes hash = 1;

  /**
   * The creator node identifier.<br/>
   * This SHALL be the unique identifier for the node that created the event.<br/>
   * This SHALL match the ID of the node as it appears in the address book.
   */
  int64 creator_node_id = 2;

  /**
   * The birth round of the event.<br/>
   * The birth round SHALL be the pending consensus round at the time the event is created.<br/>
   * The pending consensus round SHALL be **one greater** than the latest round to reach consensus.
   */
  int64 birth_round = 3;

}


```

### Impact on Mirror Node

No impact on mirror node

### Impact on SDK

No impact on SDK

## Backwards Compatibility

This feature is NOT backward compatible in any way, as currently the entire gossip protocol has zero support for any kind of backward or forward compatibility. All nodes have to be updated at the same time. If communication is attempted between nodes of different versions, they will NOT connect (not because of this feature, but because of the currently implemented protection in network handshake).

As it is not a user facing functionality, there are no issues with compatibility against SDK, event scrapers or anything like that.

## Security Implications

There are no new security implications here. Connection is already established and authorized for purposes of gossip. Events are serialized, signed, verified and interpreted exactly in the same way as they are currently done when coming from sync protocol.

## Reference Implementation

Reference implementation is done in PR [#20348](https://github.com/hiero-ledger/hiero-consensus-node/pull/20348)

## Rejected Ideas

None so far

## Open Issues

None so far

## References

Pull request with the implementation [#20348](https://github.com/hiero-ledger/hiero-consensus-node/pull/20348)

## Copyright/license

This document is licensed under the Apache License, Version 2.0 —
see [LICENSE](../LICENSE) or <https://www.apache.org/licenses/LICENSE-2.0>.
