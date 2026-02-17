// SPDX-License-Identifier: WTFPL
pragma solidity >=0.8.4;

import './DetailDecoder.sol';
import './IocFokWriteback.sol';
import './OrderType.sol';
import './PRBMathCommon.sol';
import './PrefixDecoder.sol';
import 'common/IHieroAccountAllowanceHook.sol';
import 'common/ISupraRegistry.sol';

contract OrderFlowAllowance is IHieroAccountAllowanceHook {
    using DetailDecoder for uint256;
    using PrefixDecoder for uint256;

    uint256 constant BIPS = 1_000_000;
    uint256 constant STALE_PRICE = 60;

    // abcdabcdabcdabcdabcdabcdabcdabcdabcdabcd
    ISupraRegistry public immutable supra = ISupraRegistry(address(0xABCDabcdABcDabcDaBCDAbcdABcdAbCdABcDABCd));
    error Fail(bytes32 prefix, string reason);
    mapping(bytes32 => bytes32) public orders;

    // ----------------------------- Batch state (one stack slot) -----------------------------
    struct BatchState {
        address inToken;
        address outToken;
        int64   sumOut;
        int64   sumIn;
        uint256 execQty;
        uint256 remaining;
        uint256 needTotal;  // Σ required input
        uint256 feeTotal;   // Σ fees
        uint256 cursor;     // args cursor
        bool    first;
    }

    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external payable override returns (bool) {
        require(msg.value == 0, 'msg.value');
        bytes memory args = context.data;
        require(args.length >= 32, "args too short");
        _validateForCustomFee(proposedTransfers);

        BatchState memory st;
        st.first = true;
        st.cursor = 0;

        while (st.cursor < args.length) {
            // ---- 1) Read 32-byte prefix key ----
            bytes32 prefixKey = _loadBytes32(args, st.cursor);
            st.cursor += 32;

            // Decode prefix fields
            uint256 p = uint256(prefixKey);
            require(block.timestamp < p.expiration(), "expired");

            uint8 ot = p.orderType();
            require( OrderType.isLimitStyle(ot) || OrderType.isMarketStyle(ot), "unsupported type" );

            address inTok  = p.inToken();
            address outTok = p.outToken();
            require(inTok != outTok, "invalid path");

            // Establish pair & aggregate execution once
            if (st.first) {
                st.inToken  = inTok;
                st.outToken = outTok;

                (st.sumOut, st.sumIn) = checkTokenCreditAndDebit(
                    context.owner, proposedTransfers, st.outToken, st.inToken
                );
                require(
                    (st.sumOut < 0 && st.sumIn > 0) || (st.sumOut == 0 && st.sumIn == 0),
                    "signs"
                );
                require(st.sumOut <= 0, "sumOut sign");

                st.execQty   = uint256(uint64(-st.sumOut));
                st.remaining = st.execQty;
                st.first     = false;
            } else {
                require(inTok  == st.inToken,  "mixed inToken");
                require(outTok == st.outToken, "mixed outToken");
            }

            // ---- 2) Load detail for this key ----
            bytes32 dBytes = orders[prefixKey];
            require(dBytes != bytes32(0), "detail missing");
            uint256 d = uint256(dBytes);

            // ---- 3) Compute factorBps (market slippage and/or oracle style) ----
            uint256 factorBps;

            // Standard MARKET (type == 1): apply slippage directly
            if (ot == OrderType.MARKET) {
                uint256 slip = d.deviation();              // 0..1e6 expected
                factorBps = (slip <= BIPS) ? (BIPS - slip) : 0;
            }
            // Oracle-style (STOP_*, STOP_MARKET_*): read proof and compute factor via oracle
            else if (OrderType.isOracleStyle(ot)) {
                require(args.length >= st.cursor + 32, "len oob");
                uint256 proofLen = _loadUint256(args, st.cursor);
                st.cursor += 32;

                require(args.length >= st.cursor + proofLen, "proof oob");
                bytes memory proof = _slice(args, st.cursor, proofLen);
                st.cursor += proofLen;

                factorBps = _oracleFactor(ot, d, st.inToken, st.outToken, proof);  // for STOP_MARKET_* returns (BIPS - slip); for STOP_LIMIT_* returns BIPS
            }
            // Plain LIMIT
            else {
                factorBps = BIPS;
            }

            // ---- 4) Apply this order (greedy allocation + writeback/delete/+optional poke-convert) ----
            _applyOne(orders, prefixKey, p, d, ot, factorBps, st);
        }

        // Must allocate exactly execQty across the batch
        require(st.remaining == 0, "debit too high");

        // Global price/fee guard
        uint256 sumInAbs = uint256(uint64(st.sumIn));
        require(sumInAbs + st.feeTotal >= st.needTotal, "credit too low");

        return true;
    }

    // ----------------------------- Oracle factor -----------------------------
    /// @dev Verifies oracle proof, enforces trigger condition, and returns factorBps
    ///      used to reduce required input for MARKET-style oracle orders.
    function _oracleFactor(
        uint8 ot,
        uint256 d,
        address inToken,
        address outToken,
        bytes memory proof
    ) private returns (uint256 factorBps) {
        ISupraRegistry.PriceInfo memory pi = supra.verifyOracleProofV2(proof);
        require(pi.pairs.length == 1, "oracle: pair length");
        require(
            pi.timestamp[0] <= block.timestamp &&
            pi.timestamp[0] >= block.timestamp - STALE_PRICE,
            "oracle: stale"
        );

        ISupraRegistry.TokenPair memory pair = supra.getPair(pi.pairs[0]);
        // adjust decimals now
        require(
            (inToken == pair.tokenA && outToken == pair.tokenB) ||
            (inToken == pair.tokenB && outToken == pair.tokenA),
            "oracle: wrong pair"
        );

        uint256 decimals = pi.decimal[0];
        require(decimals < 39, "oracle: decimals");
        // oracle returns whole token per whole token. Need to put in sats.
        uint256 oraclePrice = PRBMathCommon.mulDiv(pi.prices[0], 10 ** pair.decimalsA, 10 ** pair.decimalsB);
        uint256 scale = 10 ** decimals;

        // exec price from order's num/den, oriented to oracle (A,B)
        uint256 nmr = d.numerator();
        uint256 dnm = d.denominator();
        uint256 execPrice = (inToken == pair.tokenA)
            ? PRBMathCommon.mulDiv(nmr, scale, dnm)
            : PRBMathCommon.mulDiv(dnm, scale, nmr);

        // for limit orders: slippage detail bits repurposed as deviation from exec price to compute trigger price
        // slippage = BIPS -> no deviation
        uint256 slip = d.deviation(); // reused field

        // STOP_LIMIT: trigger at execPrice * (slip/BIPS). LIMIT keeps factor=1
        uint256 triggerPrice = execPrice;
        if (OrderType.isLimitStyle(ot)) {
            triggerPrice = PRBMathCommon.mulDiv(execPrice, slip, BIPS);
        }

        if (OrderType.isLessThanStyle(ot)) {
            // stop-loss (LT): trigger when oracle <= trigger
            require(oraclePrice <= triggerPrice, "op > tr");
        } else {
            // breakout (GT): trigger when oracle >= trigger
            require(oraclePrice >= triggerPrice, "op < tr");
        }

        // Factor for MARKET variants; LIMIT variants keep factor = 1
        if (OrderType.isMarketStyle(ot)) {
            factorBps = (slip <= BIPS) ? (BIPS - slip) : 0;
        } else {
            factorBps = BIPS;
        }
    }

// ------------------------------- Apply one order (small scope) -------------------------------
    function _applyOne(
        mapping(bytes32 => bytes32) storage orders_,
        bytes32 prefixKey,
        uint256 p,           // uint256(prefixKey)
        uint256 d,           // uint256(detail)
        uint8   ot,
        uint256 factorBps,   // market/stop-market factor; BIPS for limit/stop-limit
        BatchState memory st
    ) private {
        // If the batch has zero execution (poke), st.remaining == st.execQty == 0
        bool poke = (st.execQty == 0);

        // If nothing left to allocate…
        if (st.remaining == 0) {
            if (OrderType.isMarketStyle(ot)) {
                // MARKET / STOP_MARKET: implicit IOC — cannot rest
                revert("IOC: no fill");
            }

            // POKE CONVERSION: For STOP_LIMIT (oracle-style & limit-style), convert to LIMIT with no fill.
            if (poke && OrderType.isOracleStyle(ot) && OrderType.isLimitStyle(ot)) {
                // execQty = 0, convertToLimit = true
                IocFokWriteback.update(
                    orders_,
                    prefixKey,
                    ot,
                    d,
                    0,              // no executed qty
                    true            // convert STOP_* LIMIT -> LIMIT
                );
            }
            return; // LIMITs remain resting; STOP_LIMIT converted above if poke & triggered
        }

        // ---- Normal greedy allocation path (same as before) ----
        uint256 q    = d.quantity();
        uint256 mfb  = d.minFill();
        uint256 nmr  = d.numerator();
        uint256 dnm  = d.denominator();

        uint256 take = q <= st.remaining ? q : st.remaining;

        // per-order minFillBps: take / q >= mfb / 1e6
        if (mfb > 0 && take != 0) {
            require(take * BIPS >= q * mfb, "min fill");
        }

        if (take != 0) {
            // Required input for this order
            uint256 need_i = PRBMathCommon.mulDiv(nmr, take, dnm);
            if (factorBps != BIPS) {
                need_i = PRBMathCommon.mulDiv(need_i, factorBps, BIPS);
            }
            st.needTotal += need_i;

            // Fee per order (feeBips may differ by prefix)
            uint256 feeBips_i = PrefixDecoder.feeBips(p);
            require(feeBips_i < BIPS, "feeBips too high");
            uint256 fee_i = PRBMathCommon.mulDiv(need_i, feeBips_i, BIPS);
            st.feeTotal += fee_i;

            // Apply storage write now (revert later rolls back):
            bool convertToLimit = (OrderType.isOracleStyle(ot) && OrderType.isLimitStyle(ot)); // STOP_LT/STOP_GT
            IocFokWriteback.update(
                orders_,
                prefixKey,
                ot,
                d,
                take,
                convertToLimit
            );

            st.remaining -= take;
        } else {
            // take == 0
            if (OrderType.isMarketStyle(ot)) revert("IOC: no fill");
            // LIMIT/STOP_LIMIT with no allocation: keep resting (no conversion unless poke-mode above)
        }
    }

    // -------------------------------- Byte helpers (memory-safe) --------------------------------
    function _loadBytes32(bytes memory data, uint256 offset) private pure returns (bytes32 out) {
        require(data.length >= offset + 32, "bytes32 oob");
        assembly ("memory-safe") {
            out := mload(add(add(data, 32), offset))
        }
    }

    function _loadUint256(bytes memory data, uint256 offset) private pure returns (uint256 out) {
        require(data.length >= offset + 32, "uint256 oob");
        assembly ("memory-safe") {
            out := mload(add(add(data, 32), offset))
        }
    }

    function _slice(bytes memory data, uint256 offset, uint256 len) private pure returns (bytes memory out) {
        require(data.length >= offset + len, "slice oob");
        assembly ("memory-safe") {
            out := mload(0x40)
            mstore(out, len)
            let dest := add(out, 32)
            let src  := add(add(data, 32), offset)
            // copy in 32B chunks
            for { let i := 0 } lt(i, len) { i := add(i, 32) } {
                mstore(add(dest, i), mload(add(src, i)))
            }
            // bump free mem pointer
            mstore(0x40, add(dest, and(add(len, 31), not(31))))
        }
    }

    // ---------------------------- Existing helpers ----------------------------
    function _validateForCustomFee(ProposedTransfers memory proposedTransfers) internal pure {
        require(proposedTransfers.customFee.tokens.length == 0, "cf tokens present");
        require(proposedTransfers.customFee.hbarAdjustments.length == 0, "cf hbar present");
    }

    // ===== Helper Functions to Check Token Transfers =====
    //
    // These helpers loop through the provided token transfers looking for the token with id `tokenId`
    // and summing the credits (or debits) accordingly.

    // return the total amount of inToken and outToken to be transferred
    // immediately reverts if foreign token is found
    function checkTokenCreditAndDebit(
        address _owner,
        ProposedTransfers memory proposedTransfers,
        address outToken,
        address inToken
    ) public pure returns (int64 sumOut, int64 sumIn) {

        IHieroAccountAllowanceHook.TokenTransferList[] memory tokenTransferList = proposedTransfers.direct.tokens;
        IHieroAccountAllowanceHook.AccountAmount[] memory hbarAdjustments = proposedTransfers.direct.hbarAdjustments;

        for (uint256 i = 0; i < tokenTransferList.length; i++) {
            // get the amount of base token transferred from context.owner
            IHieroAccountAllowanceHook.TokenTransferList memory tokenTransfer = tokenTransferList[i];
            require(tokenTransfer.token != address(0), 'hbar in direct token transfer list');
            require(tokenTransfer.nftTransfers.length == 0, 'nft');

            if (tokenTransfer.token == inToken) {
                for (uint256 j = 0; j < tokenTransfer.adjustments.length; j++) {
                    IHieroAccountAllowanceHook.AccountAmount memory adjustment = tokenTransfer.adjustments[j];
                    if (adjustment.account == _owner) {
                        int64 amt = adjustment.amount;
                        sumIn += amt;
                    }
                }
            }
            else if (tokenTransfer.token == outToken) {
                for (uint256 j = 0; j < tokenTransfer.adjustments.length; j++) {
                    IHieroAccountAllowanceHook.AccountAmount memory adjustment = tokenTransfer.adjustments[j];
                    if (adjustment.account == _owner) {
                        int64 amt = adjustment.amount;
                        sumOut += amt;
                    }
                }
            }
            else {revert('foreign');}
        }

        // get the amount of hbar transferred from context.owner
        for (uint256 j = 0; j < hbarAdjustments.length; j++) {
            if (inToken == address(0)) {
                if (hbarAdjustments[j].account == _owner) {
                    int64 amt = hbarAdjustments[j].amount;
                    sumIn += amt;
                }
            }
            else if(outToken == address(0)) {
                if (hbarAdjustments[j].account == _owner) {
                    int64 amt = hbarAdjustments[j].amount;
                    sumOut += amt;
                }
            }
            else {revert('hbar neither base nor quoted');}
        }

        return (sumOut, sumIn);
    }
}
