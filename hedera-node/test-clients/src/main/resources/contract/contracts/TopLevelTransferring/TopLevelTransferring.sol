// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

import "./SubLevelTransferringContract.sol";

contract TopLevelTransferring {

    // @notice Should be able to send tinybars as initialValue
    constructor() payable {}

    // @notice Should return the tinybars transferred
    function topLevelTransferCall() public payable returns (uint256) {
        return msg.value;
    }

    // @notice Should always fail
    function topLevelNonPayableCall() public pure returns (bool) {
        return false;
    }

    // @notice Should be able to transfer tinybars as part of method call. Tinybars must be send to this contract using the receive fallback
    function subLevelPayableCall(address _contract, uint256 _amount) public {
        SubLevel(_contract).receiveTinybars{value: _amount}();
    }

    function subLevelNonPayableCall(address _contract, uint256 _amount) public {
        SubLevel(_contract).receiveTinybars{value: _amount}();
    }

    // @notice Should be able to send tinybars without any method being called. Query the balance of the contract after executing to verify this.
    receive() external payable { }
}

