# Telemetry observability contract (2026-11-24)

Fields added so CFG changes can be verified with real traffic instead of URLTest
health alone. They are additive: older app versions keep working, and CFG must
ignore unknown fields. Privacy: no domains, URLs, subscription content or node
addresses are added.

## `conn_minute` rows: `chain`

Each row may carry `chain`, a bounded `>`-joined list of the connection's
outbound tags (group and node), for example `Google>M-日本JP-HY2`. The app reads
`Connection.chain()` once on a NEW event and caches it; it never reads
`Connection.domain`. At most four tags are kept and the string is truncated.

CFG resolves the group by matching tags against the subscription's group names.
It must keep rows without `chain` (older app versions) and fall back to
`outbound`/`entry`.

## Batch metadata: `profile_rev`

Every upload batch may carry `profile_rev`, the first 12 hex characters of the
SHA-256 of the active profile file (after HTTP decoding). The same value is
included in `service_start` and `profile_update_ok` as `rev`. CFG reports it as
client-reported for now; matching it to a published subscription revision is a
follow-up because the Worker builds the body per request. `custom`/manual edits
will not match a published revision and must be reported as such.

## `dns_cache_miss` client event

Aggregated count of core `router: missing fakeip record` lines:

```json
{"type":"dns_cache_miss","ts":1795449000000,"count":134,"window_s":60,
 "after_network_change":true,"vpn_session_id":"..."}
```

- Recorded at most once per minute, when the collector batch contains at least
  3 misses; the activity log coalesces into five-minute buckets and marks the
  bucket as an issue when the merged count reaches 10.
- `after_network_change` is true when a network change was recorded within the
  previous 30 minutes.
- No domain, fake IP or URL is included.

## `group_snapshot` client event

Every fifteen minutes while the service is attached:

```json
{"type":"group_snapshot","ts":1795449000000,"rows":[
  {"group":"Google","selected":"M-日本JP-HY2","available":13,"total":27,
   "selected_ms":120,"fastest_ms":88}]}
```

- URLTest groups only; no per-node list.
- `available`/`total` come from a full item scan; `selected_ms`/`fastest_ms` may
  be omitted when unknown.

## CFG reports

- `cfg clientlog report --device <id>` (or per-device sections) must never mix
  devices; the `device` UUID is already on every batch.
- `cfg clientlog compare --before <window> --after <window>` compares the same
  metrics per group/device and prints sample sizes and coverage gaps; it must
  refuse a verdict when the sample is too small.
- Real traffic (`conn_minute` by `chain`/group) is the primary metric; URLTest
  failure rate is secondary and must be shown separately.
