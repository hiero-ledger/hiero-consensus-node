# Rewrite of old sync on top of message-based protocol

## Previous solution

Network communication based on dynamic negotiation of one of 3 protocols (Heartbeat, Sync, Reconnect), each of them hogging socket till it is finished with current task. Implemented using ad-hoc byte sequences.

## Current Implementation

- We need to stay compatible with the reconnect protocol as much as possible
- We don’t want to mess with connection creation/teardown

Decision was to piggyback message based protocol on top of existing network. This means that instead of 3 protocols (Heartbeat, Sync, Reconnect) we have 2 protocols (Rpc and Reconnect). Rpc takes over the socket and handles everything (ping, sync, broadcast), releasing the connection only in case of falling behind, when reconnect will take over the responsibilities.

After the reconnect is done, RPC again grabs the connection forever.

## Future implementation

- Get rid of the reconnect completely (block node and complete re-init of the platform)
- Consider moving rpc mechanism to gRPC, OR implement proper select-based networking

## Goals

- Implement equivalent of old sync, with not-worse performance, which can handle injection of broadcast messages (to allow experimentation with broadcast implementations)
- Test the new approach, have it deployed behind a disabled feature switch and enable at some point (dynamic config would be helpful to turn it off)
- In the meantime and afterwards, experiment with broadcast logic (and later chatter), with highly configurable settings, and benchmark it a lot
- Have things structured in they way that a lot of logic is in a network-agnostic place (not passing Connection everywhere as in the old solution)
- Have code structure that will allow putting alternative sync logic with small effort and next to no refactoring (things like Richard’s “push-only” RPC sync)
- Enough metrics and logs to be able to debug issues with the new implementation

## Non-goals

- Be visibly better than the old implementation for pure sync (if it can be done, great, but we may not be able to do much better)
- Fix outstanding logic issues with sync (at least in the first iteration)

# RPC implementation

After taking over the socket from the network code, the RPC adapter is doing the following:

- In a loop, read bytes from the input socket; exit if it sees the ‘end of conversation’ marker
- In a loop, write pending messages from the outgoing queue to the wire; exit the loop if requested by the system
- If no outgoing messages are written for some amount of time (5ms currently) and at least the given amount of time has passed since the last ping (1s currently), send a ping message; the other side will reply with a ping replay with correlation id, allowing measuring network delay
- if reconnect is supposed to happen, we need to free the connection; this is done by sending end of conversation marker to remote side and waiting for corresponding end of conversation; if other side won’t send anything, we will have socket timeout; if it keeps sending other things (like events), there is an explicit ‘end of conversation’ timeout, which will kill the connection as misbehaving

The actual protocol is as follows:

- int16 batchSize

if batchSize == -1, then it means end of conversation and next bytes will be coming from old network protocol

Then, we have batchSize times message, which is of the format

- byte messageType
- serialized PBJ for the message itself

Current message types are (will change in the future):

```jsx
SYNC_DATA = 1;
KNOWN_TIPS = 2;
EVENT = 3;
EVENTS_FINISHED = 4;
PING = 5;
PING_REPLY = 6;
```

Messages are getting deserialized and passed into following methods

```java
void receiveSyncData(@NonNull SyncData syncMessage);
void receiveTips(@NonNull List<Boolean> tips);
void receiveEvents(@NonNull List<GossipEvent> gossipEvents);
void receiveEventsFinished();
```

There is also corresponding send message interface. Writing messages is asynchronous, they are mostly prepared from the socket read thread and put into a queue, which is later processed by socket write thread.

# Relationships between classes

Left side of the diagram is responsible for interaction with legacy network interface, right side of the diagram is managing the business logic of the communication. In theory, left side can be replaced with any other RPC implementation and right side would be not aware of that.

Top classes are singletons in the application, middle layer is 1-per-peer, bottom classes are internal helper classes of middle tier, there just to reduce the code complexity of the big class.

<img src="rpc-gossip-RuntimeRelationship.png"/>

# Creation dependencies

When new connection appears, RpcProtocol is asked to create a PeerProtocol instance. To do that, it first creates instance of RpcPeerProtocol and then passes it masked as 'RpcSender' to shadowgraph synchronizer, so it can produce RpcPeerHandler. That handler is then injected into RpcPeerProtocol as a class which will be interpreting the incoming messages and everything is returned back to the system.

<img src="rpc-gossip-Creation.drawio.png"/>

# Interactions during sync process

When RpcPeerProtocol gets hold of the specific connection, it enters a loop processing rpc messages (and won't release the hold of the connection, unless reconnection happens).

Three threads are created - 'reader', 'writer' and 'dispatch'.
* Reader thread does blocking reads from the socket and puts deserialized messages on the inputQueue.
* Dispatch thread polls messages from inputQueue and executes business logic for them; important simplification is that all logic on the right side of the diagram is done from that single thread. GossipRpcShadowgraphSynchronizer is shared resource between connections and it is thread-safe. All outgoing messages are pushed to the outputQueue. It also handles periodic wakeups to see if synchronization should be initiated.
* Writer thread polls messages from outputQueue and writes them to the socket in blocking way. It flushes messages if there is nothing else on outputQueue (otherwise, they will get autoflushed by the system or when output buffer gets full). It also handles sending periodic ping messages.

<img src="rpc-gossip-SyncCommunication.drawio.png"/>
