package ru.andreyz.memoryservice.dto;

public record CreateNoteRequest(String title, String text, String tags, String source) {}
