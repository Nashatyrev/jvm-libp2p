# Post-quantum attestations: measurements and first simulation

Question: with leanXMSS signatures and leanVM proof aggregation, can attestations published at 3 s
be aggregated and reach every node before the 12 s slot ends, with 16 attestation subnets and
4 aggregators per subnet?

## Signature and aggregation measurements

leanVM `main` at e723a8c (Keccak), CPU only, 2026-11-10. M3 Max: 12P+4E cores, 64 GB, in desktop
use during the run. M3 Ultra: 24P+8E cores, 512 GB, idle.

**Single signature**

| | M3 Max | M3 Ultra |
|---|---|---|
| Signature size | 2,404 B (4 + two 1,200 B layers) | same |
| Public key | 32 B | same |
| Key generation | 0.20 s | 0.12 s |
| Sign, p50 / p95 / max | 4.5 / 19 / 49 ms | 2.1 / 9.1 / 23 ms |
| Verify, p50 | 32 µs | 31 µs |

**Leaf aggregation (raw signatures into one proof)**

| Signatures | Proof size | M3 Max | M3 Ultra | Peak memory |
|---|---|---|---|---|
| 125 | 205.6 KiB | 0.19 s | 0.14 s | 0.9 GiB |
| 250 | 222.1 KiB | 0.30 s | 0.19 s | 1.4 GiB |
| 500 | 230.1 KiB | 0.52 s | 0.33 s | 2.4 GiB |
| 1,000 | 236.8 KiB | 0.96 s | 0.56 s | 4.5 GiB |
| 2,000 (one of 16 subnets) | 245.8 KiB | 1.86 s | 1.03 s | 8.7 GiB |
| 4,000 | 265.1 KiB | 3.94 s | 1.96 s | 17.5 GiB |

**Merges (proofs into one proof)**

| Merge | Proof size | M3 Max | M3 Ultra | Verify |
|---|---|---|---|---|
| 4 × 500 | 239.6 KiB | 0.42 s | 0.27 s | 9.6 ms |
| 2 × 2,000 | 216.6 KiB | 0.33 s | 0.22 s | 9.7 ms |
| 4 × 2,000 | 246.2 KiB | 0.44 s | 0.30 s | 10.5 ms |
| 8 × 2,000 | 254.7 KiB | 0.83 s | 0.51 s | 11.5 ms |
| 16 × 2,000 (final, all subnets) | 264.3 KiB | 1.77 s | 0.93 s | 12.6 ms |

## Simulation: variant A

The 16 subnet aggregates are the end product; every node receives them and the next proposer
merges them.

Setup (`DcPqAttestationRunnerTest`, one cold slot):

- 1,000 nodes, 1M validators: 100 pools (10 Gbit), 600 VPS (500 Mbit), 100 upper home (200/100),
  100 mid home (100/50), 100 home (50/25 Mbit).
- Block at 0 s, 1 MiB payload and 21 blobs at 1 s.
- 31,250 votes (1/32 of validators) of 2,548 B each at 3.0 s, on 16 subnets. Full 2,404 B signature.
- Pools subscribe to all 16 vote subnets, VPS to 2, upper and mid home to 1. The 50/25 home tier
  publishes votes but relays no vote subnet.
- One 260 KiB aggregate per subnet, published by a pool node at 6.5 s. This stands for an
  aggregator that collects until about 5 s and proves in one pass, plus a first-seen rule that
  lets only one of the four aggregators' proofs travel. Proving itself is not simulated.

Arrival times in seconds from slot start, p50 / p99 / max:

| Tier | Votes arrive | Aggregates arrive |
|---|---|---|
| Pools (aggregators) | 3.23 / 3.84 / 4.38 | 6.84 / 7.12 / 7.35 |
| VPS | 3.41 / 4.59 / 5.15 | 6.94 / 7.44 / 7.95 |
| Upper home (200/100) | 3.49 / 4.74 / 5.12 | 7.08 / 7.81 / 8.02 |
| Mid home (100/50) | 3.67 / 5.61 / 6.67 | 7.47 / 9.35 / 10.54 |
| Home (50/25) | not subscribed | 7.72 / 10.14 / 11.05 |

Every node has all 16 aggregates before the slot ends; the slowest at 11.05 s. Aggregators have
every vote by 4.38 s.

What makes it work: without the first-seen rule (64 aggregates travelling) the two lower home tiers
miss the slot, and a 50/25 node that relays a vote subnet also misses it, because votes are
delivered about 9 times each.

## Optimizations not used in this simulation

- **Cache the top layer of the signature.** The top 1,200 B layer stays the same for 2^16
  signatures of a validator, so peers can cache it and only 1,204 B per vote need to travel.
- **Start aggregating during collection.** Prove the votes received so far, then aggregate the
  partial aggregates with the stragglers. This burns more CPU in total but could save up to
  about 1 s of the slot.
