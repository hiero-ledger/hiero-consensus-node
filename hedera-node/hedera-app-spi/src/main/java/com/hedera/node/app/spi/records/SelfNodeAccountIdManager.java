// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.records;

import com.hedera.hapi.node.base.AccountID;

public interface SelfNodeAccountIdManager {

    String getSelfNodeAccountId();

    void setSelfNodeAccountId(final AccountID accountId);
}
