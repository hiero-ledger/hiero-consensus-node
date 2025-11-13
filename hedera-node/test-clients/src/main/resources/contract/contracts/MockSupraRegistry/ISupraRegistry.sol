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
    }
    
    function verifyOracleProofV2(bytes calldata proof) external returns (PriceInfo memory info);

    function getPair(uint256 pairId) external view returns(address tokenA, address tokenB);
}
