// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

contract Whitelister {
    mapping(address => bool) public whitelist;

    function addToWhitelist(address _toBePermitted) public {
        whitelist[_toBePermitted] = true;
    }

    function isSenderWhitelisted() public returns(bool) {
        return whitelist[address(msg.sender)];
    }

    function isWhitelisted(address _toBeChecked) public returns(bool) {
        return whitelist[_toBeChecked];
    }
}
