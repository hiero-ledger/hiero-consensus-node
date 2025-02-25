// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fixtures;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import edu.umd.cs.findbugs.annotations.NonNull;

/** Holds information related to a node used in test {@link Scenarios} */
public record TestNode(
        long nodeNumber, @NonNull AccountID nodeAccountID, @NonNull Account account, @NonNull TestKeyInfo keyInfo) {}
