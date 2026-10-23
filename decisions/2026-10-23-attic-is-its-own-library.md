# attic is its own library

**Decision:** files an app keeps (a shop's photos, an invoice's PDF, a 3D model) are manor's library attic: a bucket on any S3 API (R2, S3), reached through cognitect's aws-api, every file named by its content and recorded in a table of attic's, links for the browser, and an upload route's handler. It is its own repository, `io.github.p4ulcristian/attic`, public, MIT, topic `manor`; house takes it from git and puts its settings, table and upload route in. One public namespace, `attic.api`. It needs cellar (the table, its queries), wire (the upload's answer), logbook and meter (handed in), ring-core (the multipart parser) and malli. Since v0.1.0.

It is Iron Rainbow's file code made general: its R2 client and signed links, its catalog's content-named store and files table, its upload route and its kinds told by bytes. Nothing of Iron Rainbow's is in it: no bucket, label, domain or token.

**Why:** every app keeps files, and every one meets the same questions: what name a file gets, whether an object can be written over, how a private file reaches a browser, who may upload, what an upload may be. Iron Rainbow answered each after a bug (an overwritten object, stale caches, an SVG served inline, an upload with no size); a library answers them once.

**Instead of:**
- the files in house: house puts libraries together; the bucket is a question of its own, as Postgres is cellar's
- an S3 SDK's own presigner and upload code (AWS SDK v2): a large dependency for three operations, and Iron Rainbow already runs on aws-api
- files in Postgres (bytea): a database grown by every photo, every backup carrying them, and no CDN in front
