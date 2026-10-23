# A file is named by its content; an object is never written over

**Decision:** a file's key is made from its bytes: `<prefix>/<the first 32 hex digits of its sha256, written as a uuid>.<the extension of its kind>`, `orders/photos/3f1c9a2e-77b0-4d1e-9c4a-0b8e1f2a6c3d.png`. The prefix is the caller's, per call (`store-file!`'s `:key-prefix`, the upload's setting): a folder of segments of letters, digits, `.` `_` `-`, never `.` or `..`. The same bytes under the same prefix are the same key: stored once, every later store answering the row already there. An object is put with `If-None-Match: *`, so the bucket refuses to write over one (412, taken as "there already"); R2 and S3 both keep it. The full sha256 is in the file's row: a key recorded with another sha256 (two contents sharing the first 128 bits) throws `:attic/key-taken`, never written over. Since v0.1.0.

**Why:** a changed file is a new key, so no browser, CDN or copy ever holds a stale one, and a key can be cached forever; the same photo uploaded twice is one object. The uuid form is Iron Rainbow's catalog's, so its stored keys and attic's agree. The prefix per call lets one app keep its kinds of file apart (photos, invoices) without one attic each.

**The price:** the same bytes under two prefixes are two objects. A file can't be renamed or replaced in place: a new version is a new key, and the app's row points at it. Two uploads of the same new bytes at once both put; the second is refused by the bucket, and both answer the same row.

**Instead of:**
- the full 64 hex digits as the key: no collision to think about, but keys unlike Iron Rainbow's, for a risk the row's full sha256 already catches
- random keys: the same bytes stored again and again, and nothing says two files are one
- a fixed prefix in the settings: every file of an app in one folder
- a HEAD before every put: two requests where the bucket's own condition does it in one
