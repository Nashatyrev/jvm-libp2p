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

**Default-node profile.** From the all-subnets recording we keep only what a node subscribed to 2 attestation subnets (and no sync committee subnets) would have received over the same connections:
- publishes, IHAVE, GRAFT and PRUNE are filtered by topic;
- IWANT and IDONTWANT carry only message ids, so they are mapped to topics through the eth2 message id of every recorded message. 99.96% of the ids resolved.

We ran three different subnet pairs, and they agree within 0.5 pp; the tables show subnets {5, 37}. This is an approximation: a real 2-subnet node would have a somewhat different mesh.

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

**Replay.**
- Each payload is first un-Snappied back to raw SSZ, and every frame is rebuilt with raw SSZ inside. Each scenario then compresses from raw data; nothing is compressed on top of Snappy.
- Stream scenarios keep **one compressor per (stream, direction)**, the way a real connection would. They are flushed after every frame, since gossip can't wait for more data.
- The baseline accounting reproduces the recorded wire bytes exactly.
- Every custom-codec result below comes from a verified round trip: each frame is decoded back and compared byte for byte.

## Results (inbound, relative to today's wire bytes)

| Scenario | Default node total | All-subnets total | Attestation | Aggregate | Block | Control (default / all) |
|---|---|---|---|---|---|---|
| Today: Snappy per message | 100% | 100% | 100% | 100% | 100% | 100% / 100% |
| No compression at all | 138% | 107% | 105% | 106% | 237% | 100% / 100% |
| zstd-3 per message | 95.9% | 99.8% | 100% | 97% | 86% | 100% / 100% |
| zstd-3 + 64 KiB dictionary per topic, per message | 94.2% | 98.4% | 96–98% | 93% | 86% | 100% / 100% |
| zstd-3 + 64 KiB dictionary, whole frame | 84.9% | 82.6% | 71–74% | 81% | 83–86% | 94% / 97% |
| **zstd-3 stream per peer stream** (2 MiB window) | **67.4%** | **51.2%** | 40% | 62% | 76–79% | 60% / 64% |
| zstd-3 stream, 32 KiB window | 71.7% | 62.3% | 40–41% | 63% | 90% | 62% / 94% |
| deflate-6 stream (32 KiB) | 71.8% | 62.8% | 41–42% | 64% | 88% | 63% / 94% |
| **Plain LZ77, Snappy format, 64 KiB history** | **72.8%** | **63.1%** | **42%** | 63% | 93% | 62% / 93% |
| same, Snappy-like single hash table encoder | 73.8% | 63.4% | 42% | 64% | 96% | 63% / 94% |
| Plain LZ77, Snappy format, 4 KiB history | 76.6% | 65.0% | 42% | 64% | 94% | 82% / 98% |
| Plain LZ77, Snappy format, 1 MiB history | 71.1% | 54.3% | 42–43% | 64% | 85–88% | 64% / 68% |

Data column sidecars stay at ~96% under every per-stream compressor.

### Observations

1. **Context is what matters, not the algorithm.** No per-message codec gets meaningfully below today's Snappy, except on blocks. Every per-stream codec lands at 40–43% on attestations and 62–64% on aggregates.
2. **Attestations and aggregates need very little history.** 4 KiB already gives the full gain. What is left of an attestation (~117 bytes) is essentially the signature plus indices.
3. **On a default node, 64 KiB is enough for control too (62%).** With only a few topics, the repeated message ids are close together on the stream. On an all-subnets node the same ids are much further apart: control needs 1–2 MiB there (64–68%) and gets almost nothing from 64 KiB (93%).
4. **Entropy coding buys little.** At equal window size, plain LZ77 is within ~1 pp of zstd and deflate. zstd's remaining edge is mostly on blocks.
5. **On a default node, what's left is mostly data that doesn't compress.** After LZ77 at 64 KiB, blocks and data columns make up ~55% of the remaining bytes.
6. **Static dictionaries age quickly.** On a 5-minute test a per-topic dictionary gave 81% on attestations; trained 20 minutes before the evaluation window, it gives 96–98%. The useful dictionary content seems to be the current epoch's checkpoints, which a stream context picks up by itself.
7. **Cross-peer redundancy is duplicate delivery, not compressible structure.** With a context shared across *all* peers (not implementable, since each connection is decoded separately), a default node would receive 39.5% of today's bytes: aggregates 40%, blocks 11%. That is the same message arriving from several peers; IDONTWANT and friends address it, compression shouldn't.

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
- **No duplicates:** every message arrived exactly once.
- **CPU wasn't measured.** Node 3 also runs the codec and re-compresses to Snappy on receipt; the hub decompresses before sending.

