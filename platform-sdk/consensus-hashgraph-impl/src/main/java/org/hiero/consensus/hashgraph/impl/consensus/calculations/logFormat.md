# Format of a log test .CSV file
The file shows the results of running the hashgraph consensus algorithm on a set of hashgraphs, including showing the results of all the internal memoized variables after each update of each event.

The file consists of multiple lines, each of which is a sequence of 'int64' numbers (Java 'long'), separated by commas. Each line ends with a number (not a comma), followed by a newline. The last line ends with a newline and is followed by an empty line.

The first number in each line tells its type:

- 0 = `RoundInfo`
- 1 = `RoundInfoPrev`
- 2 = `Event` (immutable parameters in the signed event)
- 3 = `EventInfo` (memoized parameters recalculated when the event updates)
- 4 = `UpdateResults` (the consensus events and other information returned when `update()` reaches consensus)

If any field is an array, it is represented by its length followed by all elements in order. For stake, the elements are positive `int64` numbers. For an `EventInfo[]`, each element is the `eventID` of that `EventInfo`. The `eventID` for a `null` is -1. Every `boolean` is `0` for `false` and `1` for `true`. For an `Instant`, there are two `int64` numbers: the seconds since the start of the epoch, then the number of nanoseconds since the start of the second. Both are -1 if it is null (hasn't been assigned yet). The following gives all the fields for each type, in order:

```
  RoundInfo (type 0)
    int64 pendingRound

  RoundInfoPrev (type 1)
    int64 pendingRound

  Event (type 2)
    int64 eventID

  EventInfo (type 3)
    int64 eventID

  UpdateResults (type 4)
    EventInfo[] consensusEvents
```

Each round starts by giving `RoundInfo` then `RoundInfoPrev`. If they have a `pendingRound == 1`, then it is starting over with a new hashgraph, and all previous events in the file should be ignored. 

It then gives the `EventInfo` for many events. Whenever an event appears for the first time for this hashgraph, it gives its `EventSigned` just before its `EventInfo`.

If an event reaches consensus (so the `update()` method returned a non-null result), then the next line will be the `UpdateResults` returned by that method. This is then followed by the new `RoundInfo` and `RoundInfoPrev` where the `pendingRound` is incremented by 1, followed by many events recalculated with that new pending round.

All events in every hashgraph will be valid events. So when an event is added to the hashgraph, its `parentsSigned` list has at most one parent for each creator, and lists the self-parent first (if there is one). Its `timeCreated` is greater than its self-parent. Its `birthRound` is greater than or equal to all its parents. Its `coin` will be a uniform random `int32` (rather than limited to the range 0 to n, for n nodes in the birth round). Nodes will always have nonnegative stake, with at least one node having positive stake.