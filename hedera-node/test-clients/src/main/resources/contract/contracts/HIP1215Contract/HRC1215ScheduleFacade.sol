// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;

import "./HederaResponseCodes.sol";
import "../IHRC1215ScheduleFacade/IHRC1215ScheduleFacade.sol";

abstract contract HRC1215ScheduleFacade {

    /// Delete the targeted schedule transaction.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    function deleteSchedule(address scheduleAddress) internal returns (int64 responseCode) {
        (bool success, bytes memory result) = scheduleAddress.call(
            abi.encodeWithSelector(IHRC1215ScheduleFacade.deleteSchedule.selector));
        responseCode = success ? abi.decode(result, (int64)) : HederaResponseCodes.UNKNOWN;
    }

}