## Proposal: `/snappy-stream`

Our constraint is that the decoder must stay as simple and as hardened as what clients already run. Zip bombs and complex parsers (Huffman/FSE tables, large dictionaries) are exactly the attack surface we don't want to grow.

**Format: unchanged Snappy block elements, plus a history.**

```
stream := frame*
frame  := uvarint(len(block)) block          ; len(block) <= 32 + B + B/6
block  := uvarint(n) element*                 ; Snappy block format, n <= B = 64 KiB
```

- A copy element may reach up to 65535 bytes back: into the current block, or into the last 64 KiB decoded on the same stream and direction.
- Snappy's 2-byte-offset copy already reaches 64 KiB, so the wire format itself doesn't change. What changes is the decoder's output buffer: it is prefixed with the stream's history.
- Each direction has its own history, starting empty.
- The sender may cut blocks anywhere. The prototype cuts at every write and at 64 KiB.

**Decoder.** It is today's Snappy decoder with every offset and length checked, plus two rules:
- a block may not declare more than `B` decoded bytes, or decode to anything other than exactly `n` bytes;
- a copy may not reach before the start of the history.

Any violation closes the stream, since the state can't be resynced. The largest possible output of one block is `B`, the same bound as today; memory is fixed at history plus one block per direction. Our decoder is ~60 lines and fuzzed with 200k random and corrupted inputs.

**Encoder.** It is not part of the spec. A Snappy-style single hash table loses only ~1 pp against deeper match search.

**Negotiation.**
- The initiator offers `/snappy-stream/meshsub/1.2.0` first and `/meshsub/1.2.0` right after it in the same multistream-select exchange.
- A peer without support answers `na`, so the extra cost is one round trip; it costs nothing extra for peers that do support it.
- After selection the prefix is stripped, and the protocol runs as usual on the decompressed stream.
- Application protocol ids, message ids and validation are unchanged. Message ids are already computed over decompressed data.

**What changes for gossip.** Payloads go on the stream as raw SSZ, since the stream is compressed anyway. That needs either a new topic encoding (`/ssz` instead of `/ssz_snappy`) or an agreement that `snappy-stream` implies raw payloads.

**Prototype.**
- It is in jvm-libp2p: a `streamCompression` host option, off by default, for muxed TCP streams.
- A two-host gossip test runs over compressed streams, with fallback in both directions.
- Recorder, analyzer and prototype: *[links]*.

## Costs and trade-offs

- **CPU.** Every message is encoded and decoded separately for each peer; today one Snappy blob is forwarded as is to all mesh peers. The measurement already includes this, since contexts are per stream. Compressing one hour of a default node's inbound traffic took 7 CPU-seconds with zstd-3 and ~10–13 s with our unoptimized Kotlin LZ77.
- **Memory.** It is 64 KiB of history per direction per peer, plus encoder hash tables. That is fixed and known up front: a few MB per node for ~100 peers.
- **Latency.** None added: every write is flushed immediately.
- **Layering.** This lives below gossipsub, as a libp2p stream feature. It needs agreement across libp2p implementations (go, rust, nim, js, jvm, …), not only among CL clients.

