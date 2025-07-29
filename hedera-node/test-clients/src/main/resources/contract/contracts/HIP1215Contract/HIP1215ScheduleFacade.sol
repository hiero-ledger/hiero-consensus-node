// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;

import "./HederaResponseCodes.sol";
import "../IHIP1215ScheduleFacade/IHIP1215ScheduleFacade.sol";

abstract contract HIP1215ScheduleFacade {

    /// Delete the targeted schedule transaction.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    function deleteSchedule(address scheduleAddress) internal returns (int64 responseCode) {
        (bool success, bytes memory result) = scheduleAddress.call(
            abi.encodeWithSelector(IHIP1215ScheduleFacade.deleteSchedule.selector));
        responseCode = success ? abi.decode(result, (int64)) : HederaResponseCodes.UNKNOWN;
    }

}