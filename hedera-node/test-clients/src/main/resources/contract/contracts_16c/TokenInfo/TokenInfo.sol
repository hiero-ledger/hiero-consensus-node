// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./HederaResponseCodes.sol";
import "./HederaTokenService.sol";

contract TokenInfo is HederaTokenService {

    function getInformationForToken(address token) external returns (IHederaTokenService.TokenInfo memory tokenInfo) {
        (int responseCode, IHederaTokenService.TokenInfo memory retrievedTokenInfo) = HederaTokenService.getTokenInfo(token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ();
        }

        tokenInfo = retrievedTokenInfo;
    }
}