// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.meta;

import com.esaulpaugh.headlong.abi.Address;
import com.hederahashgraph.api.proto.java.AccountID;

public record AccountCreationDetails(AccountID createdId, Address evmAddress) {}
