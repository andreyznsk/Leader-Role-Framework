ALTER TABLE rag.indexed_documents
    ADD COLUMN IF NOT EXISTS doc_type VARCHAR(50);
