package ru.andreyz.memoryservice.dto;

public record CreateNoteRequest(String text, String tags, String source) {}
