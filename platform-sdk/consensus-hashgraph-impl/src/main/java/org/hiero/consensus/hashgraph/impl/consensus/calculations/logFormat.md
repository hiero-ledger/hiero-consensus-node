# Format of a log test .CSV file

The file shows the results of running the hashgraph consensus algorithm on a set of hashgraphs, including showing the results of all the internal memoized variables after each update of each event.

The file consists of multiple lines, each of which is a sequence of 'int64' numbers (Java 'long'), separated by commas. Each line ends with a number (not a comma), followed by a newline. The last line ends with a newline and is followed by an empty line.

The first number in each line tells its type:

- 0 = `NewHashgraph`
- 1 = `RoundInfo`
- 2 = `RoundInfoPrev`
- 3 = `EventSigned` (immutable parameters in the signed event)
- 4 = `EventInfo` (memoized parameters recalculated when the event updates)
- 5 = `UpdateResults` (the consensus events and other information returned when `update()` reaches consensus)

If any field is an array, it is represented by its length followed by all elements in order. For stake, the elements are nonnegative `int64` numbers. For an `EventInfo[]`, each element is the `eventID` of that `EventInfo`. The `eventID` for a `null` is -1. Every `boolean` is `0` for `false` and `1` for `true`. For an `Instant`, there are two `int64` numbers: the seconds since the start of the epoch, then the number of nanoseconds since the start of the second. Both are -1 if it is `null` (hasn't been assigned yet). The following gives all the fields for each type, in order:

```
  NewHashgraph (type 0)
    int64       hashgraphID
    int64       softwareVersion
    int64       randomSeed
    int32       year // UTC time that this file was created (7 fields)
    int32       month
    int32       day
    int32       hour
    int32       min
    int32       sec
    int32       nano

  RoundInfoPrev (type 1)
    int64       pendingRound
    boolean     prevJudgeCon1
    EventInfo[] prevJudges
    boolean     prevJudgesCopied
    int64       prevMinNonAncientRound
    int64       prevNumCons
    int64       prevMinJudgeBirthRound

  RoundInfo (type 2)
    int64       pendingRound
    int64[]     nodes // each of these is a nodeID, not an index
    int64[]     stake
    int32       seeNum
    int32       seeDen
    boolean     judgeCon1
    int32       coinInterval
    int32       targetNumRoundsNonAncient
    int32       numRoundsAddressBook

  EventSigned (type 3)
    int64       eventID
    Instant     timeCreated
    int64       creatorNodeID
    int64       birthRound
    int32       coin // this is a uniform random int32, not limited to [0,numNodes]
    EventInfo[] parentsSigned

  EventInfo (type 4)
    int64       eventID
    int32       creatorIndex // index into RoundInfo.nodes[] from birth round (-1 if not there)
    EventInfo   selfParent
    int64       maxJudgeRound
    EventInfo[] parents
    int64       totalStake
    long        minNonAncientRound
    int32       voteD // either 1 or 2
    boolean[]   ancestorJudge
    long        gen
    EventInfo[] lastSee
    EventInfo[] stronglySeeP
    long        votingRound
    EventInfo   firstSelfWitnessS
    EventInfo   firstWitnessS
    EventInfo[] stronglySeeS1
    EventInfo[] voteE (EventInfo part of the vote() pair)
    boolean[]   voteB (boolean part of the vote() pair)

  UpdateResults (type 5)
    int64       pendingRound
    EventInfo[] searchOrder // consensus events in search order (not in the record)
    EventInfo[] consensusEvents // consensus events in consensus order
    Instant[]   timeCon // timeCon each consensus event, in consensus order
    long[]      gen // gen for each consensus event, in consensus order
    Instant     roundTimestamp
    int32       voteD // either 1 or 2
    boolean     usedCoin // were there any coin rounds while deciding this round?

  EventInfoConsensus (type 6)
    int64       eventID
    boolean     isConsensus
    long        consensusOrder
    Instant     consensusTimestamp
```

Compared to the paper, this format skips `roundInfoPrev` in `UpdateResults` (because it's a separate line). It also takes the `EventInfo` from the paper (and Java implementation) and splits it into 3 kinds of rows: `EventSigned` for the immutable fields, `EventInfoConsensus` for the fields set when it reaches consensus, and `EventInfo` for all the other mutable fields. It skips `payload`, `parentBirthRounds`, `parentCreators`, and `signature` in `EventSigned`, because they don't affect consensus). It also adds fields for `EventID` and `PendingRound` to several of the row types to help identify the objects.

Each new hashgraph starts with a `NewHashgraph` row. If the reader ever sees another `NewHashgraph` row, then it should discard the current hashgraph and all the events and start over with a new hashgraph and an empty set of events. Every `NewHashgraph` is immediately followed by a `RoundInfoPrev` row then a `RoundInfo` row, both with the same pending round, which can be any positive integer (1 to simulate a genesis start, and >1 to simulate a reconnect).

It then gives the `EventInfo` rows for many events. Whenever an event appears for the first time for this hashgraph, it gives its `EventSigned` row just before its `EventInfo` row. Each `EventInfo` is the state of the event immediately after it is updated with the most recent `RoundInfoPrev` and `RoundInfo`.

If an event decides a round (so the `update()` method returned a non-null result), then the next row will be the `UpdateResults` returned by that method. This is then followed by a `EventInfoConsensus` row for each of the events that just reached consensus (in consensus order), then the new `RoundInfoPrev` row and `RoundInfo` row, where the `pendingRound` is incremented by 1 in both of them. It then continues as above, with `EventSigned` rows and `EventInfo` rows, until the next time an event reaches consensus.

All events in every hashgraph will be valid events. So when an event is added to the hashgraph, its `parentsSigned` list has at most one parent for each creator, and lists the self-parent first (if there is one). Its `timeCreated` is greater than its self-parent. Its `birthRound` is greater than or equal to all its parents. Its `coin` will be a uniform random `int32` (rather than limited to the range 0 to n, for n nodes in the birth round). Nodes will always have nonnegative stake, with at least one node having positive stake.
