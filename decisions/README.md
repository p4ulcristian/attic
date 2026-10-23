# attic decisions

One file per decision: `YYYY-MM-DD-what-we-decided.md`. The title is the decision. Each file says what we decided, why, and what we chose against. A decision changes by a new file; the old one says so at the top (`Replaced by` or `Changed in part by`), and nothing else in it is rewritten.

attic is manor's file storage (manor: house and the libraries it puts together). All were decided as v0.1.0 was built, from Iron Rainbow's R2 and catalog file code and what it taught.

- [attic is its own library](2026-10-23-attic-is-its-own-library.md)
- [A file is named by its content; an object is never written over](2026-10-23-a-file-is-named-by-its-content.md)
- [Every file stored is a row of `attic.files`](2026-10-23-every-file-is-a-row-of-attic-files.md)
- [The bucket through aws-api; signed links written by attic](2026-10-23-the-bucket-through-aws-api-links-signed-by-attic.md)
- [A file's kind is told by its bytes; the caller says which kinds it takes](2026-10-23-a-files-kind-is-told-by-its-bytes.md)
- [Uploads go through the server; the app says who may upload](2026-10-23-uploads-go-through-the-server.md)
- [Every value that sets behaviour is a setting; no hidden defaults](2026-10-23-every-value-that-sets-behaviour-is-a-setting.md)
- [The app passes its log and its meter](2026-10-23-the-app-passes-its-log-and-meter.md)
- [The tests run against a real S3 API (SeaweedFS) and a real Postgres](2026-10-23-tests-run-against-seaweedfs-and-postgres.md)
