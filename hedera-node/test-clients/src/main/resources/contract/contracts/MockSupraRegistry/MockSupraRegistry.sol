// SPDX-License-Identifier: MIT
pragma solidity >=0.8.4;

import "common/ISupraRegistry.sol";

interface IERC20 {
    function decimals() external returns(uint8);
}

contract MockSupraRegistry is ISupraRegistry {
    mapping(uint256 => TokenPair) private pairs;

    // Queue of verifyOracleProofV2() behaviors.
    struct CallConfig {
        bool shouldRevert;
        uint256 pairId;
        uint256 price;
        uint256 timestamp;
        uint256 decimal;
        uint256 round;
    }

    CallConfig[] internal callConfigs;
    uint256 internal callConfigHead;

    /// --- Mock setup functions ---

    function registerPair(uint256 pairId, address tokenA, address tokenB) external {
        require(tokenA != tokenB, "MockSupraRegistry: identical tokens");
        pairs[pairId] = TokenPair(
            tokenA, 
            tokenB, 
            tokenA == address(0) ? 8 : IERC20(tokenA).decimals(), 
            tokenB == address(0) ? 8 : IERC20(tokenB).decimals());
    }

    /// Configure a single next call to verifyOracleProofV2() to revert, clearing any queued behavior.
    function mockNextRevert() external {
        _resetQueue();
        _enqueueRevert();
    }

    /// Configure a single next call to verifyOracleProofV2() to return a single-entry PriceInfo,
    /// clearing any queued behavior.
    function mockNextPriceInfo(
        uint256 pairId,
        uint256 price,
        uint256 timestamp,
        uint256 decimal,
        uint256 round
    ) external {
        _resetQueue();
        _enqueuePriceInfo(pairId, price, timestamp, decimal, round);
    }

    /// Enqueue a revert for the next verifyOracleProofV2() call without clearing the queue.
    function enqueueRevert() external {
        _enqueueRevert();
    }

    /// Enqueue a single-entry PriceInfo response without clearing the queue.
    function enqueuePriceInfo(
        uint256 pairId,
        uint256 price,
        uint256 timestamp,
        uint256 decimal,
        uint256 round
    ) external {
        _enqueuePriceInfo(pairId, price, timestamp, decimal, round);
    }

    /// --- Public API ---

    function getPair(uint256 pairId) external view override returns (TokenPair memory) {
        TokenPair storage p = pairs[pairId];
        return p;
    }

    function verifyOracleProofV2(bytes calldata) external override returns (PriceInfo memory info) {
        require(callConfigHead < callConfigs.length, "MockSupraRegistry: behavior not configured");
        CallConfig memory cfg = callConfigs[callConfigHead++];
        if (callConfigHead == callConfigs.length) {
            _resetQueue();
        }

        if (cfg.shouldRevert) {
            revert("MockSupraRegistry: forced revert");
        }

        info.pairs = new uint256[](1);
        info.prices = new uint256[](1);
        info.timestamp = new uint256[](1);
        info.decimal = new uint256[](1);
        info.round = new uint256[](1);

        info.pairs[0] = cfg.pairId;
        info.prices[0] = cfg.price;
        info.timestamp[0] = cfg.timestamp;
        info.decimal[0] = cfg.decimal;
        info.round[0] = cfg.round;

        return info;
    }

    function _resetQueue() internal {
        delete callConfigs;
        callConfigHead = 0;
    }

    function _enqueueRevert() internal {
        callConfigs.push(CallConfig({
            shouldRevert: true,
            pairId: 0,
            price: 0,
            timestamp: 0,
            decimal: 0,
            round: 0
        }));
    }

    function _enqueuePriceInfo(
        uint256 pairId,
        uint256 price,
        uint256 timestamp,
        uint256 decimal,
        uint256 round
    ) internal {
        callConfigs.push(CallConfig({
            shouldRevert: false,
            pairId: pairId,
            price: price,
            timestamp: timestamp,
            decimal: decimal,
            round: round
        }));
    }
}
