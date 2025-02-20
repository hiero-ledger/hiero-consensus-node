// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.0;

contract InternalCallee {
    uint calledTimes = 0;

    function externalFunction() external returns (uint) {
        return ++calledTimes;
    }

    function revertWithRevertReason() public pure returns (bool) {
        revert("RevertReason");
    }

    function revertWithoutRevertReason() public pure returns (bool) {
        revert();
    }

    function selfdestruct(address payable _addr) external {
        selfdestruct(_addr);
    }
}
