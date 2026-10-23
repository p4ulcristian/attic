-- A file the bucket holds, recorded once: a key recorded before is left as it is (a file never
-- changes). Returns the record as it stands; added says whether this call wrote it.
WITH added AS (
  INSERT INTO attic.files (file__key, file__sha256, file__size, file__content_type)
  VALUES (:file/key, :file/sha256, :file/size, :file/content-type)
  ON CONFLICT (file__key) DO NOTHING
  RETURNING file__key, file__sha256, file__size, file__content_type, file__stored_at
)
SELECT file__key, file__sha256, file__size, file__content_type, file__stored_at, true AS added FROM added
UNION ALL
SELECT file__key, file__sha256, file__size, file__content_type, file__stored_at, false AS added FROM attic.files
WHERE file__key = :file/key AND NOT EXISTS (SELECT 1 FROM added)
