-- A file's record, deleted; refused by Postgres while a row of the app names it
-- through a reference to file__key. Returns the record as it was.
DELETE FROM groundwork_file_storage.files
WHERE file__key = :file/key
RETURNING file__key, file__sha256, file__size, file__content_type, file__stored_at
