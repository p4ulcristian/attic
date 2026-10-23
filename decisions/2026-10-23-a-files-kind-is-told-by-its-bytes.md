# A file's kind is told by its bytes; the caller says which kinds it takes

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

**Decision:** attic tells a file's content type from its first bytes (`attic.kinds`), never from its name or what the browser says: PNG, JPEG, GIF, WebP, AVIF, Radiance HDR, PDF, glTF binary, and glTF JSON (JSON whose name ends in `.gltf`, the only use of the name). Markup is no kind attic knows: SVG and HTML, which a browser runs, are never stored. Each call says which kinds it takes: `store-file!`'s `:allowed-content-types` and the upload's, a set of `known-content-types`, checked when given; bytes of any other kind are refused (`:attic/wrong-kind`, or the upload's `wrong-kind`). The kind gives the key's extension and the object's content type. Since v0.1.0.

**Why:** a browser's content type and a file's name are the uploader's to write; a `photo.png` that is HTML, served from the bucket, runs. Iron Rainbow decides by bytes for that reason. A set is data: house reads it from the settings, checks it, and shows it; it can only name kinds attic tells.

**The price:** a kind attic doesn't know (a CSV, a ZIP, a video) can't be stored until attic learns it, in a new version. An app can't store SVG icons at all.

**Instead of:**
- the app's own function from bytes to a kind: any kind at all, but a function in the settings can't be checked or shown, and every app would write its own sniffing
- the extension or the browser's type: the uploader's word
- SVG stored as an attachment (`Content-Disposition`): a header the public URL or a CDN may drop
