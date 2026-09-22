# Indexing progress: live endpoints and demo page

The trackers service publishes what every tracker is doing, as a snapshot and as a live
stream, plus a standalone page that renders it. Nothing here depends on Alfresco: the
service answers on its own port, `:8085`, whether or not a repository is reachable.

The point is the re-index. `SUMMARY` reports a backlog but never a speed, so
`Approx transaction indexing time remaining` was the string `N/A` and nothing more. A
speed is a derivative, and a derivative needs history, which is what this feature adds.

## Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/progress/stream` | SSE. One `progress` event per sampling tick, the first one sent on connection. |
| `GET /api/v1/progress` | The same payload, once, for `curl … \| jq`. |
| `/indexing.html` | The demo page, served from the jar. |

Advertised by `GET /api/v1` as the capability `index.progress`.

```bash
curl -s http://trackers:8085/api/v1/progress | jq
curl -N http://trackers:8085/api/v1/progress/stream
```

## Payload

```json
{
  "generatedAt": "2026-09-22T10:00:00Z",
  "cores": [
    {
      "core": "alfresco",
      "trackers": [
        {
          "tracker": "metadata",
          "active": true,
          "done": 4000,
          "remaining": 250,
          "remainingNodes": 375,
          "peakRemaining": 1000,
          "ratePerSec": 42.5,
          "etaSeconds": 6,
          "trend": "RISING",
          "lastSampleAt": "2026-09-22T09:59:58Z"
        }
      ]
    }
  ]
}
```

A figure that could not be measured is `null`, never `0`: a stalled tracker and a tracker
nobody has timed yet are different states, and a client that reads a missing field as zero
would show an instant ETA for an indexer that is not moving. Field names and the ISO
instant format are a contract consumed outside this repository — `ProgressPayloadContractTest`
guards them.

### What each tracker contributes

| Tracker | `done` | `remaining` | `remainingNodes` |
|---|---|---|---|
| `metadata` | `lastIndexedTxId` | transactions the repository has past that id | `remaining × MeanDocsPerTx` |
| `acl` | `lastIndexedChangeSetId` | change sets past that id | `remaining × MeanAclsPerChangeSet` |
| `content` | nodes whose content is in sync | nodes whose content needs updating | — already nodes |
| `cascade` | — | documents carrying `cascade_flag:1` | — |
| `repair` | — | error nodes pending in `RepairReport` | — |

`cascade` and `repair` have a backlog but no cursor: nothing about them only ever grows.
Their speed is therefore read from the drain of the backlog itself, which is valid only
while it shrinks — a queue being fed faster than it empties reports no speed rather than a
negative one.

### One bar, one meaning

Every bar burns down against `peakRemaining`, the worst backlog seen since the service
started: `1 − remaining / peakRemaining`. It answers "how far through the work in front of
it is this tracker", and it answers it the same way on every row, so two rows showing the
same backlog show the same bar.

The tempting alternative, `done / (done + remaining)`, was worse on two counts. It has no
meaning for `cascade` and `repair`, which have no `done` — so the page would mix two
denominators and invite exactly the comparison it cannot honour. And where it does apply it
answers a different question: 30 000 content nodes in sync out of 30 361 is 98.8 %, a bar
pinned near full at all times. That is a health measure, not a progress one.

Two consequences follow from the peak: a new wave of work raises it and makes the bar
recede, and a restart forgets it, so the first sample after a restart sets the peak to the
current backlog and the bar starts at 0 %.

`remainingNodes` is an estimate: the mean it multiplies drifts over a run, and it is the
mean of transactions already indexed, not of those still queued.

## How the ETA is computed

Throughput is measured on `done` wherever a cursor exists, never on `remaining`. The
backlog is a difference between two moving quantities — it falls as the tracker indexes and
rises as the repository ingests — so its derivative measures neither. `done` only ever
grows, and `etaSeconds = remaining / ratePerSec` then reads correctly during an import: the
speed stays true and the ETA lengthens, which is what is actually happening.

`cascade` and `repair` keep no cursor at all, so the drain of their backlog is the only
thing that can be timed and the fallback is used for them. The choice there is between an
imperfect measure and none.

The rate is the slope of a **least-squares fit over every sample** of a sliding window
(60 s by default, sampled every 2 s), not a delta between the window's two ends. That
distinction is the whole difference between a readable ETA and one that yo-yos: an
end-to-end delta uses two samples out of thirty, so it carries the noise of a two-point
estimate with the latency of a one-minute window, and since the trackers work in cycles
(metadata every 5 s, content every 20 s) those two ends land at arbitrary phases of the
cycle. The fit uses all thirty. No smoothing factor is involved; the window is the only
knob.

Consequences worth knowing:

