package ru.andreyz.ragservice.indexer;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChunkSplitter {

    private final RagChunkProperties chunkProperties;

    public ChunkSplitter(RagChunkProperties chunkProperties) {
        this.chunkProperties = chunkProperties;
    }

    public List<String> split(String content) {
        String[] paragraphs = content.split("\n\n+");
        List<String> merged = mergeShortParagraphs(paragraphs);
        List<String> chunks = splitLongChunks(merged);
        return addOverlap(chunks);
    }

    private List<String> mergeShortParagraphs(String[] paragraphs) {
        List<String> result = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (String para : paragraphs) {
            String p = para.trim();
            if (p.isEmpty()) continue;

            if (buf.length() > 0 && buf.length() < chunkProperties.getMinSize()) {
                buf.append("\n\n").append(p);
            } else {
                if (!buf.isEmpty()) result.add(buf.toString());
                buf = new StringBuilder(p);
            }
        }
        if (!buf.isEmpty()) {
            if (result.isEmpty() || buf.length() >= chunkProperties.getMinSize()) {
                result.add(buf.toString());
            } else {
                result.set(result.size() - 1, result.get(result.size() - 1) + "\n\n" + buf);
            }
        }
        return result;
    }

    private List<String> splitLongChunks(List<String> chunks) {
        List<String> result = new ArrayList<>();
        int maxChunk = chunkProperties.getMaxSize();
        for (String chunk : chunks) {
            if (chunk.length() <= maxChunk) {
                result.add(chunk);
                continue;
            }
            // Split at sentence boundaries
            int start = 0;
            while (start < chunk.length()) {
                int end = Math.min(start + maxChunk, chunk.length());
                if (end < chunk.length()) {
                    // Look for last sentence end within the window
                    int sentenceEnd = lastSentenceEnd(chunk, start, end);
                    if (sentenceEnd > start) end = sentenceEnd;
                }
                result.add(chunk.substring(start, end).trim());
                start = end;
            }
        }
        return result;
    }

    private int lastSentenceEnd(String text, int from, int to) {
        for (int i = to - 1; i > from; i--) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == ' ' || next == '\n') return i + 1;
            }
        }
        return -1;
    }

    private List<String> addOverlap(List<String> chunks) {
        if (chunks.size() <= 1) return chunks;
        List<String> result = new ArrayList<>();
        result.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            String overlap = extractLastSentence(chunks.get(i - 1));
            result.add(overlap.isEmpty() ? chunks.get(i) : overlap + " " + chunks.get(i));
        }
        return result;
    }

    private String extractLastSentence(String text) {
        int end = text.length();
        // Skip trailing whitespace
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        // Find the second-to-last sentence end (to get the last sentence)
        int lastEnd = -1;
        for (int i = end - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                if (lastEnd == -1) {
                    lastEnd = i;
                } else {
                    String sentence = text.substring(i + 1, end).trim();
                    return sentence.length() > 200 ? sentence.substring(sentence.length() - 200) : sentence;
                }
            }
        }
        if (lastEnd == -1) return "";
        String sentence = text.substring(0, end).trim();
        return sentence.length() > 200 ? sentence.substring(sentence.length() - 200) : sentence;
    }
}
