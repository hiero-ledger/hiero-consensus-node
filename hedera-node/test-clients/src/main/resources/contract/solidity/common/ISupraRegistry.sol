// SPDX-License-Identifier: MIT
pragma solidity >=0.8.4;

interface ISupraRegistry {
    struct PriceInfo {
        uint256[] pairs;
        uint256[] prices;
        uint256[] timestamp;
        uint256[] decimal;
        uint256[] round;
    }
    
    struct TokenPair {
        address tokenA;
        address tokenB;
        uint8 decimalsA;
        uint8 decimalsB;
    }
    
    function verifyOracleProofV2(bytes calldata _bytesproof) external returns (PriceInfo memory);

    function getPair(uint256 pairId) external returns (TokenPair memory);
}
