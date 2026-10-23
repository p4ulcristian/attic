# The tests run against a real S3 API (SeaweedFS) and a real Postgres

**Decision:** attic's bucket tests run against SeaweedFS's S3 API, in a container, with credentials it checks, and a throwaway Postgres; `ATTIC_TEST_S3_ENDPOINT`, `ATTIC_TEST_S3_ACCESS_KEY`, `ATTIC_TEST_S3_SECRET_KEY`, `ATTIC_TEST_PG_PORT` and `ATTIC_TEST_PG_USER` say where, and without them those tests say they were skipped. They prove: the key from the content, the same bytes stored once, the row, an object whose row was rolled back found again, a wrong kind refused, a signed link that reads, a changed one and an expired one refused (403 from the server, which checks the signature and the expiry), an upload storing, refusing by kind and size, refusing too many parts and a body that isn't multipart, `:may-upload?` asked before the body is read, and an unreachable bucket failing with no row. The kinds, the settings and the links' shape run without either. Since v0.1.0.

**Why:** a fake S3 in the test would check what attic's author thinks S3 does: a signature it doesn't verify proves nothing. MinIO, the usual choice, publishes no images or binaries any more (its download answers 410); SeaweedFS does, and verifies SigV4 signatures, presigned expiry and `If-None-Match`.

**The price:** the tests need a container runtime for the bucket. SeaweedFS is not R2: Iron Rainbow's live checks against R2 stand for R2's side.

**Instead of:**
- an in-process fake answering PUT, GET and HEAD: fast, and blind to signatures and expiry
- MinIO built from source: a Go build to run a test
