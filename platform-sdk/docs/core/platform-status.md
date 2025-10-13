# Platform Status

The platform status is represented as an enum value from `PlatformStatus`. Status transitions are handled by
the `PlatformStatusManager`.

A diagram of the status state machine is available [here](./platform-status-transitions.svg).

## Statuses

### STARTING_UP

This is the initial status of the platform. Under normal operation, the platform will always transition from
`STARTING_UP` to `REPLAYING_EVENTS`.

### REPLAYING_EVENTS

Immediately after starting up, before beginning to gossip, the platform transitions to `REPLAYING_EVENTS`, at which time
it will replay events from the preconsensus event stream. Once it is done replaying events, it will transition to
`OBSERVING` under normal operation, or `FREEZE_COMPLETE` if a freeze timestamp was crossed during replay.

Even if the platform is not configured to replay events from the preconsensus event stream, it will still
pass through this status, for the sake of consistency.

### OBSERVING

Upon transitioning to `OBSERVING`, the platform will begin gossiping, but will not create events. It will remain in this
status for a period of wall clock time, before transitioning to `CHECKING`.

The purpose of spending time in the `OBSERVING` status is to allow the platform to listen to gossip, and discover
self events that may have been created prior to the platform starting up. It is possible that the node previously
created and gossiped out self events, but that a crash prevented those events from being durably written to disk.
In such a situation, it is important for the node to rediscover these old events prior to creating new events. If
a node were to immediately begin creating new events before learning of previous self events, this would cause a
branch. Branching is not catastrophic, but should be avoided if possible.

### CHECKING

After transitioning to `CHECKING`, the platform will begin to create events, but will not yet accept app transactions.
It will remain in `CHECKING` until a self event is observed reaching consensus, at which time the platform will
transition to `ACTIVE`.

The platform doesn't accept app transaction in this phase, since there should be a high degree of confidence that any
accepted app transactions will successfully be able to reach consensus. The best way to gain this confidence is simply
to wait until self events start reaching consensus.

The platform will immediately transition to `ACTIVE` from `CHECKING` in case of being instructed to quiesce.

### ACTIVE

While in the `ACTIVE` status, the platform is creating events and accepting app transactions (unless some conditions
prevents the creation of events like peers reporting being behind, an unhealthy state or quiesce).
The platform will remain in `ACTIVE` status while being instructed to quiesce.
Once instructed to stop quiescing, it will transition back to `CHECKING` if a grace period since the stop quiescing
instruction elapsed and enough time has passed since a self event last reaching consensus.

### BEHIND

The platform transitions to `BEHIND` when it has fallen behind the rest of the network. The platform isn't gossiping,
and will remain in `BEHIND` while performing a reconnect. Once the reconnect is complete, the platform will transition
to `RECONNECT_COMPLETE`.

It is possible to transition to `BEHIND` from `OBSERVING`, `CHECKING`, `ACTIVE`, or `RECONNECT_COMPLETE`.

### RECONNECT_COMPLETE

After completing a reconnect, the platform will resume gossiping, but won't create any events. If the platform
determines that it is still behind the rest of the network, it will transition back to `BEHIND`. Otherwise, it will
remain in `RECONNECT_COMPLETE` until the state received during the reconnect has been saved to disk.

The reconnect state must be saved to disk for the node to be able to replay events from the preconsensus event stream
with a valid starting point. Refraining from creating events prior to saving the reconnect state ensures that any node
creating events has a valid state to replay from, and that the network can't get itself into a situation where no node
has this ability.

### FREEZING

The platform transitions to `FREEZING` when it has crossed a freeze timestamp and is in the process of freezing. The
platform is gossiping, and is permitted to create events, but will not produce any additional events after creating
one with its self signature for the freeze state. It is possible to transition to `FREEZING` from `OBSERVING`,
`CHECKING`, `ACTIVE`, or `RECONNECT_COMPLETE`.

The platform remains in `FREEZING` until the freeze state has been written to disk, at which point it transitions to
`FREEZE_COMPLETE`.

### FREEZE_COMPLETE

While in `FREEZE_COMPLETE`, the platform has completed the freeze. It is still gossiping, so that signatures on the
freeze state can be distributed to nodes that don't yet have them. The platform cannot exit `FREEZE_COMPLETE`, and will
eventually be shut down.

It is possible to enter `FREEZE_COMPLETE` from either `REPLAYING_EVENTS` or `FREEZING`.

### CATASTROPHIC_FAILURE

The platform transitions to `CATASTROPHIC_FAILURE` when it has encountered a catastrophic failure. The platform
cannot recover from this state.
