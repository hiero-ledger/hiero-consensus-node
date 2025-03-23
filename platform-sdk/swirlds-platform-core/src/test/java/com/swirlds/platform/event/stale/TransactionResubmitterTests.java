// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.stale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.platform.event.EventTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.event.resubmitter.DefaultTransactionResubmitter;
import com.swirlds.platform.event.resubmitter.TransactionResubmitter;
import com.swirlds.platform.test.fixtures.event.TestingEventBuilder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.junit.jupiter.api.Test;

class TransactionResubmitterTests {

    // commenting out this test because we turned off resubmitting
    //    @Test
    //    void basicBehaviorTest() {
    //        final Randotron randotron = Randotron.create();
    //
    //        final long maxSignatureAge = randotron.nextInt(50, 500);
    //        final Configuration configuration = new TestConfigBuilder()
    //                .withValue(StateConfig_.MAX_SIGNATURE_RESUBMIT_AGE, maxSignatureAge)
    //                .getOrCreateConfig();
    //
    //        final PlatformContext platformContext = TestPlatformContextBuilder.create()
    //                .withConfiguration(configuration)
    //                .build();
    //
    //        // the round must be high enough to allow events to be too old
    //        final long currentRound = randotron.nextLong(1000, 2000);
    //        final EventWindow eventWindow = new EventWindow(
    //                currentRound,
    //                1 /* ignored by resubmitter */,
    //                1 /* ignored by resubmitter */,
    //                AncientMode.BIRTH_ROUND_THRESHOLD);
    //
    //        final TransactionResubmitter resubmitter = new DefaultTransactionResubmitter(platformContext);
    //        resubmitter.updateEventWindow(eventWindow);
    //
    //        final int transactionCount = randotron.nextInt(1, 100);
    //        final List<EventTransaction> transactions = new ArrayList<>();
    //        final List<EventTransaction> systemTransactions = new ArrayList<>();
    //        for (int i = 0; i < transactionCount; i++) {
    //            final boolean systemTransaction = randotron.nextBoolean();
    //            final EventTransaction transaction;
    //            if (systemTransaction) {
    //
    //                final boolean tooOld = randotron.nextBoolean(0.1);
    //                final long round;
    //
    //                if (tooOld) {
    //                    if (currentRound - maxSignatureAge > 1) {
    //                        round = randotron.nextLong(1, currentRound - maxSignatureAge);
    //                    } else {
    //                        round = 1;
    //                    }
    //                } else {
    //                    round = randotron.nextLong(currentRound - maxSignatureAge, currentRound);
    //                }
    //
    //                final StateSignatureTransaction payload = new StateSignatureTransaction(
    //                        round, randotron.nextSignature().getBytes(), randotron.nextHashBytes());
    //                transaction = new EventTransaction(new OneOf<>(STATE_SIGNATURE_TRANSACTION, payload));
    //
    //                if (!tooOld) {
    //                    systemTransactions.add(transaction);
    //                }
    //            } else {
    //                final ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
    //                buffer.putLong(randotron.nextLong());
    //                transaction = new EventTransaction(new OneOf<>(APPLICATION_TRANSACTION,
    // Bytes.wrap(buffer.array())));
    //            }
    //            transactions.add(transaction);
    //        }
    //
    //        final PlatformEvent event =
    //                new TestingEventBuilder(randotron).setTransactions(transactions).build();
    //
    //        final List<EventTransaction> transactionsToResubmit = resubmitter.resubmitStaleTransactions(event);
    //
    //        assertEquals(systemTransactions.size(), transactionsToResubmit.size());
    //        for (int i = 0; i < systemTransactions.size(); i++) {
    //            assertEquals(systemTransactions.get(i), transactionsToResubmit.get(i));
    //        }
    //    }

    @Test
    void noSystemTransactionsTest() {
        final Randotron randotron = Randotron.create();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final long currentRound = randotron.nextLong(1, 1000);
        final EventWindow eventWindow = new EventWindow(
                currentRound,
                1 /* ignored by resubmitter */,
                1 /* ignored by resubmitter */,
                AncientMode.BIRTH_ROUND_THRESHOLD);

        final TransactionResubmitter resubmitter = new DefaultTransactionResubmitter(platformContext);
        resubmitter.updateEventWindow(eventWindow);

        final int transactionCount = randotron.nextInt(1, 100);
        final List<Bytes> transactions = new ArrayList<>();
        for (int i = 0; i < transactionCount; i++) {
            final ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
            buffer.putLong(randotron.nextLong());
            transactions.add(Bytes.wrap(buffer.array()));
        }

        final PlatformEvent event = new TestingEventBuilder(randotron)
                .setTransactionBytes(transactions)
                .build();

        final List<EventTransaction> transactionsToResubmit = resubmitter.resubmitStaleTransactions(event);
        assertEquals(0, transactionsToResubmit.size());
    }

    @Test
    void noTransactionsTest() {
        final Randotron randotron = Randotron.create();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final long currentRound = randotron.nextLong(1, 1000);
        final EventWindow eventWindow = new EventWindow(
                currentRound,
                1 /* ignored by resubmitter */,
                1 /* ignored by resubmitter */,
                AncientMode.BIRTH_ROUND_THRESHOLD);

        final TransactionResubmitter resubmitter = new DefaultTransactionResubmitter(platformContext);
        resubmitter.updateEventWindow(eventWindow);

        final PlatformEvent event = new TestingEventBuilder(randotron)
                .setTransactions(Collections.emptyList())
                .build();

        final List<EventTransaction> transactionsToResubmit = resubmitter.resubmitStaleTransactions(event);
        assertEquals(0, transactionsToResubmit.size());
    }

    @Test
    void eventWindowNotSetTest() {
        final Randotron randotron = Randotron.create();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final TransactionResubmitter resubmitter = new DefaultTransactionResubmitter(platformContext);

        final PlatformEvent event = new TestingEventBuilder(randotron)
                .setTransactions(Collections.emptyList())
                .build();

        assertThrows(IllegalStateException.class, () -> resubmitter.resubmitStaleTransactions(event));
    }
}
