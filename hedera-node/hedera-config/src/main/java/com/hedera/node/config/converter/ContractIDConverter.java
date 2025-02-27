// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import com.hedera.hapi.node.base.ContractID;
import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.stream.Stream;

/**
 * Config api {@link ConfigConverter} implementation for the type {@link ContractID}.
 */
public class ContractIDConverter implements ConfigConverter<ContractID> {

    @Override
    public ContractID convert(@NonNull final String value) throws IllegalArgumentException, NullPointerException {
        if (value == null) {
            throw new NullPointerException("null can not be converted");
        }
        try {
            final long[] nums =
                    Stream.of(value.split("[.]")).mapToLong(Long::valueOf).toArray();
            if (nums.length != 3) {
                throw new IllegalArgumentException("Does not match pattern 'A.B.C'");
            }
            if (nums[0] < 0) {
                throw new IllegalArgumentException("Shared num of value is negative");
            }
            if (nums[1] < 0) {
                throw new IllegalArgumentException("Realm num of value is negative");
            }
            if (nums[2] < 0) {
                throw new IllegalArgumentException("Account num of value is negative");
            }
            return ContractID.newBuilder()
                    .shardNum(nums[0])
                    .realmNum(nums[1])
                    .contractNum(nums[2])
                    .build();
        } catch (final Exception e) {
            throw new IllegalArgumentException("'" + value + "' can not be parsed to " + ContractID.class.getName(), e);
        }
    }
}
