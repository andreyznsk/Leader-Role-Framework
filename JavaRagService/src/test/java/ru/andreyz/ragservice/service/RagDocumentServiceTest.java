package ru.andreyz.ragservice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.andreyz.ragservice.db.IndexedDocument;
import ru.andreyz.ragservice.db.IndexedDocumentRepository;
import ru.andreyz.ragservice.indexer.FileIndexer;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagDocumentServiceTest {

    @Mock
    IndexedDocumentRepository repository;

    @Mock
    FileIndexer fileIndexer;

    @Test
    void getDocument_missingFile_returnsUnavailableDetailsInsteadOf500() throws Exception {
        RagDocumentService service = new RagDocumentService(repository, fileIndexer);
        IndexedDocument document = new IndexedDocument(
                41L,
                "rag-inbox/mail/2026-06-20/missing.md",
                "NOTICE",
                "hash",
                LocalDateTime.of(2026, 6, 20, 15, 0),
                1,
                "indexed",
                null
        );
        when(repository.findById(41L)).thenReturn(Optional.of(document));

        RagDocumentService.RagDocumentDetails details = service.getDocument(41L);

        assertThat(details.summary().id()).isEqualTo(41L);
        assertThat(details.summary().status()).isEqualTo("missing");
        assertThat(details.summary().errorMessage()).contains("missing");
        assertThat(details.content()).contains("Document content is unavailable");
    }
}
