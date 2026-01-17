// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer.hooks;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.pbj.runtime.io.buffer.Bytes;

public record HookInvocation(AccountID ownerId, long hookId, Address ownerAddress, Bytes calldata, long gasLimit) {}
