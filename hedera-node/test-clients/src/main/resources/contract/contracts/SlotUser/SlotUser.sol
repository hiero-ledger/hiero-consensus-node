// SPDX-License-Identifier: Apache-2.0
contract SlotUser {
  uint public slotA;
  uint public slotB;
  Observation observation;

  constructor() {
    observation = new Observation();
  }

  function consumeA(uint slot, uint datum) external {
    observation.notice(datum);
    slotA = slot;
  }

  function consumeB(uint slot) external {
    slotB = slot;
  }

  function datum() external view returns (uint) {
    return observation.datum(); 
  }
}

contract Observation {
  uint public datum;

  function notice(uint _datum) external {
    datum = _datum;
  }
} 
