# DC gossip traffic profile: the epoch-length ceiling with stream-Snappy

The third note in the series, after [Gossip traffic profile for a slot](dc-slot-traffic-writeup.md)
and [the peer-count fix](dc-slot-traffic-followup.md). Every run here has
[stream-Snappy](https://ethresear.ch/t/snappy-with-a-memory-40-less-gossip-traffic/26078/2) and the fixed
peer graph, and residential nodes subscribe to **2 attestation subnets**. The epoch is shortened step
by step, **8 → 4 → 3 → 2 slots**, to find where a 50 / 25 Mbit/s node stops coping.

Note: this note was mostly composed by Claude.

## TL;DR

- **The ceiling is a 3-slot epoch.** 8, 4 and 3 slots fit; 2 does not.
- **8 and 4 slots fit with room to spare.** The home link clears the bulk-data burst by ~4 s, and
  every vote wave after it has p99 under 570 ms.
- **3 slots fits without margin.** FFG p99 is 1068 ms, the worst wave 1.5 s, and the late aggregates
  reach 800–900 ms. But the link still drains between the waves, and from 6 s on the vote tail is back at
  380–510 ms.
- **2 slots does not fit.** The home link stays saturated until 5.5 s, then reaches its ceiling
  on every later wave. Every wave's p99 stays above 1.2 s, and the last waves are not fully
  delivered on any tier.
- **The tail is set by the network, not by the receiving node.** At 2 slots the 10 Gbit/s pools
  have an FFG p99 of 1753 ms, against 1943 ms at the home tier.

## Result

| | slots per epoch | attestation subnets |
|---|---|---|
| ✅ | 8 | 2 |
| ✅ | 4 | 2 |
| ✅ | 3 | 2 |
| ❌ | 2 | 2 |

The criteria are the write-up's: the link should not stay saturated, and the vote tail should not
run away from the median, judged on the worst of the twelve waves.

Home tier (50 / 25 Mbit/s), measured slot:

| slots per epoch | 8 | 4 | 3 | 2 |
|---|---|---|---|---|
| votes per slot | 125 k | 250 k | 333 k | 500 k |
| inbound UDP | 29.0 MB | 37.8 MB | 43.8 MB | 54.5 MB |
| 100 ms buckets ≥ 95% of the link | 15 | 21 | 31 | 47 |
| FFG p50 / p99, ms | 207 / 628 | 216 / 779 | 224 / 1068 | 251 / 1943 |
| FFG worst wave p99, ms | 938 | 1146 | 1529 | 3016 |
| aggregates p99, ms | 397 | 466 | 697 | 1001 |
| aggregates worst wave p99, ms | 407 | 488 | 906 | 1106 |
| payload chunks p99, ms | 1721 | 1954 | 2069 | 2348 |
| blob columns p99, ms | 1944 | 1668 | 1645 | 1851 |

### The home node's slot

Inbound throughput per node, averaged over the 100 nodes of the 50 / 25 Mbit/s group, in 100 ms
buckets across the measured slot. All panels are on one y scale.

**8-slot epoch.**

![8-slot epoch](docs/dc-slot-traffic/snappy-8slots-2subnets.png)

**4-slot epoch.**

![4-slot epoch](docs/dc-slot-traffic/snappy-4slots-2subnets.png)

**3-slot epoch.**

![3-slot epoch](docs/dc-slot-traffic/snappy-3slots-2subnets.png)

**2-slot epoch.**

![2-slot epoch](docs/dc-slot-traffic/snappy-2slots-2subnets.png)

All four panels carry the same bulk data, and the link is pinned from 1.2 s to ~3 s in every one of
them. What changes with the epoch length is how much vote traffic lands on top of it and after it.

- **8 and 4 slots** clear the burst by ~4 s. The waves after it peak at 30–45 Mbit/s and the link
  drains between them.
- **3 slots** clears it by ~4.5 s. The later waves touch the 50 Mbit/s line at their peaks but still
  drain to ~10 Mbit/s in between, so each wave starts on an empty queue.
- **2 slots** never drains. The link stays pinned until ~5.5 s, and from 6 s on every vote and
  aggregate wave reaches the ceiling, with only 15–30 Mbit/s between them. Each wave queues behind
  the last.

## Per-wave latency

FFG votes, home tier, p50 / p99 in ms:

| wave @ | 8 slots | 4 slots | 3 slots | 2 slots |
|---|---|---|---|---|
| 0 s | 191 / 353 | 197 / 364 | 210 / 422 | 227 / 775 |
| 1 s | 208 / 800 | 232 / 1146 | 252 / 1161 | 291 / 1770 |
| 2 s | 425 / 938 | 463 / 1059 | 485 / **1529** | 592 / **3016** |
| 3 s | 240 / 551 | 267 / 999 | 319 / 1389 | 466 / 2513 |
| 4 s | 194 / 345 | 206 / 567 | 217 / 1152 | 271 / 1947 |
| 5 s | 191 / 340 | 198 / 352 | 206 / 707 | 226 / 1668 |
| 6 s | 191 / 339 | 195 / 357 | 204 / 416 | 222 / 1599 |
| 7 s | 191 / 340 | 193 / 343 | 202 / 379 | 217 / 1318 |
| 8 s | 190 / 340 | 194 / 355 | 201 / 405 | 216 / 1349 |
| 9 s | 191 / 345 | 197 / 380 | 205 / 431 | 219 / 1212 |
| 10 s | 191 / 346 | 197 / 388 | 206 / 486 | 221 / 1781 |
| 11 s | 191 / 348 | 199 / 402 | 207 / 507 | 223 / 1634 |

Aggregates, home tier, p50 / p99 in ms:

| wave @ | 8 slots | 4 slots | 3 slots | 2 slots |
|---|---|---|---|---|
| 6 s | 225 / 382 | 235 / 407 | 250 / 459 | 296 / 566 |
| 8 s | 224 / 379 | 235 / 405 | 245 / 451 | 293 / 559 |
| 9 s | 234 / 398 | 248 / 460 | 264 / 527 | 324 / 782 |
| 10 s | 234 / 399 | 253 / 479 | 280 / 906 | 344 / 1106 |
| 11 s | 234 / 407 | 252 / 488 | 282 / 784 | 348 / 1070 |

In every configuration the worst vote waves are the ones published into the bulk-data window at
1–3 s. What separates them is the recovery.

- **8 and 4 slots** are back at 340–400 ms p99 by the 4–5 s wave, and aggregates stay flat across the
  slot.
- **3 slots** takes one wave longer to recover, and from 6 s on its tail sits at 380–510 ms. Its late
  aggregates reach 800–900 ms. This is the last configuration that passes the criteria, and it does
  so without margin.
- **2 slots** never recovers: every wave from 1 s to 11 s has p99 above 1.2 s. When the run ends, a
  few hundredths of a percent of the deliveries for the 9–11 s waves are still missing on every
  tier. Even at 101 B per vote, 500 000 votes per slot is more than the network carries in time.

## The tail belongs to the network

FFG p99 by tier:

| FFG p99, ms | `home` 50/25 | `mid-home` | `upper-home` | `vps` 500M | `validator-pools` 10G |
|---|---|---|---|---|---|
| 8 slots | 628 | 388 | 375 | 375 | 286 |
| 4 slots | 779 | 510 | 517 | 488 | 373 |
| 3 slots | 1068 | 791 | 840 | 785 | 741 |
| 2 slots | 1943 | 1816 | 1895 | 1812 | **1753** |

At 8 and 4 slots the home tier's tail is clearly its own, about 2.1–2.2× that of the pools. From 3
slots on, the tiers converge. At 2 slots the pools see nearly the home tier's tail on a link 200×
wider. The votes are stuck in the uplinks of the nodes that publish and relay them, not in the
receiver's downlink. A pool node receives 407 MB in the measured slot at 8 slots, 753 MB at 4,
979 MB at 3 and 1434 MB at 2.

## How it is modelled

- **Stream-Snappy is applied to sizes, not run.** Votes go on the wire at 42% of their size
  (240 → 101 B) and aggregates at 63%. These are the 64 KiB-history figures measured on a mainnet
  recording in the ethresear.ch post. Blocks, payload and blob columns are left as they are; they
  barely compress (93% / 96%).
- **Topic strings are not compressed.** The stream would shrink the 47-byte attestation topic to a
  few-byte back-reference. Here it stays at full size in every copy, so the vote figures are
  slightly pessimistic.
- **The warm-up slot is short**, as in the write-up: it publishes only from 8 s, so it carries votes
  and aggregates but no bulk data. A full warm-up slot, checked on the 4-slot epoch with 1 subnet,
  gives the home tier FFG p99 of 724 ms against 822 ms, the same aggregates (411 vs 413 ms), and blob
  columns of 1391 ms against 1733 ms. The short warm-up therefore reads somewhat pessimistic,
  mostly on blobs. Raising QUIC's initial congestion window instead does not reproduce a full
  warm-up. With 32 packets the home links never shed their window (38 KB against 15 KB warm), and
  FFG p99 came out worse than with either warm-up.

## Run parameters

- 1M validators; 1000 nodes in five groups: 100 pools with 200 peers, 600 VPS, and 100 each of
  upper-, mid- and 50 / 25 Mbit/s home, with 100 peers each. Fixed peer graph.
- Residential tiers subscribe to **2 of 64** attestation subnets. VPS nodes subscribe to 4, pools to all.
- 2 slots of 12 s; the warm-up slot publishes from 8 s; slot 2 measured.
- eth gossip: D=8, DLow=6, DHigh=12, DLazy=6, heartbeat 700 ms; disciplined IWANT on the home tier.
- Block 8 KiB; payload 64 × 16 KiB; blobs 128 × 42 KiB (21 blobs); mainnet-length topic names.
- FFG: 12 waves, one per second, of 1M / slots-per-epoch / 12 votes, 101 B each.
- Aggregates: 1024 per slot, published at 6, 8, 9, 10 and 11 s. The bitvector covers one slot's
  share of a subnet, so it grows as the epoch shortens:

  | slots per epoch | votes per wave | aggregate, raw | aggregate, on the wire |
  |---|---|---|---|
  | 8 | 10 416 | 484 B | 305 B |
  | 4 | 20 833 | 728 B | 459 B |
  | 3 | 27 777 | 891 B | 561 B |
  | 2 | 41 666 | 1216 B | 766 B |

- `-Ddc.snappyStream=true -Ddc.homeFfgSubnets=2 -Ddc.ffgSlots=N` in
  `DcScenarioRunnerTest.large network`.
