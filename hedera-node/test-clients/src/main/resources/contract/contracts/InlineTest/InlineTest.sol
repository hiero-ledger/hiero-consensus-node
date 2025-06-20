// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.6.0;

contract InlineTest {
    bytes32 private store;

    function setStore(bytes32 inVal) external {
        store = inVal;
    }

    function getStore() external view returns (bytes32) {
        return store;
    }

    function getCodeSize(address _addr) view external returns (uint _size) {
        assembly {
            _size := extcodesize(_addr)
        }
    }
}
