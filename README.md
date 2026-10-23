# attic

Files for a Clojure app, in an S3 bucket (R2, S3, any S3 API). Each file
is named by its content: the same bytes are the same key, stored once, and
an object is never written over. Every file stored is a row of
`attic.files`, so the app can list what it has. A browser reads a file
through a signed link that expires, or a public address; an upload goes
through the server, each file checked by its bytes before it is stored.

```clojure
io.github.p4ulcristian/attic {:git/tag "v0.1.4" :git/sha "ee84b16"}
```

Needs [cellar](https://github.com/p4ulcristian/cellar) (the files table),
[wire](https://github.com/p4ulcristian/wire) (the upload's answer),
[logbook](https://github.com/p4ulcristian/logbook) and
[meter](https://github.com/p4ulcristian/meter) (handed in),
[safe](https://github.com/p4ulcristian/safe) (the bucket's key is its
secrets), cognitect's aws-api (S3), ring-core (the multipart parser) and malli. One public
namespace, `attic.api`.

## The api

| name | does |
|---|---|
| `make-attic` `stop-attic!` `settings-shape` | an attic from its settings and `{:log :meter}`, every key required, checked first; declares attic's metrics |
| `files-table` | `attic.files` as a map for cellar, to migrate with the app's tables |
| `store-file!` `known-content-types` | stores bytes under their content's key and records them, on a pool or in a transaction; `{:key-prefix :allowed-content-types :file-name}`; the row |
| `find-file` `list-files` | a file's row by key; the rows by prefix, after a key, at most a limit |
| `read-file-bytes` | a file's bytes from the bucket |
| `delete-file!` `delete-unnamed-files!` | a file's row and object deleted, on a pool, only when no row of the app names it (a reference to `:file/key`): `:deleted`, `:still-named` or `:not-found`; every file under a prefix no row names |
| `delete-file-after-commit!` | `delete-file!` on the pool kept for once the app's transaction commits (cellar's `run-after-commit!`), never after a rollback; nil back, the outcome counted and logged then |
| `make-signed-link` `make-public-link` | a link that reads the file for `:link-expiry-seconds`; its address under `:public-url` |
| `make-upload-handler` `upload-settings-shape` | a Ring handler for a multipart upload, one part per file, after the app's `:may-upload?` |

```clojure
(require '[attic.api :as attic])

(def attic
  (attic/make-attic {:endpoint            "https://<account>.r2.cloudflarestorage.com"
                     :bucket              "shop-files"
                     :access-key          access-key          ; read and write on that bucket
                     :secret-key          secret-key          ; both safe's secrets: #secret :attic/access-key, :attic/secret-key
                     :region              "us-east-1"         ; R2 checks none; aws-api needs one it knows
                     :public-url          nil                 ; private: files are read through signed links
                     :link-expiry-seconds 300
                     :connect-timeout-ms  10000
                     :request-timeout-ms  60000
                     :retries             {:max-retries 4 :first-wait-ms 500 :max-wait-ms 4000}}
                    {:log log :meter meter}))

(cellar/migrate! pool {:tables (conj app-tables attic/files-table) :extensions [] :functions [] :views []}
                 changes migrate-options)

;; an order's invoice, in the transaction that saves the order
(def invoice (attic/store-file! attic transaction pdf-bytes {:key-prefix            "invoices"
                                                             :allowed-content-types #{"application/pdf"}
                                                             :file-name             nil}))
;=> #:file{:key "invoices/3f1c9a2e-77b0-4d1e-9c4a-0b8e1f2a6c3d.pdf" :sha256 "3f1c9a2e…"
;          :size 48213 :content-type "application/pdf" :stored-at #object[…]}

(attic/make-signed-link attic (:file/key invoice))
;=> "https://<account>.r2.cloudflarestorage.com/shop-files/invoices/3f1c….pdf?X-Amz-Algorithm=…&X-Amz-Signature=…"

;; uploads of a shop's photos, at the app's own path
(def upload-photos
  (attic/make-upload-handler attic
                             {:key-prefix            "shops/photos"
                              :allowed-content-types #{"image/png" "image/jpeg" "image/webp"}
                              :max-file-bytes        10000000
                              :max-files             10}
                             {:pool        pool
                              :wire        wire
                              :may-upload? (fn [request] (signed-in? request))}))   ; asked before the body is read
```

```
POST /files/photos   multipart/form-data, one part per file

200 {"stored":   [{"field":"photo","file-name":"door.png","key":"shops/photos/….png",
                   "sha256":"…","size":48213,"content-type":"image/png"}],
     "rejected": [{"field":"notes","file-name":"notes.html","reason":"wrong-kind"}]}
```

A rejected part's reason is `wrong-kind`, `too-large` or `not-a-file` (a
form field); the others are stored. The whole upload is refused, nothing
stored, with 403 (`:may-upload?` said no), 400 (not multipart), 413 (more
parts than `:max-files`) or 503 (the bucket or the database failed,
written to the log as `:attic/upload-failed`). The server's own body limit
must leave room for an upload's parts together.

The kinds attic tells by their bytes: PNG, JPEG, GIF, WebP, AVIF,
Radiance HDR, PDF, glTF (binary, and JSON named `.gltf`). Never markup:
SVG and HTML are refused
([why](decisions/2026-10-23-a-files-kind-is-told-by-its-bytes.md)).

A key is `<prefix>/<the sha256's first 32 hex digits as a uuid>.<extension>`;
the object is put with `If-None-Match: *`, and the row keeps the full
sha256 ([why](decisions/2026-10-23-a-file-is-named-by-its-content.md)).
The object is put before its row is written: a row always has its object,
and an object whose row was rolled back is found again by the same bytes
([why](decisions/2026-10-23-every-file-is-a-row-of-attic-files.md)).

`make-attic` declares, on the app's meter: `attic_files_stored_total{outcome}`
(`stored`, `already-stored`), `attic_files_refused_total{reason}`
(`wrong-kind`, `too-large`), `attic_files_deleted_total{outcome}`
(`deleted`, `still-named`, `not-found`), `attic_bucket_failures_total{operation}`
(`store`, `read`, `delete`).

A file is deleted, row and object, only when no row of the app names it
through a reference to `:file/key`; Postgres refuses the delete otherwise,
and nothing goes. `delete-file!` runs on a pool, after the app's own rows
are kept ([why](decisions/2026-10-23-a-file-is-deleted-only-when-no-row-names-it.md));
`delete-file-after-commit!` keeps it, on the pool, for once the app's
transaction commits (cellar's `run-after-commit!`; never after a
rollback), so a handler that deletes the rows naming a file deletes the
file in the same step: `(attic/delete-file-after-commit! attic pool
transaction key)`, nil back, the outcome counted and logged later
([cellar's why](https://github.com/p4ulcristian/cellar/blob/tidy-up/decisions/2026-10-23-a-transaction-keeps-work-to-run-after-its-commit.md)).

Every setting and option is required: a missing or unknown one is
refused, named, with every other problem at once, before anything runs.
What stays fixed, and why, is in
[the decision](decisions/2026-10-23-every-value-that-sets-behaviour-is-a-setting.md).

## Tests

```sh
clojure -X:test     # the bucket tests are skipped without a bucket and a database

# with SeaweedFS's S3 API and a throwaway Postgres:
echo '{"identities":[{"name":"t","credentials":[{"accessKey":"attic-test-key","secretKey":"attic-test-secret"}],"actions":["Admin","Read","Write","List"]}]}' > /tmp/attic-s3.json
docker run -d --name attic-test-s3 -p 127.0.0.1:8961:8333 -v /tmp/attic-s3.json:/etc/s3.json:ro \
  chrislusf/seaweedfs server -s3 -s3.config=/etc/s3.json
initdb -D /tmp/attic-pg -A trust -U "$USER"
pg_ctl -D /tmp/attic-pg -o "-p 5499 -k /tmp -c listen_addresses=localhost" -l /tmp/attic-pg.log start
ATTIC_TEST_S3_ENDPOINT=http://127.0.0.1:8961 ATTIC_TEST_S3_ACCESS_KEY=attic-test-key \
ATTIC_TEST_S3_SECRET_KEY=attic-test-secret ATTIC_TEST_PG_PORT=5499 ATTIC_TEST_PG_USER=$USER clojure -X:test
pg_ctl -D /tmp/attic-pg stop
```

Why SeaweedFS and not a fake:
[the decision](decisions/2026-10-23-tests-run-against-seaweedfs-and-postgres.md).
Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
