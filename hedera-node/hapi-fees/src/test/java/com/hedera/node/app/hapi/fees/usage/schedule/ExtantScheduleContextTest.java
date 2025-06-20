// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.schedule;

import static com.hedera.node.app.hapi.fees.usage.schedule.ExtantScheduleContextTest.SettableField.ADMIN_KEY;
import static com.hedera.node.app.hapi.fees.usage.schedule.ExtantScheduleContextTest.SettableField.NO_ADMIN_KEY;
import static com.hedera.node.app.hapi.fees.usage.schedule.ExtantScheduleContextTest.SettableField.NUM_SIGNERS;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_RICH_INSTANT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.KEY_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.hapi.fees.test.KeyUtils;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.SchedulableTransactionBody;
import java.util.EnumSet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ExtantScheduleContextTest {
    private final int numSigners = 2;
    private final boolean resolved = true;
    private final Key adminKey = KeyUtils.A_THRESHOLD_KEY;
    private final String memo = "Not since life began";
    private final SchedulableTransactionBody scheduledTxn =
            SchedulableTransactionBody.newBuilder().setTransactionFee(123).build();

    enum SettableField {
        NUM_SIGNERS,
        NO_ADMIN_KEY,
        ADMIN_KEY,
        MEMO,
        SCHEDULED_TXN,
        IS_RESOLVED
    }

    @Test
    void buildsAsExpectedWithAllPresent() {
        // given:
        final var ctx = builderWith(EnumSet.allOf(SettableField.class)).build();
        // and:
        final long expectedNonBaseRb = ExtantScheduleContext.METADATA_SIZE
                + BASIC_RICH_INSTANT_SIZE
                + memo.getBytes().length
                + getAccountKeyStorageSize(adminKey)
                + scheduledTxn.getSerializedSize()
                + numSigners * KEY_SIZE;

        // then:
        assertTrue(ctx.isResolved());
        assertSame(memo, ctx.memo());
        assertSame(scheduledTxn, ctx.scheduledTxn());
        assertSame(adminKey, ctx.adminKey());
        assertEquals(numSigners, ctx.numSigners());
        // and:
        assertEquals(expectedNonBaseRb, ctx.nonBaseRb());
    }

    @Test
    void buildsAsExpected() {
        // given:
        final var ctx = builderWith(EnumSet.complementOf(EnumSet.of(ADMIN_KEY))).build();
        // and:
        final long expectedNonBaseRb = ExtantScheduleContext.METADATA_SIZE
                + BASIC_RICH_INSTANT_SIZE
                + memo.getBytes().length
                + scheduledTxn.getSerializedSize()
                + ctx.numSigners() * KEY_SIZE;

        // then:
        assertTrue(ctx.isResolved());
        assertNull(ctx.adminKey());
        assertEquals(memo, ctx.memo());
        assertSame(scheduledTxn, ctx.scheduledTxn());
        assertEquals(numSigners, ctx.numSigners());
        // and:
        assertEquals(expectedNonBaseRb, ctx.nonBaseRb());
    }

    @Test
    void requiresAllFieldsSet() {
        final var numSigners = builderWith(EnumSet.complementOf(EnumSet.of(NUM_SIGNERS)));
        final var adminAndNoAdmin =
                builderWith(EnumSet.complementOf(EnumSet.of(SettableField.ADMIN_KEY, NO_ADMIN_KEY)));
        final var memo = builderWith(EnumSet.of(SettableField.MEMO));
        final var scheduleTxn = builderWith(EnumSet.complementOf(EnumSet.of(SettableField.SCHEDULED_TXN)));
        final var isResolved = builderWith(EnumSet.complementOf(EnumSet.of(SettableField.IS_RESOLVED)));
        // expect:
        Assertions.assertThrows(IllegalStateException.class, () -> numSigners.build());
        Assertions.assertThrows(IllegalStateException.class, () -> adminAndNoAdmin.build());
        Assertions.assertThrows(IllegalStateException.class, () -> memo.build());
        Assertions.assertThrows(IllegalStateException.class, () -> scheduleTxn.build());
        Assertions.assertThrows(IllegalStateException.class, () -> isResolved.build());
    }

    private ExtantScheduleContext.Builder builderWith(final EnumSet<SettableField> fieldsSet) {
        final var builder = ExtantScheduleContext.newBuilder();

        for (final SettableField field : fieldsSet) {
            switch (field) {
                case NUM_SIGNERS:
                    builder.setNumSigners(numSigners);
                    break;
                case NO_ADMIN_KEY:
                    builder.setNoAdminKey();
                    break;
                case ADMIN_KEY:
                    builder.setAdminKey(adminKey);
                    break;
                case MEMO:
                    builder.setMemo(memo);
                    break;
                case SCHEDULED_TXN:
                    builder.setScheduledTxn(scheduledTxn);
                    break;
                case IS_RESOLVED:
                    builder.setResolved(resolved);
                    break;
            }
        }

        return builder;
    }
}
