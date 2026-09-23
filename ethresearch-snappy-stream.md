# Streaming compression for gossipsub: up to x2 less traffic 

Today every gossip message is compressed with Snappy on its own. Compressing the whole gossip stream instead, keeping the context across messages, also catches the redundancy between them. For a node subscribed to all attestation subnets this cuts inbound gossip traffic by up to 2x.

## TL;DR

- **Keeping context across messages is what gains.** Plain LZ77 with a 64 KiB per-stream history, in the unmodified Snappy block format, receives:
  - **73% of today's bytes on a default node** (zstd with a per-stream context: 67%);
  - **63% on an all-subnets node** (zstd: 51%);
  - **42% for attestations and 63% for aggregates** in both cases.
- **It also addresses two known inefficiencies without dedicated protocol changes:**
  - the topic string sent with every message shrinks to a few-byte back-reference;
  - the `AttestationData` repeated across a slot's attestations and aggregates is effectively sent once per stream and then referenced. What is left of an attestation (~117 bytes) is essentially its signature and indices.
- **The decoder** is today's Snappy decoder plus a history buffer, with the same decompression-bomb bound.

## Measurement

**Recording.**
- A Netty handler in Teku's gossip pipeline, right after libp2p's protobuf codec, dumped every RPC frame with timestamps and stream ids.
- The node ran on mainnet with `--p2p-subscribe-all-subnets-enabled` and about 45 peers, for ~95 minutes. No records were dropped.
- The analysis skips the first 5 minutes, uses the next 20 only to train dictionaries, and evaluates on the following **60 minutes**.

**Default-node profile.** From the all-subnets recording we keep only what a node subscribed to 2 attestation subnets (and no sync committee subnets) would have received over the same connections

**Inbound traffic per hour.**

| Category | Default node (2 subnets), MiB | Share | All subnets, MiB | Share |
|---|---|---|---|---|
| Attestations | 107 | 18% | 3188 | 57% |
| Aggregates | 138 | 23% | 138 | 2.5% |
| Blocks | 154 | 25% | 154 | 2.8% |
| Data column sidecars (custody subnets) | 109 | 18% | 109 | 1.9% |
| Control (IHAVE/IWANT/IDONTWANT/GRAFT/PRUNE) | 92 | 15% | 1946 | 35% |
| Sync committee + contributions | 7.5 | 1.2% | 45 | 0.8% |
| **Total** | **609** | | **5601** | |


## Results (inbound, relative to today's wire bytes)

| Scenario                                                        | Default node total | All-subnets total | Attestation | Aggregate | Block | Control (default / all) |
|-----------------------------------------------------------------|---|---|---|---|---|---|
| Today: Snappy per message                                       | 100% | 100% | 100% | 100% | 100% | 100% / 100% |
| No compression at all                                           | 138% | 107% | 105% | 106% | 237% | 100% / 100% |
| zstd-3 stream per peer stream (2 MiB window) (For comparisson)  | **67.4%** | **51.2%** | 40% | 62% | 76–79% | 60% / 64% |
| Plain LZ77, Snappy format, 4 KiB history                        | 76.6% | 65.0% | 42% | 64% | 94% | 82% / 98% |
| **Plain LZ77, Snappy format, 64 KiB history**                   | **72.8%** | **63.1%** | **42%** | 63% | 93% | 62% / 93% |
| Plain LZ77, Snappy format, 1 MiB history                        | 71.1% | 54.3% | 42–43% | 64% | 85–88% | 64% / 68% |

Data column sidecars stay at ~96% under every per-stream compressor.

## Live check: three Teku nodes on mainnet

We also ran the prototype between real nodes on mainnet, all subnets subscribed.
- **Topology:** a hub node (supernode) connected to the network. Nodes 2 and 3 are connected only to the hub, as direct peers.
- **Node 2** receives plain gossip. **Node 3** receives `/snappy-stream` with raw SSZ payloads.
- **Window:** 40 minutes, after a 5-minute warm-up, with both nodes following the head.

