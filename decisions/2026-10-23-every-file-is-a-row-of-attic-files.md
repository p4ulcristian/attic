# Every file stored is a row of `attic.files`

> **Changed in part** by [A file is deleted only when no row names it](2026-10-23-a-file-is-deleted-only-when-no-row-names-it.md) (2026-10-23): `delete-file!` deletes a row with its object when no row of the app names it.

**Decision:** attic declares its table as a map for cellar, `files-table`, in its own schema: `attic.files`, columns `file__key` (primary key), `file__sha256`, `file__size`, `file__content_type`, `file__stored_at` (now). `store-file!` writes the row after the object, on the pool or in the caller's transaction; a row is never changed and attic deletes none. `find-file` and `list-files` (by prefix, after a key, a limit) read them. The app keeps a file's key in its own rows (an order's photo); the files table is what the bucket holds. Since v0.1.0.

**Why:** an app needs to list what it has stored and know a file's size and kind without asking the bucket; Iron Rainbow's catalog lists a release's files from its `files` table and checks each copy against the sha256 there. The object first, then the row: a row always has its object. Written in the caller's transaction, the row exists only if the caller's work does.

**The price:** an object can be left without a row: the caller's transaction rolled back after the put, or the server stopped between the two. It costs space, not correctness: the same bytes stored again find the object (412) and write the row. Nothing deletes objects; an app that must forget a file (personal data) needs a delete attic doesn't have yet.

**Instead of:**
- the bucket's listing as the record: slow, paged, without the sha256, and a different answer per S3 API
- the row before the object: a row could name an object that never arrived
- a table of the app's: every app would write the same one
