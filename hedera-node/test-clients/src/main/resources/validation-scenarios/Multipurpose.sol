// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.5.3;

contract Multipurpose {
  uint32 luckyNumber = 42;

  event Boast(string saying);

  function() external payable {}
  constructor() public payable {}  

  function believeIn(uint32 no) public {
    luckyNumber = no;
  }

  function pick() public view returns (uint32) {
    return luckyNumber;
  }

  function donate(uint120 toNum, string memory saying) public payable {
    address payable beneficiary = address(uint120(toNum));
    beneficiary.transfer(1);
    emit Boast(saying);
  }
}
