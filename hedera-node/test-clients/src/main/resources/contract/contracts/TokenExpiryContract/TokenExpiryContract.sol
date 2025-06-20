// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./HederaTokenService.sol";

contract TokenExpiryContract is HederaTokenService {

    function getExpiryInfoForToken(address token) external returns (
        IHederaTokenService.Expiry memory expiry) {
        (int responseCode,
        IHederaTokenService.Expiry memory retrievedExpiry) = HederaTokenService.getTokenExpiryInfo(token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ();
        }

        expiry = retrievedExpiry;
    }

    function updateExpiryInfoForToken(
        address token,
        uint32 second,
        address autoRenewAccount,
        uint32 autoRenewPeriod) public {
        IHederaTokenService.Expiry memory expiry;
        expiry.second = second;
        expiry.autoRenewAccount = autoRenewAccount;
        expiry.autoRenewPeriod = autoRenewPeriod;

        int response = HederaTokenService.updateTokenExpiryInfo(token, expiry);

        if (response != HederaResponseCodes.SUCCESS) {
            revert ();
        }
    }

    function updateExpiryInfoForTokenAndReadLatestInfo(
        address token,
        uint32 second,
        address autoRenewAccount,
        uint32 autoRenewPeriod) external returns (
        IHederaTokenService.Expiry memory expiry) {
        updateExpiryInfoForToken(token, second, autoRenewAccount, autoRenewPeriod);

        (int responseCode,
        IHederaTokenService.Expiry memory retrievedExpiry) = HederaTokenService.getTokenExpiryInfo(token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ();
        }

        expiry = retrievedExpiry;
    }
}
