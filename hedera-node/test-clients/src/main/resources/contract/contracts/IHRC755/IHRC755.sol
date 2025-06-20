// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

interface IHRC755 {
    // Sign the addressed schedule transaction with the keys of the calling EOA.
    function signSchedule() external returns (int64 responseCode);
}
