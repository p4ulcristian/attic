# Every value that sets behaviour is a setting; no hidden defaults

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

**Decision:** as in every manor library, every value that sets how attic behaves is written out by the app, checked by malli before anything runs, the maps closed, a missing or unknown key refused, named, with every other problem at once; no function fills in an argument.

- **`make-attic`'s settings**, `settings-shape`: `:endpoint :bucket :access-key :secret-key :region :public-url :link-expiry-seconds :connect-timeout-ms :request-timeout-ms :retries {:max-retries :first-wait-ms :max-wait-ms}` (`:attic/bad-settings`); its options `{:log :meter}` (`:attic/bad-options`).
- **`store-file!`'s options**: `:key-prefix :allowed-content-types :file-name`, nil written out for no name.
- **`make-upload-handler`'s settings**, `upload-settings-shape`: `:key-prefix :allowed-content-types :max-file-bytes :max-files`; its options `:pool :wire :may-upload?`.
- **`list-files`' options**: `:key-prefix` (`""` for all), `:after-key` (nil from the first), `:limit`.

**What stays fixed, and why:**

- **The key's form** and **`If-None-Match: *`**: what makes a file's name its content ([the decision](2026-10-23-a-file-is-named-by-its-content.md)).
- **The kinds attic tells and how**: a new kind is code, not a setting ([the decision](2026-10-23-a-files-kind-is-told-by-its-bytes.md)).
- **Path-style requests and links, SigV4, `UNSIGNED-PAYLOAD`, redirects never followed**: what an S3 API takes from every client.
- **The upload's answer, its status codes, its texts and `no-store`**: what attic says, read by the app's browser code.
- **The multipart parts read as UTF-8**: the form fields are refused, not read.

**Why:** a value left to a default is a value the app doesn't show ("Every value that sets behaviour is a setting", mailroom).

**Instead of:**
- aws-api's defaults (10 s to connect, no answer timeout, its backoff): invisible, and the second one is no limit at all
- a default expiry (Iron Rainbow's 300 s): how long a leaked link lives is the app's to say
