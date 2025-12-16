// SPDX-License-Identifier: WTFPL
pragma solidity >=0.8.4;

import "./OrderType.sol";
import "./DetailDecoder.sol";

library IocFokWriteback {
    using DetailDecoder for uint256;

    /// @dev Enforce market/limit semantics + optional FOK (if you still use a flag for FOK).
    /// If you don’t use a separate FOK flag anymore, drop that param and logic.
    function update(
        mapping(bytes32 => bytes32) storage orders,
        bytes32 prefixKey,
        uint256 orderTypeVal,    // PrefixDecoder.orderType(prefixWord)
        uint256 detailWord,      // uint256(orders[prefixKey])
        uint256 execQty,         // executed this tx (out token units)
        bool convertToLimit
    ) internal {
        uint256 orderQty = detailWord.quantity();

        // Sanity: prevent underflow on write-back
        require(execQty <= orderQty, "exec > requested");

        if (OrderType.isMarketStyle(orderTypeVal)) {
            // MARKET-style: implicit IOC — must execute something and never rest
            require(execQty > 0, "IOC: no fill");
            delete orders[prefixKey];
            return;
        }

        // LIMIT-style: write back remaining qty (or delete if fully filled)
        unchecked {
            uint256 rem = orderQty - execQty; // safe due to require above
            if (rem == 0) {
                delete orders[prefixKey];
            } else {
                if (convertToLimit) { // delete the stop_limit order and replace it as limit
                    delete orders[prefixKey];
                    prefixKey = toLimit(prefixKey);
                }
                bytes32 newDetail = DetailDecoder.encode(
                    rem,
                    detailWord.numerator(),
                    detailWord.denominator(),
                    detailWord.deviation(),
                    detailWord.minFill()
                );
                orders[prefixKey] = newDetail;
            }
        }
    }

    /// @notice Return a new prefix key with orderType set to LIMIT (0).
    /// @dev orderType lives in the top byte [248..255].
    function toLimit(bytes32 prefixKey) internal pure returns (bytes32) {
        uint256 p = uint256(prefixKey);
        // clear the top byte, then OR in LIMIT at that position
        p = (p & ~(uint256(0xFF) << 248)) | (uint256(OrderType.LIMIT) << 248);
        return bytes32(p);
    }

    /// @notice Generic setter if you want to set any orderType (0..255).
    function setOrderType(bytes32 prefixKey, uint8 newType) internal pure returns (bytes32) {
        uint256 p = uint256(prefixKey);
        p = (p & ~(uint256(0xFF) << 248)) | (uint256(newType) << 248);
        return bytes32(p);
    }
}
