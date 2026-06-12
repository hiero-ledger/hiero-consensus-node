# Glossary
version 18, July 25, 2025

<a name="top"/>

[HAPI - Hiero API (Hedera API)](#hapi) - THESE SHOULD BE REVIEWED
- [Entity](#entity)
  - [Auto renewal](#auto-renewal)
- [Transaction](#transaction)
  - [Congestion Pricing](#congestion-pricing)
  - [Synthetic Transaction](#synthetic-transaction)
  - [System Transaction](#system-transaction)
  - [Admin Transaction](#admin-transaction)
  - [Child Transaction](#child-transaction)
- [Account](#account)
  - [Application Account](#application-account)
  - [Civilian Account](#civilian-account)
  - [Externally Owned Account (EOA)](#eoa)
  - [Hollow Accounts](#hollow-account)
  - [Privileged System Account](#privileged-system-account)
- [HTS - Token Service](#hts)
  - [Auto Association](#auto-association)
  - [Token Allowance](#token-allowance)
- [HSCS - Smart Contract Service)](#hscs)
  - [Alias](#alias)
  - [Delegate Call](#delegate-call)
  - [Precompiles](#precompiles)
  - [Security Model V2](#security-model-v2)
  - [System Contract](#system-contract)
  - [Triplet](#triplet)
  - [Wei and ETH](#Wei)
- [HFS - File Service](#hfs)
  - [Address Book](#address-book)
- [HSS - Scheduled Transaction Service](#hss)
- [HCS - Consensus Service](#hcs)

[Block Streams](#block-streams) - THESE SHOULD BE REVIEWED
- [Block Proof](#block-proof)
- [Block Signature](#block-signature)
- [Block Stream](#block-stream)
- [Sidecar](#sidecar)
- [BLS signature algorithm](#bls-signature-algorithm)

[Other](#other) - THESE SHOULD BE REVIEWED (and maybe moved to other categories)
- [Config.txt](#config-txt)
- [Leaky test](#leaky-test)

[Hashgraph consensus](#hashgraphConsensus)
- [Rounds](#rounds) 
  - round, pending, min judge birth, ancient, future, min judges, max consensus, min non-ancient, min non-expired, max roster, future, roster, round timestamp
- [Fields of an event](#fields-of-an-event)  
  - [signed](#eventFieldSigned)
    - transactions, birthRound, createdTimestamp, selfParent, selfParentBirthRound, otherParent, otherParentBirthRound
  - [unsigned streamed](#eventFieldUnsignedStreamed)
    - consensusRound, consensusTimestamp, consensusOrder
  - [unsigned non-streamed](#eventFieldUnsignedNonstreamed)
    - non-deterministic generation (NGen), consensus generation (CGen)
  - [mutable](#eventFieldMutable)
    - deterministic generation (DGen), votingRound
- [Events](#events)
  - witness, judge, consensus, ancient, future, expired, stale, orphan, invalid, voter, initial voter, vote collector, election, preconsensus event stream (PCES)
- [Event relationships](#event-relationships) 
  - parent, self-parent, other-parent, child, self-child, other-child, ancestor, descendant, self-ancestor, self-descendant, see, strongly see, branch
- [Address Books](#addressbooks) 
  - address book, roster, roster queue
- [Misc terminology](#misc) 
  - pre-consensus event stream (PCES), tipset, transaction timestamp
- [Parameters](#parameters) 
  - numRoundsNonAncient, numRoundsNonAncient, numRoundsFutureRoster, useD12, maxOtherParents

<a name="hapi"/>

# HAPI - Hiero API (Hedera API)

HAPI is the standard for creating and signing transactions, and the effect of handling them, as part of the 5 HAPI services.

  [top](#top)

<a name="entity"/>

## Entity

A HAPI entity is an object with an address like 0.0.1234 that represents an account, smart contract, file, token type, scheduled transaction, or HCS topic.

<a name="auto-renewal"/>

- **Auto renewal**: Is a feature designed to automatically renew the lifecycle of certain entities (
like accounts, files, smart contracts, topics, or tokens) by funding their renewal fees. This is
particularly useful for entities that require continuous operation over long periods, as it
automates the process of keeping these entities active by periodically charging a linked auto-renew
account. See https://hips.hedera.com/hip/hip-16 and https://hips.hedera.com/hip/hip-372 for more
information. Note that rent and expiration are not currently enabled but will be in the future.

  [top](#top)

<a name="transaction"/>

## Transaction

A transaction is submitted to the network, is paid for with a transaction fee, causes effects according to the 5 HAPI services. The transaction (and event) and its effects are written to the block stream, and can be later accessed through a block node or mirror node.

<a name="congestion-pricing"/>

- **Congestion Pricing**: A mechanism designed to manage network congestion by dynamically adjusting
transaction fees based on network demand. The primary goal of congestion pricing is to discourage
excessive network usage during peak times. Refer to
[Congestion Pricing](https://github.com/hashgraph/hedera-services/blob/main/hedera-node/docs/fees/automated-congestion-pricing.md)
and
[Fees](https://github.com/hashgraph/hedera-services/blob/main/hedera-node/docs/design/app/fees.md).

<a name="admin-transaction"/>

- **Admin Transaction**: A HAPI transaction which requires the transaction fee payer to be a
[Privileged System Account](#privileged-system-account). Some examples of Admin Transactions
include the following:

  - Freeze Transactions
  - System Delete
  - System Undelete
  - Address Book File 0.0.101/102 updates
  - Exchange Rate File 0.0.111 updates
  - Dynamic Properties File 0.0.121 updates

<a name="synthetic-transaction"/>

- **Synthetic Transaction**: Any transaction that is neither submitted through HAPI nor created by the
platform. An example of which is the the deletion of an expired entity that did not pay rent.
Synthetic transactions are presented in the block stream (and record files) as a non-system
transaction with a non-zero nonce **or** `scheduled = true` in the `TransactionID` value. Note that
the use of a scheduled flag instead of a non-zero nonce for scheduled transactions is for legacy
reasons.

<a name="child-transaction"/>

- **Child Transaction**: A [Synthetic Transaction](#synthetic-transactions) that the EVM or Services
initiates as a result of fulfilling a user-initiated transaction ( e.g.
[Hollow Accounts](#hollow-account) creation, auto-account creation, or a scheduled execution).

<a name="system-transaction"/>

- **System Transaction**: A transaction created by the platform (not services) software. An example of
which is a node's signature on a state. Inside an event, every transaction has a flag saying whether
it is a system transaction or not.

  [top](#top)

<a name="account"/>

## Account

An account can hold hbars and other fungible and non-fungible tokens. It must be associated with the token type of any token it holds (other than hbar). It has public keys associated with it whose corresponding private keys must be used to transfer from it or change it.

<a name="eoa"/>

- **Externally Owned Account (EOA)**: Accounts that are controlled by private keys and are not
associated with smart contracts. These accounts are used to send and receive transactions on the
network and interact with smart contracts. In contrast to contract accounts or token accounts, EOAs
are typically owned by individual users and are used to manage their assets and participate in the
network. Some examples of these are [Application Accounts](#application-account) and
[Civilian Accounts](#civilian-account)

<a name="hollow-account"/>

- **Hollow Account**: An account that has been created with an account number and alias but lacks an
account key. This term is specifically used to describe accounts are auto-created by doing a
CryptoTransfer to an alias that is of EVM address size, which allows applications to create user
accounts instantly, even without an internet connection, by assigning an account alias. See also
[Account Aliases](#account-aliases), https://hips.hedera.com/hip/hip-32 and
https://hips.hedera.com/hip/hip-583.

<a name="privileged-system-account"/>

- **Privileged System Account**: An account with an entity ID of 0.0.1000 or lower. These are
accounts that are authorized to handle [Admin Transactions](#admin-transaction). Some privileged
accounts, like account 0.0.2 or 0.0.58, are owned by the Hedera Council. This means their keys are
on the account and their signatures would be required if the account is being modified or used to
pay for - [Admin Transactions](#admin-transaction)

<a name="application-account"/>

- **Application Account:** Used by developers and users interacting with applications deployed on
Hedera. These accounts enable users to interact with the functionality provided by dApps, such as
gaming, finance, or other decentralized services.

<a name="civilian-account"/>

- **Civilian Accounts**: User accounts that are not associated with any special roles or permissions
beyond standard user functionality. These are mostly used for testing purposes. By contrast, System
Accounts and Node Accounts are accounts that have specific roles and responsibilities within the
infrastructure and governance of the Hedera network.

  [top](#top)

<a name="hts"/>

## HTS - Token Service

Allows for creating, transferring, and managing fungible and non-fungible tokens. These don't include tokens such as ERC-20 or ERC-721 that exist solely inside a smart contract. But smart contracts can access HTS tokens as if they were inside a smart contract.

<a name="auto-association"/>

- **Auto Association**: The ability of accounts to automatically associate with tokens. An auto
association is one or more slots you approve that allow tokens to be sent to your contract or
account without explicit authorization for each token type. If this property is not set, you must
approve each token manually (via the `TokenAssociateTransaction` in the SDKs) before it can be
received, held, or sent by that account. https://hips.hedera.com/hip/hip-904 - currently in
progress, will represent a significant change which allows for the number of "automatic association"
slots to be set to -1 which represents infinite slots.

<a name="token-allowance"/>

- **Token Allowance**: A feature that allows an account (the owner) to authorize another account (the
spender) to transfer tokens on its behalf up to a specified limit. This mechanism is similar to the
ERC-20 allowance mechanism used in Ethereum. The amount of tokens that a user has authorized another
user or smart contract to spend on their behalf. This allowance is set by the user and can be
modified or revoked at any time. It allows the spender to spend tokens on behalf of the owner
without requiring explicit approval for each transaction.

  [top](#top)

<a name="hscs"/>

## HSCS - Smart Contract Service

Allows creating, managing, and running smart contracts, written in Solidity, and executing in an EVM.

<a name="alias"/>

- **Alias**: A value that can be associated with an account, and used in some circumstances instead
of the account id [triplet](#triplet).

  Most often used to indicate a 20-byte value that can be used in smart contracts to refer to a
Hedera account.

  All accounts will have a (derived) account num alias, but all accounts _may_ have a single
EVM address alias _or_ key alias.

  The different kinds of alias are detailed in HIP-583[^583].  HIP-32[^32] also describes a _key
alias.

  [^32]: [HIP-32](https://hips.hedera.com/hip/hip-32) "Auto Account Creation"
  [^583]: [HIP-583](https://hips.hedera.com/hip/hip-583)
  "Expand alias support in CryptoCreate & CryptoTransfer Transactions"

  - **Account num alias**:  The account ID triplet, encoded as a 64-bit `long` in the usual way,
    then prefixed with 12 bytes of `0` to form a 20-byte value
    * a.k.a. "long zero address" (though this term is somewhat deprecated)
  - **EVM address alias**: An EVM address - the rightmost 20 bytes of the 32 byte Keccak-256 hash of
    an ECDSA sep25661 public key; that is, an "address" on an Ethereum-like chain as defined in the
    Ethereum Yellow Paper.
  - **Key alias**: Available if an account's key is a "primitive" key - not a
    threshold key or keylist.  These are protobuf serialized keys of ED25519 or ECDSA `Key`s.
    * AKA **Account Alias** in the earlier HIP-32[^32]

<a name="delegate-call"/>

- **Delegate Call**: Used to call a function in another contract, but with the context of the calling
contract. This allows for the execution of functions in one contract as if they were part of another
but retains the original caller’s context (msg.sender and msg.value). See
[Delegate Call](https://medium.com/@solidity101/understanding-call-delegatecall-and-staticcall-primitives-in-ethereum-smart-contracts-dfff21caa727)
for more information.

<a name="precompile"/>

- **Precompile**: Behave as if they were contracts deployed at specific addresses (with one
exception: The EIP-4788 "beacon roots contract" which is a real contract deployed at a specific
address) In Ethereum, they are very low addresses, 0x00 .. 0x0F). They are not implemented as
contracts because that would be far too slow, instead they are built-in to the code of the Ethereum
EVM, which checks to see if the address you're calling is one of the precompiles and then just runs
the related code. We call our equivalent [System Contracts](#system-contracts) but there may still
some legacy references to "precompiles" in the codebase and older documentation.

<a name="security-model-v2"/>

- **Security Model v2**: Hedera introduced the HSCS Security Model v2 to enhance the security of its
network building with improved features to better safeguard against potential vulnerabilities and
attacks. By incorporating enhanced cryptographic techniques, robust consensus mechanisms,
decentralized governance, and continuous monitoring. Key features include - Restrictions on Smart
contracts Storage; Prohibition on Delegate Calls to System Smart Contracts; Limited Access to EOAs'
Storage; Token Allowance Requirement for Balance Changes. Refer to this
[official blog post](https://hedera.com/blog/hedera-smart-contract-service-security-model-update)
for more information.

<a name="system-contract"/>

- **System Contract**: Smart contracts that have a permanent address in the network, meaning they
will always be available at the same contract address. One example is the Token Service System
Contract which allows smart contracts on Hedera to interact with the Hedera Token Service, offering
functionalities like token creation, burning, and minting through the EVM

<a name="triplet"/>

- **Triplet**: An id for some Hedera semantic object (e.g., Account, Token Type, File) of the
form `shard.realm.num`.
  - For a standard entity id:
    - The display format shows `shard`, `realm`, and `num` in base-10
    - E.g., `0.0.12345678`
    - Each of `shard`, `realm`, and `num` are 8-byte `long` values
    - Currently `shard` and `realm` are always `0`
  - For aliases - where the triplet form is used for user input/output only and not used internally in the code:
    - `num` can be a hex-encoded value of:
      - The entity ID encoded as an account num alias (i.e., long-zero)
    - E.g., `0.0.00000000000000000000000000000000004D67FB`
  - EVM address
    - E.g., `0.0.b794f5ea0ba39494ce839613fffba74279579268`
  - Key alias
    - E.g., `0.0.1220d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a`

<a name="wei"/>

- **Wei and ETH**: A smart contract may refer to ETH and wei, where one ETH is 100,000,000 (100 million) wei. In HAPI, these translate to HBAR and tinybar. So 1 HBAR is equivalent to 100,000,000 (100 million) tinybars. An ethereum transaction referring to wei will actually translate to tinybars.

  [top](#top)

<a name="HSS - Scheduled Transaction Service"/>

## HSS - Scheduled Transaction Service

Allows creating a "scheduled transaction" that contains a HAPI transaction that will execute at some future time. The time can be chosen in advance, or it can be set to execute as soon as it receives enough signatures.

  [top](#top)

<a name="hcs"/>

## HCS - Consensus Service

Allows topics to be created, and messages to be sent to them. Each topic maintains a running hash and count of the messages flowing through it, and they can be read from a mirror node.

  [top](#top)

<a name="hfs"/>
    
## HFS - File Service

Allows files to be stored on the network. Some special files are used by the network itself, such as the address book.

<a name="address-book"/>

- **Address Book**: The _**network**_ address book is data structure that contains information (such
as node ID, alias, IP address, and weight) for all nodes in the network, not just consensus, but
also block nodes, future validator nodes and more. The address book is stored as a file on disk at
the moment but will be stored purely in state as part of Dynamic Address Book phase 3.
Disambiguation: There is also an "address book" in files 0.0.101 and 0.0.102 which is different to
the "network address book" which are manually managed by devops.

  [top](#top)

<a name="block-streams"/>

# Block Streams

All transactions, events, and results of handling transactions are written to a block stream, digitally signed by the nodes in such a way that a block proof or state proof can cryptographically prove what was in state at any given time, and what appeared in the stream at any given time.

<a name="bls-signature-algorithm"/>

- **BLS Signature Algorthm (Boneh-Lynn-Shacham)**: A paired-elliptic-curve based signature algorithm with attractive
characteristics for aggregate signatures (signatures produced by aggregating many individual
signatures, and verifiable with a single public key). BLS comes from the names of the authors of the
initial research paper (Dan Boneh, Ben Lynn, Hovav Shacham). Authors Paper here:
[Link](https://link.springer.com/article/10.1007/s00145-004-0314-9)

<a name="block-proof"/>

- **Block Proof**: Cryptographic proof that a particular block contains a given block item. Block
proofs will also be the basis of State Proofs as the root hash of states will be included in each
block. see

<a name="block-signature"/>

- **Block Signature**: Aggregated (BLS) signature of the hash of Block stream merkle root. Appended to
the end of a block stream it is used to verify the block was produced by the majority weight of the
network. These signatures will also be used for Invalid State Detection.

<a name="block-stream"/>

- **Block Stream**: A fully verifiable stream of data consisting of individual blocks corresponding to
consensus rounds. Each block contains all events, transactions, and state changes that occurred
within one consensus round. A block is transmitted from consensus node to block nodes as a stream of
individual block items, which form a merkle tree that is verified by a Block Signature. Each block
is connected to the previous block by a Block Hash which, together form a block chain.

<a name="sidecar"/>

- **Sidecar**: In Services, a sidecar refers to additional records that are created alongside the
main transaction records. These sidecar records provide more detailed information about the
transactions, making it easier to debug and understand the state changes that occur as a result of
the transactions. Note that these are related to the current record stream implementation and will
be replaced by block streams in the future.

  [top](#top)

<a name="other"/>

# Other

Other topics outside the above categories.

<a name="config-txt"/>

- **Config.txt**: A text file loaded by the platform on startup if no valid state is found on disk.
This file contains the address book to use when starting from genesis.

<a name="leaky-test"/>

- **Leaky Test**: Are unit or other tests that should not be run in parallel because they can “leak”
some of their state or data out into the global state.

  [top](#top)

<a name="hashgraphConsensus"/>

# Hashgraph Consensus

The following terms are relevant to the Hashgraph consensus algorithm, which is the core algorithm running on mainnet nodes to put the transactions in consensus order and assign them consensus timestamps, before the transactions are handled.

  [top](#top)

<a name="rounds"/>

## Rounds

- **Round** - events reach consensus in batches, called `rounds`. The first batch of events to reach consensus is round 1, the next is round 2, and so on. At any given moment, certain rounds have names, as defined below.
- **Pending** round - the round number of the minimum round that hasn't yet reached consensus. This is the round whose judges are currently being determined. Those consensus calculations use the roster whose `rosterRound` is the current `pendingRound`. Any new event is created containing a `birthRound` field equal to the current `pendingRound`. All events currently in the hashgraph should have birth rounds in the range from `(minJudgeBirthRound - numRoundsNonAncient)` to `pendingRound`, inclusive, so they are neither `ancient` nor `future`.
- **Min judge birth** round - the minimum birth round of all the judges in round `pendingRound - 1`. This is used to define which rounds are ancient and future, which controls which events can be in the hashgraph.
- **Ancient** round - a round is `ancient` if its birth round is less than `(minJudgeBirthRound - numRoundsNonAncient)`. Events with ancient birth rounds are removed from the hashgraph (or treated by consensus as if they weren't in the hashgraph).
- **Future** round - a round is `future` if its birth round is greater than the `pending round`.
- **Min judges birth** round - the minimum birth round of all the judges in round `pendingRound - 1`. This is used to define which events are ancient.
- **Max consensus** round - the round number for the maximum round that has reached consensus. <br>
   - `maxConsensusRound = pendingRound - 1`
- **min non-ancient** round - the min round that is not ancient<br>
   - `minNonAncientRound = minJudgeBirthRound - numRoundsNonAncient`
- **min non-expired** round - the min round that is not expired<br>
   - `minNonExpiredRound = minJudgeBirthRound - numRoundsNonAncient`
- **max roster** round - the maximum future round number for which there should be a roster in the queue<br>
   - `maxRosterRound = pendingRound + numRoundsFutureRoster`
- **future** round - any round greater than `pendingRound`. Events with birth rounds greater than this are not put in the hashgraph.
- **Roster** round - Any given roster (subset of an address book) is associated with a long `rosterRound`, which is its roster round number. The `nodeID`s and consensus weights in a roster should be used when its `rosterRound` equals the pending round. A queue of rosters must be stored in state, one for each round number from `minNonAncientRound` to `maxRosterRound`, inclusive. Every time a round reaches consensus, the oldest roster can be removed. Any time the transactions in a round are handled, a new roster is added for the future. An event is only allowed to be in the hashgraph when its birth round is the roster round for some roster in the queue. And it must have been created by a node that is listed in that roster.  Of course, ancient, non-expired events may still be in memory, to gossip to nodes that are behind this node, but they don't count as being in this node's hashgraph. Events with birth rounds too far in the future should not be accepted during gossip. And a node should not send such events to another node, if it knows that node will just discard them for that reason. So conceptionally, there is a queue of rosters with one for each of the roster rounds. In implementation, since most rounds have identical rosters, this can be done with less memory by just having a queue of pointers, where multiple pointers point the same roster. Or it can even store the oldest roster, and store in what round it will change. But the effect is the same as a complete queue of rosters, in the appropriate range.
- **round timestamp** - the timestamp for a round is defined as the transaction timestamp of the last transaction in the last event that reached consensus in that round. So the state for that round reflects the effects of all transactions with a timestamp equal to or less than the round timestamp.
   
  [top](#top) 
  
<a name="fields-of-an-event"/>

## Fields of an event

The fields in the first section below are created when the event is created, filled in by the creator node, signed by the creator node, and gossiped to all other nodes. The other sections are fields calculated by a node locally, about all of the events it receives or creates, and are not gossiped to other nodes.  

The sections below list all the fields inside an event. In the first section, 4 of the fields make claims about a parent event: the creator and birth round of the self-parent and other-parent. If a malicious node creates an event, it is possible that it might make one or more incorrect claims, and so the claim won't match the creator or birth round of the actual parent. An event cannot be added to the hashgraph until all of its parents about which it made false claims have become ancient. In other words, liars can't be added to the hashgraph. But once a parent becomes ancient, it's as if that parent no longer exists, and so the false claim about it is no longer treated as a lie.

<a name="eventFieldSigned"/>

### signed, immutable fields (gossiped, in the block stream)
- **transactions** - the list of transactions (possibly empty)
- **birthRound** - set to the creator's pending round at the moment of creation. Is signed and immutable.
- **createdTimestamp** - creator's claimed wall-clock time when it was created, must be later than self-parent.
- **selfParent** - the hash of the self parent (or null if there is none, or the self-parent's birth round is ancient)
- **selfParentBirthRound** - the claimed birth round of the self-parent. 
- **otherParent** - the hash of the other parent (or null if there is none, or the other parent is ancient). 
- **otherParentBirthRound** - the claimed birth round of the other-parent. 

<a name="eventFieldUnsignedStreamed"/>

### unsigned, immutable, streamed fields (not gossiped, in the block stream)
- **consensusRound** - the min round in which this event was an ancestor of all judges. 
- **consensusTimestamp** - the median timestamp of when it first reached each of the nodes that created judges in its consensus round. This is adjusted by adding nanoseconds to ensure that in consensus order, each transaction is at least 1000 nanoseconds after the previous one. (That ensures they are unique and monotonically increasing, and there are big enough gaps so that synthetic transactions inserted between them can have timestamps that are unique).
- **consensusOrder** - an long `N` indicating this is the `N`th event in all of history, according to the calculated consensus order.

<a name="eventFieldUnsignedNonstreamed"/>

### unsigned, immutable, non-streamed fields (not gossiped, not in the block stream)
- **Non-Deterministic Generation (NGen)** 
  - **Which**: set for non-ancient events
  - **Formula**: set to 1 plus the max NGen of its non-ancient parents (or set to 1, if there are none)
  - **When**: set when an event leaves the orphan buffer, just before it is sent to PCES
  - **Consistency**: an event can have different NGen on different nodes
  - **Uses**: used to decide which events to recalculate for each round, and for the GUI, and in the tipset algorithm, and when there is a need to do a non-deterministic topological sort (such as in sync). Maybe this should also be used in metrics and in tests.
  
- **Consensus Generation (CGen)** 
  - **Which**: set for the events that reached consensus in a given round
  - **Formula**: set to 1 plus the max CGen of its parents that reached consensus in the same round (or set to 1, if there are none). 
  - **When**: set for each event that reaches consensus in a given round. As they are found (by depth-first search of the hashgraph), each event is assigned a generation after its parents are assigned. 
  - **Consistency**: an event will have the same CGen on all nodes
  - **Uses**: used as one of the tie-breaking cases when sorting the events into consensus order for a round. Maybe it isn't used anywhere else.
  
<a name="eventFieldMutable"/>

### unsigned, mutable fields (not gossiped, not in the block stream)
- **Deterministic Generation (DGen)** - 
  - **Which**: set for all events that have positive voting rounds (not round 0 or round negative infinity)
  - **Formula**:  set to 1 plus the max DGen of its parents that have positive voting rounds (or set to 1, if there are none)
  - **When**:  set for each event at the same time its `votingRound` is set, during the recalculation that happens for each new consensus round
  - **Consistency**: an event will have the same DGen on all nodes for a given round, though it can change for each round 
  - **Uses**: used in the lastSee() function for consensus. Maybe it isn't used anywhere else.

- **votingRound** - the round for this event, which determines whether it is a witness. It is a witness if its voting round is different from its self-parent's voting round (or it has no self-parent). If it is a witness, then it might be eligible for election as a judge (if it's in the pending round), or it might be an initial voter for judge (if it's in the pending round plus 1), or it might be a voter and vote collector (if it's later than the pending round plus 1). Every time the processing of a new round starts, the voting round is recalculated for all events. It can be different than it was in the past because non-ancestors of the latest judges are defined to have a voting round of negative infinity (represented as a 0 in memory), and that can cause all their descendents to change their voting rounds. If a judge in the consensus round is not a descendant of any other judge in the consensus round, then it is guaranteed to have a voting round equal to the consensus round. In the fields listed here, this is the only one that is mutable. All the rest are immutable, and are signed when the event is created.


  [top](#top)

<a name="events"/>

## Events

- **Witness** event - an event whose voting round is greater than its self-parent's voting round (or which doesn't have a self parent). Only witnesses can be judges or voters.
- **Judge** event - an event that wins the election to be made a judge. It must be a witness, and it will have tended to have been gossiped to most of the other nodes quickly (otherwise it would have lost the election). An event reaches consensus when it is an ancestor of all judges in a given round. The minimum round where that happens is its consensus round. It's a math theorem that every round is guaranteed to have at least one judge, and a math conjecture that every round is guaranteed to have judges created by a supermajority of nodes (>2/3 of weight).
- **Consensus** event - an event that has reached consensus
- **Ancient** event - an event whose birth round is less than `(minJudgeBirthRound - numRoundsNonAncient)`. The `minJudgeBirthRound` is the minimum birth round of all the judges in round `pendingRound` - 1. This event should not be in the hashgraph, though it may still be in memory in order to give to other nodes that are behind this one. During gossip, a node will discard any event that is ancient for it. So no event should be sent to a node if that event will be ancient for that node.
- **Future** event - an event whose birth round is greater than the `pendingRound`. These are not added to the hashgraph while they are still future.
- **Expired** event - an event whose birth round is less than `(pendingRound - numRoundsNonAncient)`. Expired events should be removed from memory. So if a node is so far behind that the events it needs are expired for its neighbors, then it will not be able to catch up by gossip alone, so it will have to do a reconnect to catch up.
- **Stale** event - an event that became ancient before reaching consensus. It is guaranteed to never reach consensus, since only non-ancient events are allowed to reach consensus. If a node is active and keeping up with the network, it is very unlikely that any event it creates can ever go stale. An event it creates will typically spread by gossip until it reaches most of the other nodes within one `broadcast period`, which is about half of a round. So the only way its newly-created event could go stale is if it is so far behind the other nodes that its current consensus round is almost `numRoundsNonAncient` rounds behind them at the moment it creates an event. At which point, it is on the verge of becoming "fallen behind" and having to do a reconnect. 
- **Orphan** event - if an event is received before one of its non-ancient parents is received, then it cannot be put into the hashgraph. It is an orphan. Orphan events can be either discarded, or put into an orphan buffer. It can then leave the orphan buffer when each of its parents is either present (and not, itself, and orphan), or is ancient. 
- **Invalid** event - an event that can be immediately discarded because it has an invalid signature or it cannot be parsed, or it has some other error that is immediately visible, independent of any other events. (An event can be "bad" in other senses, without being invalid, such as if it is a branch, or if it claims a parent has a birth round that differs from that parent's true birth round).
- **Voter** event - an event that is currently acting as a voter in an election. It can be either an initial voter, or a vote collector. 
- **Initial voter** event - a witness with a voting round equal to `1 + pendingRound`. For each node, it votes for the witness created by that node in round pendingRound, or votes NULL if it cannot see any witness by that node in that round. (That's ordinary seeing, not strongly seeing). 
- **Vote collector** event - a witness with a voting round greater than `1 + pendingRound`. It collects votes from all witnesses that it can strongly see in the previous voting round. For each node, it sets its vote for witness in the pending round to be the majority (or plurality) of the votes it collected. In case of a tie, it picks the witness with the least signture lexicographically. For a given node, the event it creates in one round might have a different vote than the one it creates in the previous round. So this is like a node virtually voting in many rounds, repeatedly changing its vote to match the majority of its peers. For any particular election (about whether a particular event is a judge), if a vote collector collects a supermajority of votes that agree, then it `decides` that vote, and that election is over.
- **Election** event - a witness event in the pending round, which is currently being voted on for judge. The election is guaranteed to eventually be `decided` (with probability one), at which point that witness will either be declared to be a judge or not. There is a theorem that as a hashgraph grows, once a single witness is known for round `1 + pendingRound`, any witness in the pending round that is not yet in the hashgraph will be guaranteed to lose its election for judge. So any further witnesses added to that round later will not have actual elections calculated. They will just be instantly decided to not be judges. And there is a theorem that eventually (with probability one), each of the existing witnesses in the pending round will eventually have its election decided, so it will eventually be declared to either be a judge or not. When all such witnesses have been decided, then the complete set of judges will have been decided, so the round reaches consensus at that moment.
- **Preconsensus event stream (PCES)** event - an event that has not yet reached consensus, which is written to storage so that, if the node restarts (either intentionally, or as a result of a crash), the node can read these events back into memory. This helps it avoid accidentally creating an event that is a branch of one it created before the restart. The alternative is to gossip for a while after a restart, before creating any new events. But that approach can still fail if it doesn't gossip for long enough. And it will fail if all the nodes crash at the same moment, due to a software bug.

  [top](#top)

<a name="event-relationships"/>

## Event relationships

- **Parent**`(x,y)` - a parent of `x` is `y`. So `x` contains the hash of `y`. The parent is either a self-parent or an other-parent. An event can have at most one self-parent.
- **SelfParent**`(x,y)` - the self-parent of `x` is `y`. So `x` contains the hash of `y`. Both `x` and `y` must have the same creator. The difference of birth rounds for `x` and `y` is at most `numRoundsNonAncient`. If it would have been greater, then `x` should simply be created with no self-parent.
- **OtherParent**`(x,y)` - the other-parent of `x` is `y`. So `x` contains the hash of `y`. The events `x` and `y` must have different creators. The difference of birth rounds for `x` and `y` is at most `numRoundsNonAncient`. If it would have been greater, then `x` should simply be created with no other-parent. Or with a different other-parent where the difference is smaller. (This will be changed to `otherParents` plural, when multiple other parents are implemented).
- **Child**`(x,y)` - a child of `x` is `y`. This means that `x` is a parent of `y`. 
- **SelfChild**`(x,y)` - a self-child of `x` is `y`. This means that `x` is a self-parent of `y`.
- **OtherChild**`(x,y)` - an other-child of `x` is `y`. This means that `x` is an other-parent of `y`.
- **Ancestor**`(x,y)` - an ancestor of `x` is `y`. This means that `y` is either `x`, or a parent of `x`, or a parent of a parent of `x`, etc.
- **Descendant**`(x,y)` - a descendent of `x` is `y`. This means that `x` is a ancestor of `y`.
- **SelfAncestor**`(x,y)` - a self-ancestor of `x` is `y`. This means that `y` is either `x`, or a self-parent of `x`, or a self-parent of a self-parent of `x`, etc.
- **SelfDescendant**`(x,y)` - a self-descendant of `x` is `y`. This means that `x` is a self-ancestor of `y`.
- **See**`(x,y)` - `x` can see `y`. If there is no branching, then this is the same as `y` being an ancestor of `x`. If there is branching, then it means that among all of `x` and its self-ancestors, `y` became an ancestor of that chain earlier than any branch of `y` became an ancestor.  In other words, you "see" your ancestors, and if there's a branch, then you "see" the side of the branch that you became aware of first, and you never "see" the other side of that branch, even after it becomes your ancestor.
- **StronglySee** (x,y) - `x` can strongly see `y`. This means that `x` can see events created by a supermajority of nodes (`>2/3` by weight), each of which can see `y`. In other words, there are paths from `x` to `y`, following parent pointers, that pass through a supermajority of creators. Because of the definition of "see", there will always be paths through the creator of `x` and through the creator of `y`. 
- **Branch**(x,y) - `x` and `y` form a branch if all three of the following conditions hold. The definition of seeing ensures that any given event can see at most one of `x` and `y`, if they form a branch. And for an honest node, if it creates an event that sees one of `x` or `y`, all of its self-descendents will continue to see that same `x` or `y`, and none of them will ever see the other one. The 3 requirements for them to constitute a branch are:
   - both are created by the same creator
   - neither is a self-ancestor of the other
   - the difference of the birth rounds is at most `numRoundsNonAncient` (for the value of `numRoundsNonAncient` defined in the roster whose `rosterRound` equals the max of their birth rounds)

  [top](#top)

<a name="addressbooks"/>

## Address Books

- **Address book** - the current info for all nodes, stored as a file in the on-ledger file system, and therefore also in state. This includes public keys, IP addresses, node ID, proxies, consensus weight, and other info.
- **Roster** - a subset of the address book, with just the information needed for consensus. This includes the nodeID, public key for signing events, consensus weight, and some cryptographic info related to TSS and state proofs. Each roster is associated with an unsigned long `rosterRound`. The roster with a given `rosterRound` number will be used while calculating which events reach consensus in round number `rosterRound`. So it is used when `rosterRound = pendingRound`. That calculation is started after round `rosterRound - 1` reaches consensus. When round `r` reaches consensus, all its transactions are handled, which might modify the address book, and the final address book at the end of handling is used to construct roster number `r + 1 + numRoundsFutureRoster`. In that case, the value of the setting `numRoundsFutureRoster` is the one in the roster for round `r`. If transactions are designed to change `numRoundsFutureRoster`, they can instantly reduce it by any amount, or they can increase it by at most 1 per round.
- **Roster queue** - a queue of rosters for every round from round number `minNonAncientRound` through `maxRosterRound`, inclusive.  When the `pendingRound` reaches consensus, then `pendingRound`, `minNonAncientRound` and `maxRosterRound` will all increment by 1. At that point, the oldest roster is removed from the queue. And ideally, the roster for the new `maxRosterRound` would be added immediately. But it will actually be added slightly later, when the round that just reached consensus has been processed (handled). So the queue will sometimes be missing one or more of the latest rosters. That is ok. The hashgraph should only contain events that have created rounds from `minNonAncientRound` through `pendingRound`, inclusive. When each of those events is received during gossip, it will have its signature checked according to the public key associated with its creator's nodeID in the roster whose `roster number` matches its birth round. So it is OK for processing of rounds to fall behind the consensus of rounds, by `numRoundsFutureRoster` rounds, without any bad effects. But if enough rosters are missing from the queue so that the `pending round` roster is missing, then consensus will freeze until round number `pendingRound - numRoundsFutureRoster - 1` has been processed.  There's no particular problem with consensus freezing in that case, because there are already several rounds that have reached consensus and are waiting to be processed, so there is no harm in waiting until the event processing is ready for another one.

  [top](#top)

<a name="misc"/>

## Misc terminology
- **Pre-Consensus Event Stream (PCES)** - a stream to a node's hard drive of all events it created and all events it added to its hashgraph. It is guaranteed to flush this stream (so the events actually reach the hard drive) after each time it creates an event (before gossiping it out), and after each time it reaches consensus on a round (before sending it to Services to handle its transactions). 
- **TipSet** - an algorithm and a class. When a new event is created, the tipset algorithm chooses which events should be its other parent(s). It is designed to only include `tips` (events with no descendents yet that have been sent to the tipset algorithm). Events are sent to the tipset algorithm after they have been validated and are no longer in the orphan buffer or future buffer. The tipset algorithm immediately forwards them to consensus to add to the hashgraph. Currently, only 1 event can be an other parent for a given event, so it chooses the one that will make the most progress toward consensus. Or it will delay creating an event (briefly) if there are no parents that would be helpful. When we start to allow multiple other parents, then it might be set to do something like include the 5 most helpful parents, plus at most 5 random parents. 
- **Transaction timestamp** - Once an event is given a consensus timestamp, then each transaction inside it is given a unique timestamp. The first transaction has the same timestamp as the event, and then it increments by 1000 nanoseconds for each transaction after it. If a timestamp of T is assigned to the last transaction, then the next event in consensus order must have a consensus timestamp of at least T plus 1000 nanoseconds. If its timestamp is less than T+1000ns, then it is set to T+1000ns as its `consensus timestamp`. This guarantees that event timestamps are always increasing, and that there is always at least 1000 nanoseconds between adjacent transactions in consensus order.

  [top](#top)

<a name="parameters"/>

## Parameters

- **numRoundsNonAncient** - number of rounds to be ancient
- **numRoundsNonAncient** - number of rounds to be expired
- **numRoundsFutureRoster** - number of future rounds desired in the roster queue
- **useD12** - should the modified d12 algorithm be used? (currently `FALSE`)
- **maxOtherParents** - max number of other parents allowed (currently 1)

  [top](#top)
  
------------------------

The above Hashgraph Consensus topics reflect all the appropriate changes that come from the comments here:

<https://github.com/hiero-ledger/hiero-consensus-node/pull/16619>

The other categories have entries that still need to be checked. They were copied from:

<https://github.com/hiero-ledger/hiero-consensus-node/blame/main/docs/glossary.md#bls>

