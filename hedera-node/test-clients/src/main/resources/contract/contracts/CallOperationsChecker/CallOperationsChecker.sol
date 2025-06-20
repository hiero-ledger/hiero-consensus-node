// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.4.0;

contract CallOperationsChecker {

    function call(address _address) payable {
        _address.call.value(msg.value)(bytes4(keccak256("storeValue(uint256)")));
    }

    function callCode(address _address) payable {
        _address.callcode.value(msg.value)(bytes4(keccak256("storeValue(uint256)")));
    }

    function delegateCall(address _address) payable {
        _address.delegatecall(bytes4(keccak256("storeValue(uint256)")));
    }

    function staticcall(address _address) payable {
        bool callSuccess;
        bytes memory calldata = abi.encodeWithSelector(bytes4(keccak256("storeValue(uint256)")));
        assembly {
            callSuccess := staticcall(gas, _address, add(calldata, 0x20), mload(calldata), calldata, 0x20)
        }
    }
}
