# A file is deleted only when no row names it; Postgres's references say so

> Since v0.1.3. Changes in part [Every file stored is a row of `attic.files`](2026-10-23-every-file-is-a-row-of-attic-files.md) ("no row is deleted by attic").

**Decision:**

- **`delete-file! attic pool key`** deletes a file, its row and its object, only when no row of the app names it. A row names a file through a reference to `:file/key` (`[:order/invoice-key :string {:references :file/key}]`); Postgres refuses the file row's delete while one points at it, and attic answers `:still-named`, deleting nothing. Otherwise `:deleted`, or `:not-found` for a key no file has.
- **On a pool, in a transaction of its own:** the row is deleted, then the object, then the transaction is kept; a bucket failure throws and keeps the row. A transaction handed in is refused (`:attic/needs-a-pool`): an object deleted can't come back with a rollback, so the app deletes its own rows first, keeps them, then deletes the file. The object goes before the row's delete is kept, so a store of the same bytes that finds no row puts them anew; a commit that fails after it leaves a row without its object (`read-file-bytes` answers nil), the one way a row loses its object.
- **No owners, no counting:** attic knows no owners; a file's prefix is the app's to choose (`shops/<id>/logos`). `delete-unnamed-files! attic pool {:key-prefix :page-size}` deletes every file under a prefix that no row names, a page at a time, for a background job after the app deletes its rows: `{:deleted n :still-named n}`.
- Counted, `attic_files_deleted_total{outcome}` (`deleted`, `still-named`, `not-found`), a bucket's failure as `attic_bucket_failures_total{operation="delete"}`; each deletion written to the app's log, `:info :attic/file-deleted` with the key and size.

**Why:** content-named, one file is one row however many rows of the app name it (two orders with the same PDF). What names it is the app's rows, which Postgres already tracks through their references, past any gates; a count kept by attic would be a second record of the same fact that the app must keep in step on every insert, update and delete.

**The price:** a row that names a file without a reference (a key in JSON or plain text) isn't seen: the file can be deleted under it. Deleting is after the app's transaction, not in it.

**Instead of:**
- reference counting in `attic.files`: the app calls add and remove on every change, and one forgotten call deletes a file still in use or keeps one forever
- deleting in the caller's transaction: a rollback brings the row back without its object
- an owner column on `attic.files`: one file has many owners
