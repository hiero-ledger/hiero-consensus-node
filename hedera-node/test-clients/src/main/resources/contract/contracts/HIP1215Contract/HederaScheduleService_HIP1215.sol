// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;

import "./HederaResponseCodes.sol";
import "./IHederaScheduleService_HIP1215.sol";

abstract contract HederaScheduleService_HIP1215 {

    address internal constant HSS = address(0x16b);

    /// Allows for the creation of a schedule transaction to schedule any contract call for a given smart contract
    /// address, expiration time, the gas limit for the future call, the value to send with that call
    /// and the call data to use.
    /// @param to the address of the smart contract for the future call
    /// @param expirySecond an expiration time of the future call
    /// @param gasLimit a maximum limit to the amount of gas to use for future call
    /// @param value an amount of tinybar sent via this future contract call
    /// @param callData the smart contract function to call. This MUST contain The application binary interface (ABI)
    /// encoding of the function call per the Ethereum contract ABI standard, giving the function signature and
    /// arguments being passed to the function.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    /// @return scheduleAddress The address of the newly created schedule transaction.
    function scheduleCall(address to, uint256 expirySecond, uint256 gasLimit, uint64 value, bytes memory callData)
    internal returns (int64 responseCode, address scheduleAddress) {
        (bool success, bytes memory result) = HSS.call(
            abi.encodeWithSelector(IHederaScheduleService_HIP1215.scheduleCall.selector, to, expirySecond, gasLimit, value, callData));
        (responseCode, scheduleAddress) = success ? abi.decode(result, (int64, address)) : (int64(HederaResponseCodes.UNKNOWN), address(0));
    }

    /// Allows for the creation of a schedule transaction to schedule any contract call for a given smart contract
    /// address, with a sender for the scheduled transaction, expiration time, the gas limit for the future call,
    /// the value to send with that call and the call data to use.
    /// Waits until the consensus second is not before `expirySecond` to execute.
    /// @param to the address of the smart contract for the future call
    /// @param sender an account identifier of a `payer` for the scheduled transaction
    /// @param expirySecond an expiration time of the future call
    /// @param gasLimit a maximum limit to the amount of gas to use for future call
    /// @param value an amount of tinybar sent via this future contract call
    /// @param callData the smart contract function to call. This MUST contain The application binary interface (ABI)
    /// encoding of the function call per the Ethereum contract ABI standard, giving the function signature and
    /// arguments being passed to the function.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    /// @return scheduleAddress The address of the newly created schedule transaction.
    function scheduleCallWithSender(address to, address sender, uint256 expirySecond, uint256 gasLimit, uint64 value, bytes memory callData)
    internal returns (int64 responseCode, address scheduleAddress) {
        (bool success, bytes memory result) = HSS.call(
            abi.encodeWithSelector(IHederaScheduleService_HIP1215.scheduleCallWithSender.selector, to, sender, expirySecond, gasLimit, value, callData));
        (responseCode, scheduleAddress) = success ? abi.decode(result, (int64, address)) : (int64(HederaResponseCodes.UNKNOWN), address(0));
    }

    /// Allows for the creation of a schedule transaction to schedule any contract call for a given smart contract
    /// address, with a sender for the scheduled transaction, expiration time, the gas limit for the future call,
    /// the value to send with that call and the call data to use.
    /// Executes as soon as the payer signs (unless consensus time is already past the `expirySecond`, of course).
    /// @param to the address of the smart contract for the future call
    /// @param sender an account identifier of a `payer` for the scheduled transaction
    /// @param expirySecond an expiration time of the future call
    /// @param gasLimit a maximum limit to the amount of gas to use for future call
    /// @param value an amount of tinybar sent via this future contract call
    /// @param callData the smart contract function to call. This MUST contain The application binary interface (ABI)
    /// encoding of the function call per the Ethereum contract ABI standard, giving the function signature and
    /// arguments being passed to the function.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    /// @return scheduleAddress The address of the newly created schedule transaction.
    function executeCallOnSenderSignature(address to, address sender, uint256 expirySecond, uint256 gasLimit, uint64 value, bytes memory callData)
    internal returns (int64 responseCode, address scheduleAddress) {
        (bool success, bytes memory result) = HSS.call(
            abi.encodeWithSelector(IHederaScheduleService_HIP1215.executeCallOnSenderSignature.selector, to, sender, expirySecond, gasLimit, value, callData));
        (responseCode, scheduleAddress) = success ? abi.decode(result, (int64, address)) : (int64(HederaResponseCodes.UNKNOWN), address(0));
    }

    /// Delete the targeted schedule transaction.
    /// @param scheduleAddress the address of the schedule transaction.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    function deleteSchedule(address scheduleAddress) internal returns (int64 responseCode) {
        (bool success, bytes memory result) = HSS.call(
            abi.encodeWithSelector(IHederaScheduleService_HIP1215.deleteSchedule.selector, scheduleAddress));
        responseCode = success ? abi.decode(result, (int64)) : HederaResponseCodes.UNKNOWN;
    }

    /// Allows to check if the given second still has capacity to schedule a contract call with the specified gas limit.
    /// @param expirySecond an expiration time of the future call
    /// @param gasLimit a maximum limit to the amount of gas to use for future call
    /// @return hasCapacity returns `true` iff the given second still has capacity to schedule a contract call
    /// with the specified gas limit.
    function hasScheduleCapacity(uint256 expirySecond, uint256 gasLimit) view internal returns (bool hasCapacity) {
        (bool success, bytes memory result) = HSS.staticcall(
            abi.encodeWithSelector(IHederaScheduleService_HIP1215.hasScheduleCapacity.selector, expirySecond, gasLimit));
        hasCapacity = success ? abi.decode(result, (bool)) : false;
    }

}
