package com.hedera.node.app.service.token.impl.handlers.transfer.hooks;

import com.esaulpaugh.headlong.abi.Tuple;

public record HookContext(Tuple proposedTransfers, String memo, long txnFee) {
}
