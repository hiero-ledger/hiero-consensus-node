// SPDX-License-Identifier: MIT
pragma solidity >=0.8.4;

import "./ISupraRegistry.sol";

contract MockSupraRegistry is ISupraRegistry {
    mapping(uint256 => TokenPair) public pairs;

    // How the next verifyOracleProofV2() call should behave
    struct NextCallConfig {
        bool isSet;
        bool shouldRevert;
        uint256 pairId;
        uint256 price;
        uint256 timestamp;
        uint256 decimal;
        uint256 round;
    }

    NextCallConfig internal nextCallConfig;

    /// --- Mock setup functions ---

    function registerPair(uint256 pairId, address tokenA, address tokenB) external {
        require(tokenA != tokenB, "MockSupraRegistry: identical tokens");
        pairs[pairId] = TokenPair(tokenA, tokenB);
    }

    /// Configure the next call to verifyOracleProofV2() to revert
    function mockNextRevert() external {
        nextCallConfig = NextCallConfig({
            isSet: true,
            shouldRevert: true,
            pairId: 0,
            price: 0,
            timestamp: 0,
            decimal: 0,
            round: 0
        });
    }

    /// Configure the next call to verifyOracleProofV2() to return a single-entry PriceInfo
    function mockNextPriceInfo(
        uint256 pairId,
        uint256 price,
        uint256 timestamp,
        uint256 decimal,
        uint256 round
    ) external {
        nextCallConfig = NextCallConfig({
            isSet: true,
            shouldRevert: false,
            pairId: pairId,
            price: price,
            timestamp: timestamp,
            decimal: decimal,
            round: round
        });
    }

    /// --- Public API ---

    function getPair(uint256 pairId) external view override returns (address tokenA, address tokenB) {
        TokenPair storage p = pairs[pairId];
        return (p.tokenA, p.tokenB);
    }

    function verifyOracleProofV2(bytes calldata) external override returns (PriceInfo memory info) {
        // Snapshot and consume config (one-shot behavior)
        NextCallConfig memory cfg = nextCallConfig;
        delete nextCallConfig;

        require(cfg.isSet, "MockSupraRegistry: behavior not configured");

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
}

