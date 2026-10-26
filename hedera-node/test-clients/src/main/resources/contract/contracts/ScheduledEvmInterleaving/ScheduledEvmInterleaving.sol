// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.10;

interface ScheduleAuthorization {
    function authorizeSchedule(address schedule) external returns (int64 responseCode);
}

// Empty storage keeps the creation regression independent of the overlapping storage-slot assertions.
contract InterleavingCreatedContract {}

contract InterleavingCreationFactory {
    function create() external {
        new InterleavingCreatedContract();
    }
}

/** Exercises an outer EVM execution that resumes after one or more scheduled EVM executions. */
contract ScheduledEvmInterleaving {
    uint256 public value;

    constructor(uint256 initialValue) {
        value = initialValue;
    }

    // An absent slot stays outside the storage linked list, including during slot 0 deletion/reinsertion.
    function readOnlySlot() public view returns (uint256 result) {
        assembly { result := sload(1) }
    }

    function set(uint256 newValue) external {
        setValue(newValue);
    }

    function setWithCreation(uint256 newValue) external {
        // This factory was created by the outer execution. Its nonce changes here, but only its
        // newly created child belongs in this scheduled execution's created-contract list.
        address factory = address(uint160(uint256(keccak256(abi.encodePacked(
            bytes1(0xff), address(this), bytes32(0), keccak256(type(InterleavingCreationFactory).creationCode)
        )))));
        InterleavingCreationFactory(factory).create();
        setValue(newValue);
    }

    function setValue(uint256 newValue) private {
        require(readOnlySlot() == 0, "read-only storage changed");
        value = newValue;
    }

    function run(
        address[] calldata schedules,
        uint256 beforeValue,
        uint256[] calldata scheduledValues,
        bool writeAfter,
        uint256 afterValue
    ) external {
        runScheduled(schedules, beforeValue, scheduledValues, writeAfter, afterValue);
    }

    function runWithCreations(
        address[] calldata schedules,
        uint256 beforeValue,
        uint256[] calldata scheduledValues,
        bool writeAfter,
        uint256 afterValue
    ) external {
        new InterleavingCreationFactory{salt: bytes32(0)}();
        runScheduled(schedules, beforeValue, scheduledValues, writeAfter, afterValue);
        new InterleavingCreatedContract();
    }

    function runScheduled(
        address[] calldata schedules,
        uint256 beforeValue,
        uint256[] calldata scheduledValues,
        bool writeAfter,
        uint256 afterValue
    ) private {
        require(schedules.length == scheduledValues.length, "length mismatch");
        require(readOnlySlot() == 0, "read-only storage changed");
        value = beforeValue;
        for (uint256 i = 0; i < schedules.length; ++i) {
            int64 responseCode = ScheduleAuthorization(address(0x16b)).authorizeSchedule(schedules[i]);
            require(responseCode == 22, "authorize schedule failed");
            // This also establishes that each scheduled call executes before the outer call resumes.
            require(value == scheduledValues[i], "scheduled call did not execute synchronously");
        }
        if (writeAfter) {
            value = afterValue;
        }
    }
}
