// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./HederaResponseCodes.sol";
import "./HederaTokenService.sol";

contract TokenInfoContract is HederaTokenService {

    function getInformationForToken(address token) external returns (IHederaTokenService.TokenInfo memory tokenInfo) {
        (int responseCode, IHederaTokenService.TokenInfo memory retrievedTokenInfo) = HederaTokenService.getTokenInfo(token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ();
        }

        tokenInfo = retrievedTokenInfo;
    }

    function getInformationForFungibleToken(address token) external returns (IHederaTokenService.FungibleTokenInfo memory fungibleTokenInfo) {
        (int responseCode, IHederaTokenService.FungibleTokenInfo memory retrievedTokenInfo) = HederaTokenService.getFungibleTokenInfo(token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ();
        }

        fungibleTokenInfo = retrievedTokenInfo;
    }

    function getInformationForNonFungibleToken(address token, int64 serialNumber) external returns (IHederaTokenService.NonFungibleTokenInfo memory nonFungibleTokenInfo) {
        (int responseCode, IHederaTokenService.NonFungibleTokenInfo memory retrievedTokenInfo) = HederaTokenService.getNonFungibleTokenInfo(token, serialNumber);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ();
        }

        nonFungibleTokenInfo = retrievedTokenInfo;
    }
}