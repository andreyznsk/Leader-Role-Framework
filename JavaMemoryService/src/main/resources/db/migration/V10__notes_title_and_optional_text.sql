ALTER TABLE notes ADD COLUMN title VARCHAR(200);

UPDATE notes
SET title = SUBSTRING(TRIM(text), 1, 200)
WHERE title IS NULL;

UPDATE notes
SET title = 'Untitled note'
WHERE title IS NULL OR title = '';

ALTER TABLE notes ALTER COLUMN title SET NOT NULL;
ALTER TABLE notes ALTER COLUMN text DROP NOT NULL;
