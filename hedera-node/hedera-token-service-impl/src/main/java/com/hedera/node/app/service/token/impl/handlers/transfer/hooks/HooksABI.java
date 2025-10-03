// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer.hooks;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import java.math.BigInteger;

public class HooksABI {
    static final String HOOK_CTX_TUPLE = "(address,uint256,uint256,string,bytes)";
    static final String ACCOUNT_AMT_TUPLE = "(address,int64)";
    static final String NFT_TUPLE = "(address,address,int64)";
    ;
    static final String TOKEN_XFER_LIST_TUPLE = "(address," + ACCOUNT_AMT_TUPLE + "[]," + NFT_TUPLE + "[])";
    static final String TRANSFERS_TUPLE = "(" + ACCOUNT_AMT_TUPLE + "[]," + TOKEN_XFER_LIST_TUPLE + "[])";
    static final String PROPOSED_TRANSFERS_TUPLE = "(" + TRANSFERS_TUPLE + "," + TRANSFERS_TUPLE + ")";

    public static final Function FN_ALLOW =
            new Function("allow(" + HOOK_CTX_TUPLE + "," + PROPOSED_TRANSFERS_TUPLE + ")", "(bool)");
    public static final Function FN_ALLOW_PRE =
            new Function("allowPre(" + HOOK_CTX_TUPLE + "," + PROPOSED_TRANSFERS_TUPLE + ")", "(bool)");
    public static final Function FN_ALLOW_POST =
            new Function("allowPost(" + HOOK_CTX_TUPLE + "," + PROPOSED_TRANSFERS_TUPLE + ")", "(bool)");

    public static final Tuple EMPTY_TRANSFERS = Tuple.of(new Tuple[] {}, new Tuple[] {});

    /**
     * Encodes the given invocation and context using the provided function's ABI.
     *
     * @param invocation the hook invocation details
     * @param ctx the hook context
     * @param function the ABI function to use for encoding
     * @return the encoded byte array
     */
    public static byte[] encode(HookCallFactory.HookInvocation invocation, HookContext ctx, Function function) {
        final var context = Tuple.of(
                invocation.ownerAddress(),
                BigInteger.valueOf(ctx.txnFee()),
                BigInteger.valueOf(invocation.gasLimit()),
                ctx.memo(),
                invocation.calldata().toByteArray());
        return function.encodeCall(Tuple.of(context, ctx.proposedTransfers())).array();
    }
}
