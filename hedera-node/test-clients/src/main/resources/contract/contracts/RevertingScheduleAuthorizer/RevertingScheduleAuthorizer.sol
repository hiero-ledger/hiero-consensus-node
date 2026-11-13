// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;

import "./HederaScheduleService.sol";
import "./HederaResponseCodes.sol";
pragma experimental ABIEncoderV2;

/// Authorizes a schedule through the schedule system contract, and then always reverts.
contract RevertingScheduleAuthorizer is HederaScheduleService {
    function authorizeScheduleAndRevert(address schedule) external {
        int64 responseCode = HederaScheduleService.authorizeSchedule(schedule);
        require(responseCode == HederaResponseCodes.SUCCESS, "Authorize schedule failed");
        revert("Reverted after authorizing the schedule");
    }
}
