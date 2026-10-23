-- One file's record, by its key.
SELECT file__key, file__sha256, file__size, file__content_type, file__stored_at
FROM attic.files
WHERE file__key = :file/key
