# Fair sync selector

Mechanism of sync permits was implemented to implement hard limit of syncs running and same time and dynamically reduce number of concurrent syncs in case system becomes unhealthy.  Unfortunately, it doesn't have any fairness built-in, which means that in case there are significantly less permits than peers, same peers can be selected over and over for synchronization, while starving all others.

Additionally, permit system is used as main synchronization point with possible reconnect logic, making it very brittle and hard to modify.

With introduction of RPC sync and possible broadcast extension, we want to reduce amount of concurrent syncs, at same time ensuring at least basic fairness. Peers which were recently chosen for synchronization should be given less priority for next synchronization.

Proposed implementation puts another layer of fair sync selector inside RPC sync mechanism. It has two parameters, configured to be either fixed number, or ration of total nodes in the system.

* maxConcurrentSyncs
* minimalRoundRobinSize

Let's assume, for the sake of illustration that we have 20 peers, with 10 maxConcurrentSyncs and 5 minimalRoundRobinSize

This means that:
- node will only initiate up to 10 synchronizations while they are running
- it WILL accept syncs initiated by other parties, breaching number of concurrent syncs, but will not initiate any of its own until total number, including forced ones drops below 10
- in case there are free slots for the synchronization, it will not start sync with peers which were recently selected (soft limit)
- it won't repeat syncs with any peers until at least minimalRoundRobinSize syncs were performed against different peers (hard limit)

Exact way recently synced peers are avoided is an implementation detail, but in above example, if all 20 nodes were synced with, all synchronizations have finished, then 10 most recently synced peers will be avoided for the next synchronization.
