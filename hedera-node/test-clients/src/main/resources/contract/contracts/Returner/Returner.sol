// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.9;

contract Returner {
    Placeholder placeholder;

    function returnThis() public view returns (uint160) {
        return uint160(address(this));
    }

    function createPlaceholder() public {
        placeholder = new Placeholder();
    }
}

contract Placeholder {
}
