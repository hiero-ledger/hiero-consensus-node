// SPDX-License-Identifier: WTFPL
pragma solidity >=0.8.4;

library OrderType {
    uint256 public constant LIMIT              = 0;
    uint256 public constant MARKET             = 1;
    uint256 public constant STOP_LT            = 2; // oraclePrice < triggerPrice
    uint256 public constant STOP_MARKET_LT     = 3; 
    uint256 public constant STOP_GT            = 4; // oraclePrice > triggerPrice
    uint256 public constant STOP_MARKET_GT     = 5;

    function isMarketStyle(uint256 t) internal pure returns (bool) {
        return (t == MARKET || t == STOP_MARKET_LT || t == STOP_MARKET_GT);
    }

    function isLimitStyle(uint256 t) internal pure returns (bool) {
        return (t == LIMIT || t == STOP_LT || t == STOP_GT);
    }

    function isOracleStyle(uint256 t) internal pure returns (bool) {
        return !(t == LIMIT || t == MARKET);
    }

    function isLessThanStyle(uint256 t) internal pure returns (bool) {
        return (t == STOP_LT || t == STOP_MARKET_LT);
    }
}
