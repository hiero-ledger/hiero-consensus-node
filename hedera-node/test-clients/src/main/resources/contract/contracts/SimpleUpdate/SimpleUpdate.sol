// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.0;

contract SimpleUpdate {
    uint public pos0;
    uint public pos1;

    function set(uint n, uint m) public {
        pos0 = n;
        pos1 = m;
    }

    function del(address payable beneficiary ) public {
        selfdestruct(beneficiary);
    }
}