- a change of pace takes up to one window to show fully;
- an ETA appears only after two samples, so about 4 s after the service starts;
- a purge resets the cursor, the slope goes negative, and the rate is reported `null`
  rather than as negative throughput.

### `trend`

`RISING`, `STEADY`, `FALLING`, or `null` until the window holds at least four samples. The
window is cut in half and the two slopes compared, with a 10 % dead band so that noise does
not read as a change of pace. The page shows it as a coloured arrow beside the rate.

The demo page also rounds the ETA it *displays* — to ten seconds past two minutes, to the
minute past ten — since a figure that large is not accurate to the second anyway and the
last digits are pure flicker. The payload keeps the exact value.

## `active` reports the global tracking flag

`ActivatableTracker.isEnabled` is a **static** field shared by every tracker instance, so
disabling one disables all and `active` is the same value on every row. It answers "is
tracking enabled", not "is this particular tracker enabled". Pre-existing behaviour, not
introduced here.

## Putting nginx in front

SSE dies silently behind a proxy that buffers: the response arrives all at once, when the
connection closes. The stream therefore sends `X-Accel-Buffering: no`, which nginx honours
per response, so an untouched nginx already works. Configure the location as well when you
control it:

```nginx
location /api/v1/progress/stream {
    proxy_pass         http://trackers:8085;
    proxy_http_version 1.1;
    proxy_set_header   Connection "";
    proxy_buffering    off;
    proxy_cache        off;
    proxy_read_timeout 1h;
}
```

`proxy_read_timeout` is not decorative: it defaults to 60 s. Each tick emits an event
whether the figures moved or not, which keeps the connection under that ceiling, but the
explicit value costs nothing.

## Reading the API from another origin

Cross-origin access is off until an origin is named, so the endpoints keep the exposure of
the rest of the admin surface by default. `EventSource` sends no custom header and needs no
preflight, but it is still subject to CORS: without the header the browser reports a bare
connection error.

```yaml
alfresco:
  tracker:
    progress:
      cors-allowed-origins:
        - https://portail.example.org
```

## The demo page

`http://trackers:8085/indexing.html` — one file, no dependency, no CDN, read-only. It reads
`/api/v1/progress/stream` on its own origin, or on another one passed as `?api=`:

```
http://trackers:8085/indexing.html?api=https://search.example.org:8085
```

Below each core's table it plots the indexing speed, one line per tracker on a single axis
— all of them are units per second, so they share a scale and a dip common to several lines
reads as what it is, a pause of the whole tier rather than one tracker slowing down.
Hovering gives a crosshair and every series' value at that instant. Series colours are
assigned by tracker name in a fixed order, never cycled, so a tracker keeps its colour
whichever ones are present; both the light and the dark steps are validated for
colour-vision deficiency against their own surface.

A row of buttons above the cores picks the range: 10 min, 30 min, 1 h, 3 h, 6 h, 12 h, 1 d,
3 d, 6 d. The choice is remembered per viewer in `localStorage`.

**The series lives in the browser's memory, and nowhere else.** The page accumulates the
`ratePerSec` of each event it receives; the service keeps no history and the payload carries
none, which is what keeps an event that repeats every two seconds small. Two consequences
follow, and neither is a defect to report:

- a reload starts from an empty chart, and so does a second viewer;
- a 6-day range shows six days only if the tab has been open that long. Where data has not
  been collected the chart is simply blank, never zero.

Six days at one event every two seconds would be 260 000 points per tracker, so the page
keeps three resolutions and reads the one the range needs: 10 s buckets up to an hour,
1 minute up to a day, 15 minutes beyond. Values falling in the same bucket are averaged.
That is about 2 500 numbers per tracker whatever the range, and a measured heap of roughly
10 MB for two cores fed six days of history.

It is a fallback and a reference client, not the integration target: pristy-portail
consumes `/api/v1/progress/stream` from its own front end.

## Configuration

All under `alfresco.tracker.progress`, defaults in `TrackerProperties.ProgressConfig` —
the packaged `application.yml` deliberately repeats none of them.

| Key | Default | Effect |
|---|---|---|
| `sample-interval-millis` | `2000` | sampling cadence, and therefore the stream's event rate |
| `window-seconds` | `60` | span of the window the rate is measured over |
| `stream-timeout-millis` | `0` | SSE connection timeout; `0` means none |
| `cors-allowed-origins` | empty | origins allowed to read the endpoints |

Sampling runs continuously, not only while someone watches, so the first viewer already
has a full window of history and an ETA. Its cost is three `rows=0` Solr counts per core
per tick — two for the content backlog, one for the cascade one; every other figure is read
from memory.

## Security

`:8085` carries no authentication of its own — in `secureComms=https` it is protected by
mTLS, otherwise by nothing. These endpoints are read-only and expose counters, not content,
but they follow the exposure of the rest of the admin surface. Do not publish the port.
