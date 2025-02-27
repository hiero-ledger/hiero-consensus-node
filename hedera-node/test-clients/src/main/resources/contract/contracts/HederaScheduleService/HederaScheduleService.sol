// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./HederaResponseCodes.sol";
import "./IHederaScheduleService.sol";

abstract contract HederaScheduleService {
    address constant scheduleSystemContractAddress = address(0x16b);

    /// Authorizes the calling contract as a signer to the schedule transaction.
    /// @param schedule the address of the schedule transaction.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    function authorizeSchedule(address schedule) internal returns (int64 responseCode) {
        (bool success, bytes memory result) = scheduleSystemContractAddress.call(
            abi.encodeWithSelector(IHederaScheduleService.authorizeSchedule.selector, schedule));
        responseCode = success ? abi.decode(result, (int64)) : HederaResponseCodes.UNKNOWN;
    }

    /// Allows for the signing of a schedule transaction given a protobuf encoded signature map
    /// The message signed by the keys is defined to be the concatenation of the shard, realm, and schedule transaction ID.
    /// @param schedule the address of the schedule transaction.
    /// @param signatureMap the protobuf encoded signature map
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    function signSchedule(address schedule, bytes memory signatureMap) internal returns (int64 responseCode) {
        (bool success, bytes memory result) = scheduleSystemContractAddress.call(
            abi.encodeWithSelector(IHederaScheduleService.signSchedule.selector, schedule, signatureMap));
        responseCode = success ? abi.decode(result, (int64)) : HederaResponseCodes.UNKNOWN;
    }

    /// Allows for the creation of a schedule transaction for given a system contract address, abi encoded call data and payer address
    /// Currently supports the Hedera Token Service System Contract (0x167) with encoded call data for
    /// createFungibleToken, createNonFungibleToken, createFungibleTokenWithCustomFees, createNonFungibleTokenWithCustomFees
    /// and updateToken functions
    /// @param systemContractAddress the address of the system contract from which to create the schedule transaction
    /// @param callData the abi encoded call data for the system contract function
    /// @param payer the address of the account that will pay for the schedule transaction
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    /// @return scheduleAddress The address of the newly created schedule transaction.
    function scheduleNative(address systemContractAddress, bytes memory callData, address payer) internal returns (int64 responseCode, address scheduleAddress) {
        (bool success, bytes memory result) = scheduleSystemContractAddress.call(
            abi.encodeWithSelector(IHederaScheduleService.scheduleNative.selector, systemContractAddress, callData, payer));
        (responseCode, scheduleAddress) = success ? abi.decode(result, (int64, address)) : (int64(HederaResponseCodes.UNKNOWN), address(0));
    }

    /// Returns the token information for a scheduled fungible token create transaction
    /// @param scheduleAddress the address of the schedule transaction
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    /// @return fungibleTokenInfo The token information for the scheduled fungible token create transaction
    function getScheduledCreateFungibleTokenInfo(address scheduleAddress) internal returns (int64 responseCode, IHederaTokenService.FungibleTokenInfo memory fungibleTokenInfo) {
        (bool success, bytes memory result) = scheduleSystemContractAddress.call(
            abi.encodeWithSelector(IHederaScheduleService.getScheduledCreateFungibleTokenInfo.selector, scheduleAddress));
        IHederaTokenService.FungibleTokenInfo memory defaultTokenInfo;
        (responseCode, fungibleTokenInfo) = success ? abi.decode(result, (int64, IHederaTokenService.FungibleTokenInfo)) : (int64(HederaResponseCodes.UNKNOWN), defaultTokenInfo);
    }

    /// Returns the token information for a scheduled non fungible token create transaction
    /// @param scheduleAddress the address of the schedule transaction
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    /// @return nonFungibleTokenInfo The token information for the scheduled non fungible token create transaction
    function getScheduledCreateNonFungibleTokenInfo(address scheduleAddress) internal returns (int64 responseCode, IHederaTokenService.NonFungibleTokenInfo memory nonFungibleTokenInfo) {
        (bool success, bytes memory result) = scheduleSystemContractAddress.call(
            abi.encodeWithSelector(IHederaScheduleService.getScheduledCreateNonFungibleTokenInfo.selector, scheduleAddress));
        IHederaTokenService.NonFungibleTokenInfo memory defaultTokenInfo;
        (responseCode, nonFungibleTokenInfo) = success ? abi.decode(result, (int64, IHederaTokenService.NonFungibleTokenInfo)) : (int64(HederaResponseCodes.UNKNOWN), defaultTokenInfo);
    }
}
