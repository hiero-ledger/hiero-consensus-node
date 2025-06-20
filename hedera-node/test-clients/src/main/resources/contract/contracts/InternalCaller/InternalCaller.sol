// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.0;

contract InternalCaller {
    constructor() payable {}

    function callNonExisting(address _addr) external {
        _addr.call(abi.encodeWithSignature("nonExisting()"));
    }

    function staticCallNonExisting(address _addr) external {
        _addr.staticcall(abi.encodeWithSignature("nonExisting()"));
    }

    function staticCallExternalFunction(address _addr) external returns (uint) {
        (bool success, bytes memory result) = _addr.staticcall(abi.encodeWithSignature("externalFunction()"));
        return success && result.length > 0 ? abi.decode(result, (uint)) : 0;
    }

    function delegateCallExternalFunction(address _addr) external returns (uint) {
        (bool success, bytes memory result) = _addr.delegatecall(abi.encodeWithSignature("externalFunction()"));
        return success && result.length > 0 ? abi.decode(result, (uint)) : 0;
    }

    function callExternalFunction(address _addr) external returns (uint) {
        (bool success, bytes memory result) = _addr.call(abi.encodeWithSignature("externalFunction()"));
        return success && result.length > 0 ? abi.decode(result, (uint)) : 0;
    }

    function callRevertWithRevertReason(address _addr) external {
        _addr.call(abi.encodeWithSignature("revertWithRevertReason()"));
    }

    function callRevertWithoutRevertReason(address _addr) external {
        _addr.call(abi.encodeWithSignature("revertWithoutRevertReason()"));
    }

    function sendTo(address payable _addr) external {
        _addr.send(1);
    }

    function transferTo(address payable _addr) external {
        _addr.transfer(1);
    }

    function callWithValueTo(address _addr) external {
        _addr.call{value: 1}("");
    }

    function selfdestruct(address payable _addr) external {
        selfdestruct(_addr);
    }
}
