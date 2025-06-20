// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.transaction.pool;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.utility.Pair;
import com.swirlds.demo.merkle.map.internal.DummyExpectedFCMFamily;
import com.swirlds.demo.merkle.map.internal.ExpectedFCMFamily;
import com.swirlds.demo.platform.HotspotConfiguration;
import com.swirlds.demo.platform.fs.stresstest.proto.VirtualMerkleTransaction;
import com.swirlds.demo.virtualmerkle.random.PTTRandom;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("flaky")
class AccountTransactionFactoryTests {

    /**
     * This test validates that when we set the {@link HotspotConfiguration} to a certain size, we only get
     * a subset of all available keys, and we always get the same subset.
     *
     * For this case, we use a {@code samplingProbability = 1.0} to always use the already generated keys to
     * generate the next one when we build the create accounts, and we can validate that keys update are part
     * of the expected subset.
     */
    @Test
    void buildUpdateAccountTransactionWithHotspotForAll() {
        final PTTRandom random = new PTTRandom();
        final long nodeId = 0;
        final double samplingProbability = 1.0;
        final ExpectedFCMFamily expectedFCMFamily = new DummyExpectedFCMFamily(nodeId);
        final AccountTransactionFactory factory =
                new AccountTransactionFactory(random, samplingProbability, expectedFCMFamily, nodeId, 1_000, 0);

        for (int index = 0; index < 1_000; index++) {
            factory.buildCreateAccountTransaction();
        }

        final HotspotConfiguration hotspot = new HotspotConfiguration();
        hotspot.setFrequency(1.0);
        hotspot.setSize(20);

        for (int index = 0; index < 10_000; index++) {
            final Pair<MapKey, VirtualMerkleTransaction.Builder> pair = factory.buildUpdateAccountTransaction(hotspot);
            final MapKey key = pair.key();
            assertTrue(key.getAccountId() < 20, "Only first 20 accounts are considered but got " + key.getAccountId());
        }
    }

    /**
     * This test validates that when we set the {@link HotspotConfiguration} to a certain size, and frequency,
     * we only get a subset of all available keys, and we always get the same subset for the specified frequency,
     * i.e., with {@code frequency = 0.4} on average at most 40% of the keys will inside the specified subset.
     *
     * For this case, we use a {@code samplingProbability = 1.0} to always use the already generated keys to
     * generate the next one when we build the create accounts, and we can validate that 60% of keys fall out
     * of the specified subset.
     */
    @Test
    void buildUpdateAccountTransactionWithHotspotForSome() {
        final PTTRandom random = new PTTRandom();
        final long nodeId = 0;
        final double samplingProbability = 1.0;
        final ExpectedFCMFamily expectedFCMFamily = new DummyExpectedFCMFamily(nodeId);
        final AccountTransactionFactory factory =
                new AccountTransactionFactory(random, samplingProbability, expectedFCMFamily, nodeId, 1_000, 0);

        for (int index = 0; index < 1_000; index++) {
            factory.buildCreateAccountTransaction();
        }

        final HotspotConfiguration hotspot = new HotspotConfiguration();
        hotspot.setFrequency(0.4);
        hotspot.setSize(20);

        final List<Long> accounts = new ArrayList<>();
        for (int index = 0; index < 10_000; index++) {
            final Pair<MapKey, VirtualMerkleTransaction.Builder> pair = factory.buildUpdateAccountTransaction(hotspot);
            final MapKey key = pair.key();
            if (key.getAccountId() > 20) {
                accounts.add(key.getAccountId());
            }
        }

        assertTrue(accounts.size() < 6_000, "Expected on average a size less than 6K but got " + accounts.size());
    }
}
