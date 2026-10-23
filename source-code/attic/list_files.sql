-- The files whose keys start with the prefix, after a key (nil: from the first), by key.
SELECT file__key, file__sha256, file__size, file__content_type, file__stored_at
FROM attic.files
WHERE starts_with(file__key, :key-prefix)
  AND (CAST(:after-key AS text) IS NULL OR file__key > CAST(:after-key AS text))
ORDER BY file__key
LIMIT :limit
