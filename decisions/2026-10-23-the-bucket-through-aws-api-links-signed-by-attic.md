# The bucket through aws-api; signed links written by attic

**Decision:** attic talks to the bucket through cognitect's aws-api (`:s3`), path-style at the settings' `:endpoint`, signed by `:access-key` and `:secret-key` for `:region`. Two things aws-api leaves out are attic's:

- **Timeouts.** aws-api's own http client connects within a fixed 10 s and waits for an answer forever. attic hands it its own: java.net.http's, with the settings' `:connect-timeout-ms`, and every request with `:request-timeout-ms`, sent through aws-api's java.net.http `submit`. A request the bucket was too busy for (aws-api's rule) or that never connected (nothing was sent; Iron Rainbow's rule) is sent again, `:retries` `{:max-retries :first-wait-ms :max-wait-ms}`, doubling. The body is always bytes, so sending it again is safe.
- **Signed links.** aws-api signs requests but makes no presigned links. `make-signed-link` writes SigV4's query-string signature itself, as Iron Rainbow's code does: a GET of one object on the S3 endpoint, valid for `:link-expiry-seconds` (1 to 604800, S3's longest). Whoever holds it reads that file; a private bucket stays private. A bucket that browsers may read has `:public-url` (a public bucket or a CDN), and `make-public-link`; nil says there is none, and the function throws.

**The region:** aws-api finds an endpoint for the region in its own table even when the endpoint is given, and has none for R2's `"auto"`: every request fails "No known endpoint.". The setting is one region for both the requests and the links, one aws-api knows; R2 checks no region, so an R2 app writes `"us-east-1"` (Iron Rainbow's client does). `make-attic` refuses a region aws-api doesn't know, at the start, rather than at the first upload. Since v0.1.0.

**Why:** Iron Rainbow runs on aws-api against R2; its signed links were checked against live R2 (a link reads, a changed one is refused, an expired one is refused). A request with no timeout holds a request thread forever when the bucket stalls.

**The price:** attic's http client uses aws-api's `cognitect.aws.http` protocol and its java.net.http `submit`, not only `client.api`: an aws-api release that changes them changes attic. The region an R2 app writes isn't R2's own word.

**Instead of:**
- AWS SDK v2: it has a presigner and timeouts, and is a far larger dependency than the three operations attic uses
- `"auto"` with a region aws-api knows put in underneath: the requests would be signed for a region the settings don't say
- the default client and no timeout: a stalled bucket holds every upload
