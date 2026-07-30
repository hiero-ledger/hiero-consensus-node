// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test;

import com.hedera.node.app.hapi.utils.ethereum.AccessListItem;
import com.hedera.node.app.hapi.utils.ethereum.CodeDelegation;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes;

public final class TestTransactionUtils {

    private TestTransactionUtils() {}

    /**
     * Generate configurable AccessListItem for tests. @see <a href="https://eips.ethereum.org/EIPS/eip-2930">EIP-2930</a>
     *
     * @param keysCount storage keys in corresponding access list object to generate
     * @return AccessListItem
     */
    @NonNull
    public static List<AccessListItem> generateAccessList(final List<Integer> keysCount) {
        final List<AccessListItem> accessLists = new ArrayList<>();
        for (final Integer count : keysCount) {
            accessLists.add(new AccessListItem(
                    Bytes.wrap(TestByteUtils.randomAddressBytes()),
                    IntStream.range(0, count)
                            .mapToObj(e -> TestByteUtils.randomKeyBytes32())
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
    public static List<CodeDelegation> generateAuthList(final int codeDelegationsCount) {
        return IntStream.range(0, codeDelegationsCount)
                .mapToObj(e -> new CodeDelegation(
                        TestByteUtils.randomBytes(2),
                        TestByteUtils.randomAddressBytes(),
                        0,
                        1,
                        TestByteUtils.randomBytes(32),
                        TestByteUtils.randomBytes(32)))
                .toList();
    }
}
