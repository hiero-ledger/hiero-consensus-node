// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.6.0 <0.9.0;
import "./HederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract TokenAndTypeCheck is HederaTokenService {

    function isAToken(address token) external returns(bool) {
        (int statusCode, bool isToken) = HederaTokenService.isToken(token);

        if (statusCode != HederaResponseCodes.SUCCESS) {
            revert ("Token check failed!");
        }
        return isToken;
    }

    function getType(address token) external returns(int) {
        (int statusCode, int tokenType) = HederaTokenService.getTokenType(token);

        if (statusCode != HederaResponseCodes.SUCCESS) {
            revert ("Token type appraisal failed!");
        }
        return tokenType;
    }
}