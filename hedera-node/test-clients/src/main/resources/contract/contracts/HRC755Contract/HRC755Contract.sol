// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;

import "./HederaScheduleService.sol";
import "./HederaResponseCodes.sol";
pragma experimental ABIEncoderV2;

contract HRC755Contract is HederaScheduleService {
    function authorizeScheduleCall(address schedule) external returns (int64 responseCode)
    {
        (responseCode) = HederaScheduleService.authorizeSchedule(schedule);
        require(responseCode == HederaResponseCodes.SUCCESS, "Authorize schedule failed");
    }

    function signScheduleCall(address schedule, bytes memory signatureMap) external returns (int64 responseCode) {
        (responseCode) = HederaScheduleService.signSchedule(schedule, signatureMap);
        require(responseCode == HederaResponseCodes.SUCCESS, "Authorize schedule failed");
    }
}
