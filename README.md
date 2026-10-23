# groundwork-file-storage

Files for a Clojure app, in an S3 bucket (R2, S3, any S3 API). Each file
is named by its content: the same bytes are the same key, stored once, and
an object is never written over. Every file stored is a row of
`groundwork_file_storage.files`, so the app can list what it has. A browser reads a file
through a signed link that expires, or a public address; an upload goes
through the server, each file checked by its bytes before it is stored.

```clojure
io.github.p4ulcristian/groundwork-file-storage {:git/tag "v0.1.5" :git/sha "eb6a725"}
```

Needs [groundwork-postgres](https://github.com/p4ulcristian/groundwork-postgres) (the files table),
[groundwork-shaped-json](https://github.com/p4ulcristian/groundwork-shaped-json) (the upload's answer),
[groundwork-logs](https://github.com/p4ulcristian/groundwork-logs) and
[groundwork-metrics](https://github.com/p4ulcristian/groundwork-metrics) (handed in),
[groundwork-settings](https://github.com/p4ulcristian/groundwork-settings) (the bucket's key is its
secrets), cognitect's aws-api (S3), ring-core (the multipart parser) and malli. One public
namespace, `groundwork-file-storage.api`.

## The api

| name | does |
|---|---|
| `make-file-storage` `stop-file-storage!` `settings-shape` | a file storage from its settings and `{:log :meter}`, every key required, checked first; declares groundwork-file-storage's metrics |
| `files-table` | `groundwork_file_storage.files` as a map for groundwork-postgres, to migrate with the app's tables |
| `store-file!` `known-content-types` | stores bytes under their content's key and records them, on a pool or in a transaction; `{:key-prefix :allowed-content-types :file-name}`; the row |
| `find-file` `list-files` | a file's row by key; the rows by prefix, after a key, at most a limit |
| `read-file-bytes` | a file's bytes from the bucket |
| `delete-file!` `delete-unnamed-files!` | a file's row and object deleted, on a pool, only when no row of the app names it (a reference to `:file/key`): `:deleted`, `:still-named` or `:not-found`; every file under a prefix no row names |
| `delete-file-after-commit!` | `delete-file!` on the pool kept for once the app's transaction commits (groundwork-postgres's `run-after-commit!`), never after a rollback; nil back, the outcome counted and logged then |
| `make-signed-link` `make-public-link` | a link that reads the file for `:link-expiry-seconds`; its address under `:public-url` |
| `make-upload-handler` `upload-settings-shape` | a Ring handler for a multipart upload, one part per file, after the app's `:may-upload?` |

```clojure
(require '[groundwork-file-storage.api :as file-storage])

(def file-storage
  (file-storage/make-file-storage {:endpoint            "https://<account>.r2.cloudflarestorage.com"
                     :bucket              "shop-files"
                     :access-key          access-key          ; read and write on that bucket
                     :secret-key          secret-key          ; both groundwork-settings's secrets: #secret :groundwork-file-storage/access-key, :groundwork-file-storage/secret-key
                     :region              "us-east-1"         ; R2 checks none; aws-api needs one it knows
                     :public-url          nil                 ; private: files are read through signed links
                     :link-expiry-seconds 300
                     :connect-timeout-ms  10000
                     :request-timeout-ms  60000
                     :retries             {:max-retries 4 :first-wait-ms 500 :max-wait-ms 4000}}
                    {:log log :meter meter}))

(postgres/migrate! pool {:tables (conj app-tables file-storage/files-table) :extensions [] :functions [] :views []}
                 changes migrate-options)

;; an order's invoice, in the transaction that saves the order
(def invoice (file-storage/store-file! file-storage transaction pdf-bytes {:key-prefix            "invoices"
                                                             :allowed-content-types #{"application/pdf"}
                                                             :file-name             nil}))
;=> #:file{:key "invoices/3f1c9a2e-77b0-4d1e-9c4a-0b8e1f2a6c3d.pdf" :sha256 "3f1c9a2e…"
;          :size 48213 :content-type "application/pdf" :stored-at #object[…]}

(file-storage/make-signed-link file-storage (:file/key invoice))
;=> "https://<account>.r2.cloudflarestorage.com/shop-files/invoices/3f1c….pdf?X-Amz-Algorithm=…&X-Amz-Signature=…"

;; uploads of a shop's photos, at the app's own path
(def upload-photos
  (file-storage/make-upload-handler file-storage
                             {:key-prefix            "shops/photos"
                              :allowed-content-types #{"image/png" "image/jpeg" "image/webp"}
                              :max-file-bytes        10000000
                              :max-files             10}
                             {:pool        pool
                              :shaper        shaper
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
written to the log as `:groundwork-file-storage/upload-failed`). The server's own body limit
must leave room for an upload's parts together.

The kinds groundwork-file-storage tells by their bytes: PNG, JPEG, GIF, WebP, AVIF,
Radiance HDR, PDF, glTF (binary, and JSON named `.gltf`). Never markup:
SVG and HTML are refused
([why](decisions/2026-10-23-a-files-kind-is-told-by-its-bytes.md)).

A key is `<prefix>/<the sha256's first 32 hex digits as a uuid>.<extension>`;
the object is put with `If-None-Match: *`, and the row keeps the full
sha256 ([why](decisions/2026-10-23-a-file-is-named-by-its-content.md)).
The object is put before its row is written: a row always has its object,
and an object whose row was rolled back is found again by the same bytes
([why](decisions/2026-10-23-every-file-is-a-row-of-attic-files.md)).

`make-file-storage` declares, on the app's meter: `groundwork_file_storage_files_stored_total{outcome}`
(`stored`, `already-stored`), `groundwork_file_storage_files_refused_total{reason}`
(`wrong-kind`, `too-large`), `groundwork_file_storage_files_deleted_total{outcome}`
(`deleted`, `still-named`, `not-found`), `groundwork_file_storage_bucket_failures_total{operation}`
(`store`, `read`, `delete`).

A file is deleted, row and object, only when no row of the app names it
through a reference to `:file/key`; Postgres refuses the delete otherwise,
and nothing goes. `delete-file!` runs on a pool, after the app's own rows
are kept ([why](decisions/2026-10-23-a-file-is-deleted-only-when-no-row-names-it.md));
`delete-file-after-commit!` keeps it, on the pool, for once the app's
transaction commits (groundwork-postgres's `run-after-commit!`; never after a
rollback), so a handler that deletes the rows naming a file deletes the
file in the same step: `(file-storage/delete-file-after-commit! file-storage pool
transaction key)`, nil back, the outcome counted and logged later
([groundwork-postgres's why](https://github.com/p4ulcristian/groundwork-postgres/blob/tidy-up/decisions/2026-10-23-a-transaction-keeps-work-to-run-after-its-commit.md)).

Every setting and option is required: a missing or unknown one is
refused, named, with every other problem at once, before anything runs.
What stays fixed, and why, is in
[the decision](decisions/2026-10-23-every-value-that-sets-behaviour-is-a-setting.md).

## Tests

```sh
clojure -X:test     # the bucket tests are skipped without a bucket and a database

# with SeaweedFS's S3 API and a throwaway Postgres:
echo '{"identities":[{"name":"t","credentials":[{"accessKey":"file-storage-test-key","secretKey":"file-storage-test-secret"}],"actions":["Admin","Read","Write","List"]}]}' > /tmp/file-storage-s3.json
docker run -d --name file-storage-test-s3 -p 127.0.0.1:8961:8333 -v /tmp/file-storage-s3.json:/etc/s3.json:ro \
  chrislusf/seaweedfs server -s3 -s3.config=/etc/s3.json
initdb -D /tmp/file-storage-pg -A trust -U "$USER"
pg_ctl -D /tmp/file-storage-pg -o "-p 5499 -k /tmp -c listen_addresses=localhost" -l /tmp/file-storage-pg.log start
FILE_STORAGE_TEST_S3_ENDPOINT=http://127.0.0.1:8961 FILE_STORAGE_TEST_S3_ACCESS_KEY=file-storage-test-key \
FILE_STORAGE_TEST_S3_SECRET_KEY=file-storage-test-secret FILE_STORAGE_TEST_PG_PORT=5499 FILE_STORAGE_TEST_PG_USER=$USER clojure -X:test
pg_ctl -D /tmp/file-storage-pg stop
```

Why SeaweedFS and not a fake:
[the decision](decisions/2026-10-23-tests-run-against-seaweedfs-and-postgres.md).
Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
