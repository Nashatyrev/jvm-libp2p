# Distributed Consensus: Gossip traffic profile for a slot

Fast-finality designs move a lot more attestation and aggregate traffic than today's network, and
that traffic is not spread evenly: a slot has a bulk-data burst at its start and a dozen attestation
waves on top of it. This write-up shows what a slot actually looks like on the wire — how the bytes
are distributed in time, how they split per message type and per class of node, and whether
attestations and aggregates make their deadlines on a home-grade link.

**The subject is the weakest node in the network.** The network is a mix of tiers, from 10 Gbit/s
staking operators down to a 50 / 25 Mbit/s home connection, and the question that decides whether a
parameter set is viable is not what the average node sees — it is whether the *worst-provisioned*
node still receives everything in time. That node has the narrowest link, and it is also the one
whose failure is least acceptable: home stakers are the population the protocol is supposed to keep
in. So the profile below is read on the `home` tier first, and everything better provisioned is
expected to have an easier time of it — it carries the same messages over a link that is 2× to
200× wider, which the comparison further down confirms for bulk data and, interestingly, does not
confirm for the attestation tail. The pool chart is included only as the other extreme, to show what
changes when a node subscribes to everything. One condition on all of this is load-bearing and is
spelled out in [its own section](#the-load-bearing-assumption-weak-nodes-are-a-minority): the weak
nodes have to be a *minority* surrounded by stronger peers. A network made entirely of them is a
different network, and a much slower one.

Everything below comes from the `large network` scenario in
[`DcScenarioRunnerTest`](examples/dc/src/test/kotlin/playground/DcScenarioRunnerTest.kt), run on
the simulator in `examples/dc`.

## Experiment parameters

### Simulator

The nodes are real: unmodified jvm-libp2p gossipsub with its real Netty pipelines and a simulated
QUIC transport. What is simulated is time and the network underneath:

- **1000 nodes**, each behind a modelled access link with separate download and upload capacity.
- **Six regions** (US-East, US-West, Europe, Asia, South America, Africa) with a fixed RTT matrix —
  15–30 ms intra-region, up to 250 ms between the far pairs.
- **Queueing**: `SHADOW_LIKE` — FIFO shaping on a node's egress link, CoDel on the router→host
  ingress link, so a saturated downlink drops rather than buffers without bound.
- **Peer graph**: 100 peers per node (200 for the pools), built so every node has at least 8 peers
  in every subnet it subscribes to.
- **Measurement**: every simulated UDP datagram is folded into 100 ms buckets per node; gossip RPCs
  are counted after decoding, split by message type, by unique-vs-duplicate, and with a control
  breakdown (IHAVE / IWANT / IDONTWANT / GRAFT / PRUNE / subscriptions).

### Node population

1M validators, 1000 validators per node, five groups:

| group | nodes | link (down/up) | peers | DA columns | attestation subnets |
|---|---|---|---|---|---|
| `validator-pools` | 100 | 10 Gbit/s symmetric | 200 | all 128 | all 64 |
| `vps` | 600 | 500 Mbit/s symmetric | 100 | 16 of 128 | 4 of 64 |
| `upper-home` | 100 | 200 / 100 Mbit/s | 100 | 8 of 128 | 1 of 64 |
| `mid-home` | 100 | 100 / 50 Mbit/s | 100 | 8 of 128 | 1 of 64 |
| `home` | 100 | **50 / 25 Mbit/s** | 100 | 8 of 128 | 1 of 64 |

The `home` tier is the EIP-7870 "Attester" floor — 50 Mbit/s down, 25 Mbit/s up — and it is the
group everything below is read against, for the reason given above: it is the tier that decides
whether the configuration works. `mid-home` and `upper-home` are the same node with 2× and 4× the
link, and they are there to separate what a wider link fixes from what it does not.

The pools are the only publishers of blocks, payload chunks and blob columns; attestations and
aggregates are published by validators drawn from every group.

### The load-bearing assumption: weak nodes are a minority

Everything in this write-up holds **only because the 50 / 25 Mbit/s nodes are 10% of the network
and are surrounded by much better-provisioned peers.** This is a condition on the network, not a
detail of the scenario, and it is worth stating plainly because the conclusions do not survive
without it.

Gossip forwarding is byte-symmetric: a node receives each message it subscribes to about `mesh`
times and forwards it about `mesh − 1` times, so upload and download are nearly the same quantity.
This run measures exactly that — a home node sends 72.3 MB and receives 75.6 MB, a ratio of 0.96.
But the attester link is 2:1 asymmetric, so a home node has only half the upload it would need to
carry its own weight. **It is a net sink**, and a network can only absorb sinks while they are a
minority: here, a home node's ~100 peers are drawn overwhelmingly from the 700 nodes on 500 Mbit/s
or more, which can serve it at full rate and can re-forward what it forwards slowly.

**If the whole network were on 50 / 25, the network's effective capacity would be the 25 Mbit/s
uplink, not the 50 Mbit/s downlink** — nobody can deliver faster than their peers can send. The
slot measured here already averages ~35 Mbit/s of inbound raw UDP per home node (52.2 MB over 12 s)
and peaks at 100% of the downlink; there is no arrangement in which every node also *uploads* that
much over a 25 Mbit/s link. The traffic simply does not fit, and the numbers below would not carry
over.

That is not a hypothesis. It was measured in the sibling `median network` scenario, where the only
difference between the two runs is who the home nodes' peers are — both on a *symmetric* 50 / 50
link, i.e. twice the upload the attester tier is specified to have:

| `median network`, 1M validators, 50 / 50 home links | blob p99 | payload p50 | network duplication |
|---|---|---|---|
| 90 home nodes among 10 pools | 2.4 s | 0.98 s | 12.8× |
| **1000 home nodes, no pools at all** | **7.8 s** | **1.88 s** | **39×** |

So the claim this write-up supports is "a minimum-spec node survives a fast-finality slot **in a
mixed network**", not "a network of minimum-spec nodes survives one". The composition of the
population is itself a parameter, and one the protocol specifications say little about: EIP-7870
defines the attester tier as a per-node floor and makes no assumption about how many nodes sit on
it.

### Gossip parameters

Ethereum mainnet values, not jvm-libp2p defaults: **D = 8, D_low = 6, D_high = 12, D_lazy = 6,
heartbeat 700 ms**. Measured mesh size over the run: mean 9.47 per (node, topic).

One deviation: the `home` group runs **disciplined IWANT** (one outstanding request per message id,
200 ms retry, 400 ms broken-promise penalty), while the other groups keep the stock behaviour of
asking every announcer. Without this the 50/25 tier receives 22 copies of every payload chunk
against a mesh of 12, and the slot never fits in the link — see the separate note on pull discipline.

### What a slot publishes

| message | size | per slot | published at | topics |
|---|---|---|---|---|
| block | 8 KiB | 1 | 0 s | global |
| payload chunks | 16 KiB | 64 (1 MiB) | 1 s | 64 subnets |
| blob columns | 42 KiB | 128 (5.25 MiB, = 21 blobs) | 1 s | 128 subnets |
| FFG votes | 240 B | 12 waves × 20 833 = 250 k (60 MB) | 0 s … 11 s, one wave per second | 64 subnets |
| aggregates | 728 B | 1024 (0.75 MB) | 6 s, 8 s, 9 s, 10 s, 11 s | global |

Notes on the two attestation rows, which are the fast-finality-specific part:

- Each validator attests **once per epoch**, and the epoch is spread over **4 slots**, so a slot
  carries a quarter of 1M votes — 250 k votes, published in 12 waves of 20 833, one per second.
- An aggregate covers one slot's share of one subnet: a 488-byte bitvector
  (1M / 64 subnets / 4 slots / 8) plus a 240-byte attestation body = **728 B**. 64 aggregates per
  wave, one per subnet, 16 waves folded into the 5 publication instants above.
- By byte count the slot is **~60 MB of votes against ~6 MB of bulk data** published network-wide —
  the inversion that makes this traffic profile worth looking at at all.

### Run protocol

- **2 slots of 12 s**; slot 0 is warm-up and excluded, all figures describe **slot 1**.
- The warm-up slot publishes only its **last 4 seconds** (`dc.warmupFrom=8`). A measured slot needs
  a predecessor — a cold slot reads ffg p99 at 1.61 s instead of 2.12 s and blob p50 at 787 ms
  instead of 492 ms — but a *full* warm-up slot costs as much wall clock as the measured one and
  changes the result by only a few percent, except for inflating the bulk-data tail the measured
  slot inherits.
- Run on the 512 GB Mac Studio: **24 m 27 s of wall clock for 42 s of simulated time**.
- Delivery in the measured slot is **100% for every message type**; gossip bytes are 89.3% of raw
  UDP bytes, the rest being QUIC and framing overhead.

## Traffic profile

Inbound bytes per node, averaged over the group, in 100 ms buckets across the measured slot. The
stack is decoded gossip, split per message type, with the first copy of a message in the darker
shade and its duplicates in the lighter one. The grey backdrop is raw UDP — the gap between it and
the stack is QUIC and framing overhead.

How close each tier comes to its own downlink, over the measured slot:

| group | link | peak bucket | peak as % of link | buckets ≥ 95% (of 120) |
|---|---|---|---|---|
| `validator-pools` | 10 Gbit/s | 27.5 MiB / 100 ms | 23.0% | 0 |
| `vps` | 500 Mbit/s | 2.8 MiB / 100 ms | 47.4% | 0 |
| `upper-home` | 200 Mbit/s | 1.6 MiB / 100 ms | 67.0% | 0 |
| `mid-home` | 100 Mbit/s | 1.19 MiB / 100 ms | 99.7% | 9 |
| `home` | **50 Mbit/s** | 0.60 MiB / 100 ms | **100%** | **34** |

Utilisation rises monotonically as the link narrows, and only the two home tiers touch their
ceiling — `home` for 3.4 s of the slot, `mid-home` for 0.9 s. Everything above them has headroom to
spare and is not what limits the configuration, which is why the rest of this section reads the
`home` profile in detail and uses the pool profile only as a contrast.

### Home tier (50 / 25 Mbit/s) — the binding case

This is the chart that matters: the weakest node in the network, the one that saturates first and
the one the protocol is meant to keep in.

![Slot traffic profile, home group](docs/dc-slot-traffic/slot-traffic-home.png)

Per home node per slot: **52.2 MB of raw UDP, 46.4 MB of decoded gossip** (88.8%).

| | MB / node / slot | share of gossip bytes |
|---|---|---|
| payload chunks | 12.4 | 26.8% |
| FFG votes | 12.4 | 26.7% |
| aggregates | 7.6 | 16.4% |
| blob columns | 4.1 | 8.8% |
| block | 0.1 | 0.2% |
| control (87.5% IHAVE) | 9.8 | 21.1% |

**Duplicates are 70.4% of all gossip bytes** — mesh size is 11.67 and a node receives roughly that
many copies of everything it subscribes to.

The slot has three distinct phases:

1. **0 – 1 s.** The first attestation wave, the block, and a control burst at the slot boundary
   (~190 KB per 100 ms against a ~40 KB floor mid-slot) as the heartbeat fires.
2. **1 – 4.5 s: the bulk-data window.** Payload chunks and blob columns are published at +1 s and
   the link goes to **100% of its 50 Mbit/s downlink and stays there for 34 of the slot's 120
   buckets**. Blob columns finish first (p99 at 2.9 s), payload chunks last (p99 at 3.4 s) — the
   dotted verticals.
3. **4.5 – 12 s: the attestation sawtooth.** Once the bulk data drains, each of the twelve vote
   waves shows as a blue spike, with the aggregates (gold) joining from 6 s. The link is never
   saturated here.

Two reading notes:

- The stack can rise slightly *above* the grey backdrop and above the link line. That is not
  over-capacity traffic: raw UDP is credited the instant a datagram lands, while a gossip message
  is credited only when its last datagram has arrived and the RPC is decoded. A 43 KB blob column
  is ~36 datagrams, so the stack lags the grey while the queue is deep and repays the debt
  afterwards. Over the whole slot the ratio is a flat 88.8%.
- Buckets 0 – 3 s carry a small amount of wrap-around. The run's completion window is
  11 s + 4 s of settle in a 12 s slot, so the tail of the last waves folds back onto the early
  buckets. It inflates the early bands slightly; it does not affect the per-wave figures below.

### The same node on a wider link

`mid-home` and `upper-home` are the same node as `home` — same 8 DA columns, same single
attestation subnet, same 100 peers — with 2× and 4× the bandwidth. The three charts below are what
happens to the profile when only the link changes; the first one is the home profile again, repeated
here so the three can be read in sequence.

**50 / 25 Mbit/s — `home`** (the chart from above)

![Slot traffic profile, home group](docs/dc-slot-traffic/slot-traffic-home.png)

**100 / 50 Mbit/s — `mid-home`**

![Slot traffic profile, mid-home group](docs/dc-slot-traffic/slot-traffic-mid-home.png)

**200 / 100 Mbit/s — `upper-home`**

![Slot traffic profile, upper-home group](docs/dc-slot-traffic/slot-traffic-upper-home.png)

Each panel has its own y scale and its own red capacity line, so read every one against its own
line rather than against the others.

The bulk-data window is the part that moves. At 50 Mbit/s it is a 3.4-second plateau against the
ceiling; at 100 Mbit/s it is a peak that still just touches the line for 0.9 s; at 200 Mbit/s it is
a single spike that reaches 67% of the link and is over by 3 s, leaving the rest of the slot visibly
empty. What is left there is the attestation sawtooth, which costs the same on every tier.

| home group → | `home` 50/25 | `mid-home` 100/50 | `upper-home` 200/100 |
|---|---|---|---|
| peak use of own link | 100% | 99.7% | 67.0% |
| buckets ≥ 95% (of 120) | 34 | 9 | 0 |
| payload chunk p50 / p99 | 1094 / 2362 ms | 573 / 1143 ms | 461 / 845 ms |
| blob column p50 / p99 | 545 / 1868 ms | 330 / 883 ms | 303 / 729 ms |
| FFG vote p50 / p99 | 234 / 2073 ms | 203 / **1872** ms | 195 / **1950** ms |
| aggregate p50 / p99 | 310 / 971 ms | 269 / **954** ms | 264 / **953** ms |

Two things to take from this:

- **Bulk data scales with the link, as expected.** Doubling the link halves payload-chunk p99, and
  doubling it again takes off another third. A node above the home tier is never in trouble on
  blocks, payload or DA columns.
- **The attestation and aggregate tails do not.** FFG p99 barely moves (2073 → 1872 → 1950 ms) and
  the aggregate p99 is flat to the millisecond (971 → 954 → 953 ms). Those tails are not produced by
  the receiving node's downlink — they are produced by propagation through a mesh whose other
  members are home nodes. **A well-provisioned node cannot buy its way out of them**; they are a
  property of the population, which is the other half of why the weakest tier is the one to design
  against.

### Validator pool (10 Gbit/s, all subnets)

![Slot traffic profile, validator-pools group](docs/dc-slot-traffic/slot-traffic-pools.png)

The same slot seen by a node that subscribes to **all** 128 DA columns and **all** 64 attestation
subnets. Two things change completely:

- **Volume**: **1052 MB of raw UDP per node per slot**, 20× the home tier, of which 945 MB is
  decoded gossip.
- **Composition**: FFG votes are 504 MB (53%) because the node listens to all 64 subnets, and
  **control is 399 MB — 42% of its gossip bytes, 99.2% of it IHAVE** (24.2 M message ids announced
  per node over the run). On the home tier, which listens to one attestation subnet, the control
  column is 9.8 MB. IHAVE volume scales with the number of subnets a node subscribes to, and
  `maxIHaveLength` is applied per topic with no accounting across topics.

Bulk data is a rounding error here: payload chunks and blob columns together are 3.9% of what a
pool node receives, against 35.6% on the home tier.

## Attestation and aggregate waves

Per-wave delivery, home group, measured slot, **100% delivered in every wave**.

### FFG votes — 12 waves, one per second

| wave | published at | p50 | p90 | p99 |
|---|---|---|---|---|
| 0 | 0 s | 214 ms | 365 ms | 853 ms |
| 1 | 1 s | 233 ms | 602 ms | **2820 ms** |
| 2 | 2 s | 620 ms | 1319 ms | **3149 ms** |
| 3 | 3 s | 531 ms | 1105 ms | **2437 ms** |
| 4 | 4 s | 257 ms | 761 ms | 1805 ms |
| 5 | 5 s | 210 ms | 533 ms | 1470 ms |
| 6 | 6 s | 206 ms | 393 ms | 1422 ms |
| 7 | 7 s | 206 ms | 354 ms | 1160 ms |
| 8 | 8 s | 203 ms | 349 ms | 1281 ms |
| 9 | 9 s | 201 ms | 364 ms | 1257 ms |
| 10 | 10 s | 207 ms | 372 ms | 1337 ms |
| 11 | 11 s | 209 ms | 382 ms | 1356 ms |
| **all** | | **234 ms** | **676 ms** | **2073 ms** |

Every wave carries the same 20 833 votes, so the spread is not about volume. **Waves 1–3 are the
ones published into the bulk-data window**, and they pay 2.4–3.1 s at p99 against 1.2–1.4 s for the
waves after it — and 620 ms at p50 for wave 2 against ~205 ms once the link is free. The
attestation tail on a home link is set by the blobs and the payload, not by the attestations.

All twelve waves stay under the 4 s attestation deadline, with ~850 ms of margin in the worst wave.

### Aggregates — 5 publication instants

| wave | published at | aggregates | p50 | p90 | p99 |
|---|---|---|---|---|---|
| 0 | 6 s | 128 | 286 ms | 410 ms | 582 ms |
| 1 | 8 s | 128 | 285 ms | 401 ms | 494 ms |
| 2 | 9 s | 256 | 306 ms | 472 ms | 625 ms |
| 3 | 10 s | 256 | 326 ms | 553 ms | 962 ms |
| 4 | 11 s | 256 | 336 ms | 588 ms | 1181 ms |
| **all** | | **1024** | **310 ms** | **500 ms** | **971 ms** |

Aggregates are published after the bulk data is gone, so they never compete with it — but they
degrade steadily across the slot, 582 ms → 1181 ms at p99, as each later wave queues behind more
accumulated vote traffic. They are also the most expensive message per byte published: 1024
aggregates of 728 B go to a **global** topic, so every node receives all of them, which is why
0.75 MB published becomes 7.6 MB inbound per home node — more than the blob columns.
