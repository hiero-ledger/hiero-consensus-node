// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

interface IHRC755 {
    /// Signs the targeted schedule transaction with the key of the calling EOA.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    function signSchedule() external returns (int64 responseCode);
}
