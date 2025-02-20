// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;

import "./HederaScheduleService.sol";
import "./HederaResponseCodes.sol";
pragma experimental ABIEncoderV2;

contract GetScheduleInfo is HederaScheduleService {

    function getFungibleCreateTokenInfo(address scheduleAddress) external returns (int64 responseCode, IHederaTokenService.FungibleTokenInfo memory fungibleTokenInfo) {
        (responseCode, fungibleTokenInfo) = getScheduledCreateFungibleTokenInfo(scheduleAddress);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
        return (responseCode, fungibleTokenInfo);
    }

    function getNonFungibleCreateTokenInfo(address scheduleAddress) external returns (int64 responseCode, IHederaTokenService.NonFungibleTokenInfo memory nonFungibleTokenInfo) {
        (responseCode, nonFungibleTokenInfo) = getScheduledCreateNonFungibleTokenInfo(scheduleAddress);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
        return (responseCode, nonFungibleTokenInfo);
    }
}
