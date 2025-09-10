// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.sync.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;

/**
 * Configuration of the sync gossip algorithm
 *
 * @param syncSleepAfterFailedNegotiation    the number of milliseconds to sleep after a failed negotiation when running
 *                                           the sync-as-a-protocol algorithm
 * @param syncProtocolPermitCount            the number of permits to use when running the sync algorithm
 * @param onePermitPerPeer                   if true, allocate exactly one sync permit per peer, ignoring
 *                                           {@link #syncProtocolPermitCount()}. Otherwise, allocate permits according
 *                                           to {@link #syncProtocolPermitCount()}.
 * @param syncProtocolHeartbeatPeriod        the period at which the heartbeat protocol runs when the sync algorithm is
 *                                           active (milliseconds)
 * @param filterLikelyDuplicates             if true then do not send events that are likely to be duplicates when they
 *                                           are received by the peer
 * @param nonAncestorFilterThreshold         ignored if {@link #filterLikelyDuplicates} is false. For each event that is
 *                                           not a self event and is not an ancestor of a self event, we must know about
 *                                           the event for at least this amount of time before the event is eligible to
 *                                           be sent
 * @param syncKeepalivePeriod                send a keepalive message every this many milliseconds when reading events
 *                                           during a sync
 * @param maxSyncTime                        the maximum amount of time to spend syncing with a peer, syncs that take
 *                                           longer than this will be aborted
 * @param maxSyncEventCount                  the maximum number of events to send in a sync, or 0 for no limit
 * @param unhealthyGracePeriod               the amount of time the system can be in an unhealthy state before sync
 *                                           permits begin to be revoked
 * @param permitsRevokedPerSecond            the number of permits to revoke per second when the system is unhealthy and
 *                                           the grace period has expired
 * @param permitsReturnedPerSecond           the number of permits to return per second when the system is healthy
 * @param minimumHealthyUnrevokedPermitCount the minimum number of permits that must be unrevoked when the system is in
 *                                           a healthy state. If non-zero, this means that this number of permits is
 *                                           immediately returned as soon as the system becomes healthy.
 * @param rpcSleepAfterSync                  time after finishing the sync in which new sync will not be attempted;
 *                                           currently ignored and assumed 0 for old style network sync, used only for
 *                                           rpc sync; current implementation is limited by
 *                                           {@link #rpcIdleDispatchPollTimeout} regarding worst-case frequency of
 *                                           synchronizations
 * @param rpcIdleWritePollTimeout            how long should gossip rpc mechanism wait between actions piggybacking on
 *                                           write threads if no events are ready to be sent; for example, ping logic is
 *                                           executed there and in case no other writes are performed, this determines
 *                                           resolution of the ping initiation (ping is still performed roughly once per
 *                                           second, regardless of this setting)
 * @param rpcIdleDispatchPollTimeout         how long should gossip rpc mechanism wait between dispatch actions if no
 *                                           events are ready to be processed (for example synchronization start)
 * @param fairMaxConcurrentSyncs             maximum number of concurrent syncs running after which we won't initiate
 *                                           any more outgoing syncs (but can accept incoming ones) if set &lt;= 0,
 *                                           disabled entire fair sync logic (syncs will always be initiated if no other
 *                                           reasons block them) if set &gt; 0 and &lt;= 1, this number is set as a
 *                                           ratio of total number of nodes in the network if &gt; 1, ceiling of that
 *                                           number is used as limit of concurrent syncs
 * @param fairMinimalRoundRobinSize          minimal number of synchronizations which happened in the past and are not
 *                                           currently running which has to be breached before sync against same peer
 *                                           can be considered if set &gt; 0 and &lt;= 1, this number is set as a ratio
 *                                           of total number of nodes in the network if &gt; 1, ceiling of that number
 *                                           is used as minimal round robin size
 * @param keepSendingEventsWhenUnhealthy     when enabled, instead of completely reducing number of syncs when system is
 *                                           unhealthy, we will just stop receiving and processing remote events, while
 *                                           we still continue sending our own events
 */
@ConfigData("sync")
public record SyncConfig(
        @ConfigProperty(defaultValue = "25") int syncSleepAfterFailedNegotiation,
        @ConfigProperty(defaultValue = "17") int syncProtocolPermitCount,
        @ConfigProperty(defaultValue = "true") boolean onePermitPerPeer,
        @ConfigProperty(defaultValue = "1000") int syncProtocolHeartbeatPeriod,
        @ConfigProperty(defaultValue = "true") boolean waitForEventsInIntake,
        @ConfigProperty(defaultValue = "true") boolean filterLikelyDuplicates,
        @ConfigProperty(defaultValue = "3s") Duration nonAncestorFilterThreshold,
        @ConfigProperty(defaultValue = "500ms") Duration syncKeepalivePeriod,
        @ConfigProperty(defaultValue = "1m") Duration maxSyncTime,
        @ConfigProperty(defaultValue = "5000") int maxSyncEventCount,
        @ConfigProperty(defaultValue = "1s") Duration unhealthyGracePeriod,
        @ConfigProperty(defaultValue = "5") double permitsRevokedPerSecond,
        @ConfigProperty(defaultValue = "1") double permitsReturnedPerSecond,
        @ConfigProperty(defaultValue = "1") int minimumHealthyUnrevokedPermitCount,
        @ConfigProperty(defaultValue = "5ms") Duration rpcSleepAfterSync,
        @ConfigProperty(defaultValue = "5ms") Duration rpcIdleWritePollTimeout,
        @ConfigProperty(defaultValue = "5ms") Duration rpcIdleDispatchPollTimeout,
        @ConfigProperty(defaultValue = "-1") double fairMaxConcurrentSyncs,
        @ConfigProperty(defaultValue = "0.3") double fairMinimalRoundRobinSize,
        @ConfigProperty(defaultValue = "true") boolean keepSendingEventsWhenUnhealthy) {}
