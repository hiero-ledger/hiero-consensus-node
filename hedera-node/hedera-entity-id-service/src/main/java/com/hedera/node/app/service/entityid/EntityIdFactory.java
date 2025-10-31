// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A strategy for creating entity ids.
 */
public interface EntityIdFactory {
    /**
     * Returns a token id for the given number.
     * @param number the number
     */
    TokenID newTokenId(long number);

    /**
     * Returns a topic id for the given number.
     * @param number the number
     */
    TopicID newTopicId(long number);

    /**
     * Returns a schedule id for the given number.
     * @param number the number
     */
    ScheduleID newScheduleId(long number);

    /**
     * Returns an account id for the given number.
     * @param number the number
     */
    AccountID newAccountId(long number);

    /**
     * Returns an account id for the given alias.
     * @param alias the alias
     */
    AccountID newAccountIdWithAlias(@NonNull Bytes alias);

    /**
     * Returns a default account id with account num UNSET.
     */
    AccountID newDefaultAccountId();

    /**
     * Returns a file id for the given number.
     * @param number the number
     */
    FileID newFileId(long number);

    /**
     * Returns a contract id for the given number.
     * @param number the number
     */
    ContractID newContractId(long number);

    /**
     * Returns an contract id for the given alias.
     * @param evmAddress the evm address
     */
    ContractID newContractIdWithEvmAddress(@NonNull Bytes evmAddress);

    /**
     * Returns a hexadecimal string representation of the given number,
     * including current shard and realm, zero-padded to 20 bytes.
     * @param number the number
     * @return a hexadecimal string representation of the number
     */
    String hexLongZero(long number);
}
