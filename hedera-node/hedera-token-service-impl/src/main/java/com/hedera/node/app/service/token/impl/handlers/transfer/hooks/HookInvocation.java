package com.hedera.node.app.service.token.impl.handlers.transfer.hooks;

import com.hedera.hapi.node.base.AccountID;

public record HookInvocation(
        long hookId,
        AccountID ownerId,
        boolean isPost,
        byte[] abiEncodedInput,
        long gasLimit) {}