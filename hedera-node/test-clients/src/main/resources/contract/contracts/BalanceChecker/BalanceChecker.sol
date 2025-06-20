// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.4.0;

contract BalanceChecker  {

    function balanceOf(address _address) public view returns (uint256) {
        return _address.balance;
    }
}
