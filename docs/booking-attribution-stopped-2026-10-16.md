# Performance attribution stopped at the user's request

No further A/B runs are planned. Three complete runs were preserved; B2 was interrupted and its dedicated schema/Gateway cleaned. The formal source hash verification before asynchronous development was `formalMainUnchanged=true`.

| Run | Lecture C50 TPS / P95 ms | 12 rooms C100 TPS / P95 ms |
| --- | --- | --- |
| A1 historical baseline | 58.74 / 1017.75 | 118.28 / 992.17 |
| B1 round-1 exact | 63.12 / 838.70 | 136.57 / 778.99 |
| C1 correctness fixes, original promotion loop | 58.62 / 953.78 | 121.83 / 911.21 |

Only one A/B/C set completed, NOT three alternating rounds: these results cannot establish a stable regression or improvement. The 500 varied-interval fixture contains 165 distinct intervals; its approximately 330 promotion reads are primarily the window/overlap pair for those intervals, not repeated per-member lookups. No study-room refactoring follows this observation.

Raw partial results: `target/booking-attribution-d0ae49bffd/attribution-summary.json`. A separate copy and the pre-async code snapshot are in `../tmp/campusreserve-before-lecture-async-20261016/`. The snapshot contains HEAD archive, binary working-tree patch, untracked/changed files and SHA-256 inventory, excluding ignored secrets/build outputs. All round-1 fixes and batching remain in place.
