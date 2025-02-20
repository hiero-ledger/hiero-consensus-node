// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy;

/**
 * @deprecated This is being used for the old (and fragile) style of String.contains log parsing.
 * 		Do not use this for any new logs.
 */
@Deprecated
public abstract class PlatformLogMessages {
    @Deprecated
    public static final String START_RECONNECT = "start reconnect";

    @Deprecated
    public static final String CHANGED_TO_ACTIVE = "Platform status changed to: ACTIVE";

    @Deprecated
    public static final String FINISHED_RECONNECT = "finished reconnect";

    @Deprecated
    public static final String RECV_STATE_HASH_MISMATCH = "Hash from received signed state does not match";

    @Deprecated
    public static final String RECV_STATE_ERROR = "Error while receiving a SignedState";

    @Deprecated
    public static final String RECV_STATE_IO_EXCEPTION = "IOException while receiving a SignedState";

    @Deprecated
    public static final String CHANGED_TO_BEHIND = "Platform status changed to: BEHIND";

    @Deprecated
    public static final String FALL_BEHIND_DO_NOT_RECONNECT = "has fallen behind, will die";

    @Deprecated
    public static final String SYNC_STALE_COMPENSATION_SUCCESS = "Compensating for stale events during gossip";

    @Deprecated
    public static final String SYNC_STALE_COMPENSATION_FAILURE =
            "Failed to compensate for stale events during gossip" + " due to delta exceeding threshold";
}
