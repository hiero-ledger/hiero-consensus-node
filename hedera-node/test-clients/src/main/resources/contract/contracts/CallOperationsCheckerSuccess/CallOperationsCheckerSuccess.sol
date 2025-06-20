// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.4.21;

contract CallOperationsCheckerSuccess {

    function call(address _address) payable {
        (bool success,) = _address.call.value(msg.value)(bytes4(keccak256("getVersion()")));
        if (!success) {
            revert();
        }
    }

    function callCode(address _address) payable {
        (bool success,) = _address.callcode.value(msg.value)(bytes4(keccak256("getVersion()")));
        if (!success) {
            revert();
        }
    }

    function delegateCall(address _address) payable {
        (bool success,) = _address.delegatecall(bytes4(keccak256("getVersion()")));
        if (!success) {
            revert();
        }
    }

    function staticcall(address _address) payable {
        bool callSuccess;
        bytes memory calldata = abi.encodeWithSelector(bytes4(keccak256("getVersion()")));
        assembly {
            callSuccess := staticcall(gas, _address, add(calldata, 0x20), mload(calldata), calldata, 0x20)
        }
        if (!callSuccess) {
            revert();
        }
    }
}
