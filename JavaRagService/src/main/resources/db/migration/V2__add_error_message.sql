ALTER TABLE indexed_documents
    ADD COLUMN IF NOT EXISTS error_message TEXT;
