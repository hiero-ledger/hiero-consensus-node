// SPDX-License-Identifier: MIT
pragma solidity ^0.8.9;


contract UnknownFunctionSelectorContract {
    function callTokenServiceWithFakeSelector(address targetContract) public returns (bytes memory result) {
        bytes4 fakeSelector = bytes4(keccak256("unknownSelector"));
        bytes memory callData = abi.encodePacked(fakeSelector, abi.encode(targetContract));
        address tokenService = address(0x167);

        (bool success, bytes memory data) = tokenService.call(callData);
        require(success, "call failed");

        result = data;
    }

    function callAccountServiceWithFakeSelector(address targetContract) public returns (bytes memory result) {
        bytes4 fakeSelector = bytes4(keccak256("unknownSelector"));
        bytes memory callData = abi.encodePacked(fakeSelector, abi.encode(targetContract));
        address accountService = address(0x16a);

        (bool success, bytes memory data) = accountService.call(callData);
        require(success, "call failed");

        result = data;
    }


    function callScheduleServiceWithFakeSelector(address targetContract) public returns (bytes memory result) {
        bytes4 fakeSelector = bytes4(keccak256("unknownSelector"));
        bytes memory callData = abi.encodePacked(fakeSelector, abi.encode(targetContract));
        address scheduleService = address(0x16b);

        (bool success, bytes memory data) = scheduleService.call(callData);
        require(success, "call failed");

        result = data;
    }
}
