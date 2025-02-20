// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of a {@code CryptoUpdate}
 * transaction.
 */
public interface CryptoUpdateStreamBuilder extends StreamBuilder {
    /**
     * Tracks update of a new account by number. Even if someday we support creating multiple
     * accounts within a smart contract call, we will still only need to track one created account
     * per child record.
     *
     * @param accountID the {@link AccountID} of the new account
     * @return this builder
     */
    @NonNull
    CryptoUpdateStreamBuilder accountID(@NonNull AccountID accountID);
}
