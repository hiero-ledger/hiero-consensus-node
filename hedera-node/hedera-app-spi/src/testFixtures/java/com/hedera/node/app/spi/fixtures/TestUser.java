// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fixtures;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import edu.umd.cs.findbugs.annotations.NonNull;

/** Holds information related to a user used in test {@link Scenarios} */
public record TestUser(@NonNull AccountID accountID, Account account, TestKeyInfo keyInfo) {}
