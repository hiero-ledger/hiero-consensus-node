// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test;

import com.hedera.node.app.hapi.utils.ethereum.AccessList;
import com.hedera.node.app.hapi.utils.ethereum.CodeDelegation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

public final class TestingTransactionUtils {

    private TestingTransactionUtils() {
    }

    /**
     * Generate configurable AccessList for tests. @see <a href="https://eips.ethereum.org/EIPS/eip-2930">EIP-2930</a>
     *
     * @param keysCount storage keys in corresponding access list object to generate
     * @return AccessList
     */
    @NonNull
    public static List<AccessList> generateAccessList(final List<Integer> keysCount) {
        final List<AccessList> accessLists = new ArrayList<>();
        for (final Integer count : keysCount) {
            accessLists.add(new AccessList(
                    TestingByteUtils.randomAddressBytes(),
                    IntStream.range(0, count)
                            .mapToObj(e -> TestingByteUtils.randomKeyBytes32())
                            .toList()));
        }
        return accessLists;
    }

    /**
     * Generate configurable AuthorisationList/CodeDelegations for tests. @see <a href="https://eips.ethereum.org/EIPS/eip-7702">EIP-7702</a>
     *
     * @param codeDelegationsCount amount of code delegations to generate
     * @return CodeDelegations
     */
    @NonNull
    public static List<CodeDelegation> generateAuthList(int codeDelegationsCount) {
        return IntStream.range(0, codeDelegationsCount)
                .mapToObj(e -> new CodeDelegation(
                        TestingByteUtils.randomBytes(2),
                        TestingByteUtils.randomAddressBytes(),
                        0,
                        1,
                        TestingByteUtils.randomBytes(32),
                        TestingByteUtils.randomBytes(32)))
                .toList();
    }
}