| Inbound gossip stream, 40 min | Node 2 (plain) | Node 3 (`/snappy-stream`) |
|---|---|---|
| Bytes on the wire | 1349.6 MB | **594.7 MB (44.1%)** |
| Same data decoded | 1349.6 MB | 1441.0 MB (raw SSZ is 6.8% larger than Snappy) |
| Messages: attestations / aggregates / blocks / columns / sync committee | 4 817 407 / 18 962 / 199 / 1 358 / 80 046 | identical |

**What it confirms.**
- **Both nodes saw the same stream:** message counts match exactly in every category.
- **Node 3 worked normally:** it followed the head, and messages validated after re-compressing to Snappy on receipt. There were no errors and no lost records.
- **The result matches the offline estimate.** 95% of this stream is attestations, one copy of each; offline, attestations came out at 42% with a 64 KiB history.

**What it doesn't show.**
- **No control traffic.** The hub sends direct peers no IHAVE or GRAFT, so the window has no IHAVE, IWANT or IDONTWANT at all. On an all-subnets node control is 35% of inbound bytes and compresses poorly at 64 KiB (93%). On a real mesh stream we expect the total to land close to the offline 63%, not at 44%.

### Snappy-compressed payloads inside the stream

**A second run: payloads stay Snappy-compressed inside the stream,** exactly as on the network today, with no transcoding on either side. Same topology; 10 minutes after a 2-minute warm-up.

| Payloads inside `/snappy-stream` | Stream bytes before compression | On the wire | Share |
|---|---|---|---|
| Snappy (as today, no transcoding) | 324.9 MB | 140.2 MB | **43.1%** |
| Raw SSZ (first run) | 1349.6 MB (node 2's wire bytes) | 594.7 MB | 44.1% |

**On this traffic, un-Snappying the payloads first buys nothing.**
- The stream is 95% attestations, and Snappy leaves an attestation almost verbatim: its `AttestationData` and the topic string pass through Snappy nearly unchanged. The stream compressor finds those repeats either way.
- This means the payload encoding (`ssz_snappy`) and message handling can stay exactly as they are; only the stream is compressed.
- Blocks and data columns would compress better from raw SSZ, but here they are ~3% of the bytes. On a default node, where they are a much larger share, raw payloads may still be worth it.

### CPU

The prototype codec counts time with `System.nanoTime()` around every block, on the event loop thread. The numbers below cover 540 s of the second run.

| | Blocks | Bytes | Time | Per block | Per byte (throughput) | Share of one core |
|---|---|---|---|---|---|---|
| **Compression** (hub → node 3) | 148 837 | 290.6 → 125.0 MB | 2.358 s | 15.8 µs | 7.74 ns (129 MB/s) | 0.44% |
| **Decompression** (node 3) | 149 262 | 125.4 → 291.4 MB | 0.229 s | 1.5 µs | 0.75 ns (1.3 GB/s) | 0.04% |

- **Decompression is essentially free,** on par with plain Snappy.
- **Compression is 10× slower than decompression, and that is our simple Kotlin encoder:** it hashes every position. An optimized Snappy-style encoder in C or Rust runs at 250–500 MB/s, 2–4× faster.
- **A block averages ~2 KB,** i.e. almost always one message per write.
- **Rough estimate for a real node:** one peer's ~0.54 MB/s stream costs 0.44% of a core. Compressing a separate stream for each of ~8 mesh peers is on the order of 3–4% of one core with this encoder.

## Proposal: `/snappy-stream`



## Costs and trade-offs

- **CPU.** Every message is encoded and decoded separately for each peer; today one Snappy blob is forwarded as is to all mesh peers. The measurement already includes this, since contexts are per stream. Offline, compressing one hour of a default node's inbound traffic took 7 CPU-seconds with zstd-3 and ~10–13 s with our unoptimized Kotlin LZ77. Live, our encoder ran at 129 MB/s and the decoder at 1.3 GB/s (see [CPU](#cpu)).
- **Memory.** It is 64 KiB of history per direction per peer, plus encoder hash tables. That is fixed and known up front: a few MB per node for ~100 peers.

