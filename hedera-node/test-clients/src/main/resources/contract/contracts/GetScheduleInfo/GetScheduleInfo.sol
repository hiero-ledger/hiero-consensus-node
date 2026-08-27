// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;

import "./HederaScheduleService.sol";
import "./HederaResponseCodes.sol";
import "./IHederaScheduleService.sol";
import "./IHederaTokenService.sol";
pragma experimental ABIEncoderV2;

contract GetScheduleInfo is HederaScheduleService {

    function getFungibleCreateTokenInfo(address scheduleAddress) external returns (int64 responseCode, IHederaTokenService.FungibleTokenInfo memory fungibleTokenInfo) {
        // Call the HSS system contract using a staticcall to exercise the static frame path
        (bool success, bytes memory result) = address(0x16b).staticcall(
            abi.encodeWithSelector(IHederaScheduleService.getScheduledCreateFungibleTokenInfo.selector, scheduleAddress)
        );
        IHederaTokenService.FungibleTokenInfo memory defaultTokenInfo;
        (responseCode, fungibleTokenInfo) = success
            ? abi.decode(result, (int64, IHederaTokenService.FungibleTokenInfo))
            : (int64(HederaResponseCodes.UNKNOWN), defaultTokenInfo);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
        return (responseCode, fungibleTokenInfo);
    }

    function getNonFungibleCreateTokenInfo(address scheduleAddress) external returns (int64 responseCode, IHederaTokenService.NonFungibleTokenInfo memory nonFungibleTokenInfo) {
        (bool success, bytes memory result) = address(0x16b).staticcall(
            abi.encodeWithSelector(IHederaScheduleService.getScheduledCreateNonFungibleTokenInfo.selector, scheduleAddress)
        );
        IHederaTokenService.NonFungibleTokenInfo memory defaultTokenInfo;
        (responseCode, nonFungibleTokenInfo) = success
            ? abi.decode(result, (int64, IHederaTokenService.NonFungibleTokenInfo))
            : (int64(HederaResponseCodes.UNKNOWN), defaultTokenInfo);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
        return (responseCode, nonFungibleTokenInfo);
    }
}
