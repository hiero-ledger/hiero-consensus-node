// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;

interface IHRC1215ScheduleFacade {

    /// Delete the targeted schedule transaction.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    function deleteSchedule() external returns (int64 responseCode);

}