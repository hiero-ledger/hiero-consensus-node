// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.10;

import "./HederaScheduleService_HIP1215.sol";
import "./HRC1215ScheduleFacade.sol";

contract HIP1215Contract is HederaScheduleService_HIP1215 {

    uint256 internal constant SCHEDULE_GAS_LIMIT = 2_000_000;
    uint256 internal constant HAS_SCHEDULE_CAPACITY_GAS_LIMIT = 10_000;

    function scheduleCallExample(uint256 expiryShift)
    external returns (int64 responseCode, address scheduleAddress) {
        uint256 expirySecond = block.timestamp + expiryShift;
        // callData bytes for calling 'hasScheduleCapacity' on 'expirySecond' + 10 minutes time
        bytes memory hasScheduleCapacityBytes = abi.encodeWithSelector(IHederaScheduleService_HIP1215.hasScheduleCapacity.selector, expirySecond + 600, HAS_SCHEDULE_CAPACITY_GAS_LIMIT);
        // schedule call
        (responseCode, scheduleAddress) = scheduleCall(HSS, expirySecond, SCHEDULE_GAS_LIMIT, 0, hasScheduleCapacityBytes);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert("Failed to schedule");
        }
    }

    function scheduleCallWithPayerExample(address payer, uint256 expiryShift)
    external returns (int64 responseCode, address scheduleAddress) {
        uint256 expirySecond = block.timestamp + expiryShift;
        // callData bytes for calling 'hasScheduleCapacity' on 'expirySecond' + 10 minutes time
        bytes memory hasScheduleCapacityBytes = abi.encodeWithSelector(IHederaScheduleService_HIP1215.hasScheduleCapacity.selector, expirySecond + 600, HAS_SCHEDULE_CAPACITY_GAS_LIMIT);
        // schedule call
        (responseCode, scheduleAddress) = scheduleCallWithPayer(HSS, payer, expirySecond, SCHEDULE_GAS_LIMIT, 0, hasScheduleCapacityBytes);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert("Failed to schedule");
        }
    }

    function executeCallOnPayerSignatureExample(address payer, uint256 expiryShift)
    external returns (int64 responseCode, address scheduleAddress) {
        uint256 expirySecond = block.timestamp + expiryShift;
        // callData bytes for calling 'hasScheduleCapacity' on 'expirySecond' + 10 minutes time
        bytes memory hasScheduleCapacityBytes = abi.encodeWithSelector(IHederaScheduleService_HIP1215.hasScheduleCapacity.selector, expirySecond + 600, HAS_SCHEDULE_CAPACITY_GAS_LIMIT);
        // schedule call
        (responseCode, scheduleAddress) = executeCallOnPayerSignature(HSS, payer, expirySecond, SCHEDULE_GAS_LIMIT, 0, hasScheduleCapacityBytes);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert("Failed to schedule");
        }
    }

    function deleteScheduleExample(address scheduleAddress) external returns (int64 responseCode) {
        (responseCode) = deleteSchedule(scheduleAddress);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert("Failed to delete schedule");
        }
    }

    function deleteScheduleProxyExample(address scheduleAddress) external returns (int64 responseCode) {
        (responseCode) = IHRC1215ScheduleFacade(scheduleAddress).deleteSchedule();
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert("Failed to delete schedule");
        }
    }

    function hasScheduleCapacityExample(uint256 expiryShift) view external returns (bool hasCapacity) {
        uint256 expirySecond = block.timestamp + expiryShift;
        return hasScheduleCapacity(expirySecond, SCHEDULE_GAS_LIMIT);
    }

    function scheduleCallWithCapacityCheckAndDeleteExample(uint256 expiryShift)
    external returns (int64 responseCode, address scheduleAddress) {
        uint256 expirySecond = block.timestamp + expiryShift;
        bool hasCapacity = hasScheduleCapacity(expirySecond, SCHEDULE_GAS_LIMIT);
        if (hasCapacity) {
            // callData bytes for calling 'hasScheduleCapacity' on 'expirySecond' + 10 minutes time
            bytes memory hasScheduleCapacityBytes = abi.encodeWithSelector(IHederaScheduleService_HIP1215.hasScheduleCapacity.selector, expirySecond + 600, HAS_SCHEDULE_CAPACITY_GAS_LIMIT);
            // schedule call
            (responseCode, scheduleAddress) = scheduleCall(HSS, expirySecond, SCHEDULE_GAS_LIMIT, 0, hasScheduleCapacityBytes);
            if (responseCode != HederaResponseCodes.SUCCESS) {
                revert("Failed to schedule");
            } else {
                // delete the scheduled transaction after success schedule
                (responseCode) = deleteSchedule(scheduleAddress);
                if (responseCode != HederaResponseCodes.SUCCESS) {
                    revert("Failed to delete schedule");
                }
            }
        } else {
            revert("Failed to schedule. Has no capacity");
        }
    }

    function scheduleCallWithDefaultCallData(uint256 expirySecond, uint256 gasLimit)
    external payable returns (int64 responseCode, address scheduleAddress) {
        // callData bytes for calling 'hasScheduleCapacity' on 'expirySecond' + 10 minutes time
        bytes memory hasScheduleCapacityBytes = abi.encodeWithSelector(IHederaScheduleService_HIP1215.hasScheduleCapacity.selector, expirySecond + 600, HAS_SCHEDULE_CAPACITY_GAS_LIMIT);
        (responseCode, scheduleAddress) = scheduleCall(HSS, expirySecond, gasLimit, 0, hasScheduleCapacityBytes);
    }

    function hasScheduleCapacityProxy(uint256 expirySecond, uint256 gasLimit) external returns (bool hasCapacity) {
        return hasScheduleCapacity(expirySecond, gasLimit);
    }
}
