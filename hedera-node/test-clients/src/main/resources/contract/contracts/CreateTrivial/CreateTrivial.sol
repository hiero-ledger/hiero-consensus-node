// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.5.0;

contract Trivial {
  uint storeThis = 8;
  function get7() public pure returns (uint seven) {
    return 7;
  }
}

contract CreateTrivial {
  Trivial myContract;

  function create() public {
    myContract = new Trivial();
  }

  function getIndirect() public view returns (uint value) {
    return myContract.get7();
  }

  function getAddress() public view returns (Trivial retval) {
    return myContract;
  }
}
