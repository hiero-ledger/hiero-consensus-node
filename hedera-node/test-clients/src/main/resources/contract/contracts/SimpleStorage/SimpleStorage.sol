// SPDX-License-Identifier: Apache-2.0
pragma solidity 0.5.11;
contract SimpleStorage {
    uint storedData = 15;

    function set(uint x) public {
        storedData = x;
    }

    function get() public view returns (uint _get) {
        return storedData;
    }
}
