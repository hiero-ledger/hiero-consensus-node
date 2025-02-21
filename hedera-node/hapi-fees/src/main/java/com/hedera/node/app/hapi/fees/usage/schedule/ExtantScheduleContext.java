// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.schedule;

import static com.hedera.node.app.hapi.fees.usage.schedule.entities.ScheduleEntitySizes.SCHEDULE_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_RICH_INSTANT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_TX_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BOOL_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;

import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.SchedulableTransactionBody;
import java.nio.charset.StandardCharsets;

public class ExtantScheduleContext {
    static final long METADATA_SIZE =
            /* The schedule id, the scheduling account, and the responsible payer */
            3 * BASIC_ENTITY_ID_SIZE
                    +
                    /* The expiration time */
                    BASIC_RICH_INSTANT_SIZE
                    +
                    /* The scheduled transaction id */
                    BASIC_TX_ID_SIZE
                    + BOOL_SIZE;

    private final int numSigners;
    private final Key adminKey;
    private final String memo;
    private final boolean resolved;
    private final SchedulableTransactionBody scheduledTxn;

    private ExtantScheduleContext(final ExtantScheduleContext.Builder builder) {
        resolved = builder.resolved;
        numSigners = builder.numSigners;
        memo = builder.memo;
        adminKey = builder.adminKey;
        scheduledTxn = builder.scheduledTxn;
    }

    public long nonBaseRb() {
        return METADATA_SIZE
                /* If the schedule has been resolved (i.e. deleted or executed), then
                we store the resolution timestamp. */
                + (resolved ? BASIC_RICH_INSTANT_SIZE : 0)
                + memo.getBytes(StandardCharsets.UTF_8).length
                + getAccountKeyStorageSize(adminKey)
                + scheduledTxn.getSerializedSize()
                + SCHEDULE_ENTITY_SIZES.bytesUsedForSigningKeys(numSigners);
    }

    public Key adminKey() {
        return adminKey;
    }

    public int numSigners() {
        return numSigners;
    }

    public String memo() {
        return memo;
    }

    public boolean isResolved() {
        return resolved;
    }

    public SchedulableTransactionBody scheduledTxn() {
        return scheduledTxn;
    }

    public static ExtantScheduleContext.Builder newBuilder() {
        return new ExtantScheduleContext.Builder();
    }

    public static class Builder {
        private static final int IS_RESOLVED_MASK = 1 << 0;
        private static final int SCHEDULED_TXN_MASK = 1 << 1;
        private static final int MEMO_MASK = 1 << 2;
        private static final int ADMIN_KEY_MASK = 1 << 3;
        private static final int NUM_SIGNERS_MASK = 1 << 4;

        private static final int ALL_FIELDS_MASK =
                NUM_SIGNERS_MASK | SCHEDULED_TXN_MASK | MEMO_MASK | ADMIN_KEY_MASK | IS_RESOLVED_MASK;
        private int mask = 0;

        private int numSigners;
        private Key adminKey;
        private String memo;
        private boolean resolved;
        private SchedulableTransactionBody scheduledTxn;

        private Builder() {}

        public ExtantScheduleContext build() {
            if (mask != ALL_FIELDS_MASK) {
                throw new IllegalStateException(String.format("Field mask is %d, not %d!", mask, ALL_FIELDS_MASK));
            }
            return new ExtantScheduleContext(this);
        }

        public ExtantScheduleContext.Builder setNumSigners(final int numSigners) {
            this.numSigners = numSigners;
            mask |= NUM_SIGNERS_MASK;
            return this;
        }

        public ExtantScheduleContext.Builder setScheduledTxn(final SchedulableTransactionBody scheduledTxn) {
            this.scheduledTxn = scheduledTxn;
            mask |= SCHEDULED_TXN_MASK;
            return this;
        }

        public ExtantScheduleContext.Builder setMemo(final String memo) {
            this.memo = memo;
            mask |= MEMO_MASK;
            return this;
        }

        public ExtantScheduleContext.Builder setAdminKey(final Key adminKey) {
            this.adminKey = adminKey;
            mask |= ADMIN_KEY_MASK;
            return this;
        }

        public ExtantScheduleContext.Builder setNoAdminKey() {
            this.adminKey = null;
            mask |= ADMIN_KEY_MASK;
            return this;
        }

        public ExtantScheduleContext.Builder setResolved(final boolean flag) {
            this.resolved = flag;
            mask |= IS_RESOLVED_MASK;
            return this;
        }
    }
}
