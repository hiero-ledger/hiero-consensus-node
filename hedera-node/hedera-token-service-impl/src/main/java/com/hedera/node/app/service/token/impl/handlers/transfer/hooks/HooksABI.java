package com.hedera.node.app.service.token.impl.handlers.transfer.hooks;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.esaulpaugh.headlong.abi.Address;

import java.math.BigInteger;

public class HooksABI {
    static final String HOOK_CTX_TUPLE      = "(address,uint256,uint256,string,bytes)";
    static final String ACCOUNT_AMT_TUPLE   = "(address,int64)";
    static final String NFT_TUPLE           = "(address,address,int64)";
    static final String XFER_LIST_TUPLE     = "(" + ACCOUNT_AMT_TUPLE + "[])";
    static final String TOKEN_XFER_LIST_T   = "(address," + ACCOUNT_AMT_TUPLE + "[]," + NFT_TUPLE + "[])";
    static final String TRANSFERS_TUPLE     = "(" + XFER_LIST_TUPLE + "," + TOKEN_XFER_LIST_T + "[])";
    static final String PROPOSED_TRANSFERS_TUPLE      = "(" + TRANSFERS_TUPLE + "," + TRANSFERS_TUPLE + ")";

    // (HookContext, ProposedTransfers) -> bool
    static final TupleType INPUTS = TupleType.parse(HOOK_CTX_TUPLE + "," + PROPOSED_TRANSFERS_TUPLE);
    static final TupleType OUTPUTS = TupleType.parse("(bool)");
    static final Function FN_ALLOW      = new Function("allow",     INPUTS, OUTPUTS);
    static final Function FN_ALLOW_PRE  = new Function("allowPre",  INPUTS, OUTPUTS);
    static final Function FN_ALLOW_POST = new Function("allowPost", INPUTS, OUTPUTS);

    static byte[] encodeAllow(Address owner,
                              BigInteger txnFee,
                              BigInteger gasCost,
                              String memo,
                              byte[] data,
                              Tuple proposed) {
        final var ctx = Tuple.of(owner, txnFee, gasCost, memo, data);
        return FN_ALLOW.encodeCall(Tuple.of(ctx, proposed)).array();
    }

    static byte[] encodeAllowPre(Address owner,
                                 BigInteger txnFee,
                                 BigInteger gasCost,
                                 String memo,
                                 byte[] data,
                                 Tuple proposed) {
        final var ctx = Tuple.of(owner, txnFee, gasCost, memo, data);
        return FN_ALLOW_PRE.encodeCall(Tuple.of(ctx, proposed)).array();
    }

    static byte[] encodeAllowPost(Address owner,
                                  BigInteger txnFee,
                                  BigInteger gasCost,
                                  String memo,
                                  byte[] data,
                                  Tuple proposed) {
        final var ctx = Tuple.of(owner, txnFee, gasCost, memo, data);
        return FN_ALLOW_POST.encodeCall(Tuple.of(ctx, proposed)).array();
    }
}
