// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.12;

contract MakeCalls {
    function makeCallWithAmount(address _to, bytes memory _data) external payable returns (bool success, bytes memory returnData) {
        (success, returnData) = _to.call{value: msg.value}(_data);
    }

    function makeCallWithoutAmount(address _to, bytes memory _data) external returns (bool success, bytes memory returnData) {
        (success, returnData) = _to.call(_data);
    }
}
