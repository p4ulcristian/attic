# Uploads go through the server; the app says who may upload

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

**Decision:** an upload is a multipart POST to the app's server, one part per file, answered by `make-upload-handler`'s Ring handler; the browser never writes to the bucket. The handler:

1. asks the app's `:may-upload?`, `(fn [request] true-or-false)`, **before reading the body**: no is 403, and nothing of the body is read;
2. reads the parts (ring's multipart parser), each file at most `:max-file-bytes`, the rest of a larger one read and dropped; more parts than `:max-files` refuse the whole request, 413;
3. tells each file's kind by its bytes and stores the allowed ones, every row in one transaction on the app's pool;
4. answers, as JSON by the app's wire, the stored files (field, name, key, sha256, size, content type) and the rejected parts with their reason: `wrong-kind`, `too-large`, `not-a-file` (a form field). A bucket or database failure is 503, written to the log, no row kept.

attic offers the handler; the app (house) puts it at its own path and writes `:may-upload?`. house's asks doorman: the request's Origin is the app's and its session's asker is one the app's own `(fn [asker] …)` lets upload (house's decision). Since v0.1.0.

**Why:** through the server, every file is checked by its bytes before it is stored, and named by its content; a presigned upload would let the browser put any bytes under a key the server must trust. Asking first means a stranger can't make the server read and parse a large body. A file too large is one file refused, not the upload: the others are stored and the answer says which. Who may upload is the app's (a signed-in person, a shop's staff): attic knows no sessions.

**The price:** every byte crosses the server, and the server's own body limit (mailroom's `:max-body-bytes`, http-kit's) must be large enough for an upload's parts together; http-kit holds the body in memory. An upload is not a message: no rate limit, no run-once key; the same file uploaded twice is stored once anyway.

**Instead of:**
- presigned PUT links for the browser: no server cost, but no check of the bytes before they are stored, and keys the browser chose
- the upload as a mailroom message (base64 in JSON): a third larger, and every byte through the JSON parser
- `:may-upload?` answering after the body is read: the server reads a stranger's 100 MB to refuse it
- one large file refusing the whole upload (ring's own limit): the photos beside it lost for one
