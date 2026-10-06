// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

contract TrivialLogged {
    uint storeThis = 8;

    function get7() public pure returns (uint seven) {
        return 7;
    }
}

contract CreateTrivialLogged {
    TrivialLogged myContract;

    event Created(address contractAddress);

    function create() public {
        myContract = new TrivialLogged();
        emit Created(address(myContract));
    }

    function getIndirect() public view returns (uint value) {
        return myContract.get7();
    }

    function getAddress() public view returns (TrivialLogged retval) {
        return myContract;
    }
}
