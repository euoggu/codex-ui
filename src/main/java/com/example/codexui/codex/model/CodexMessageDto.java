package com.example.codexui.codex.model;

public class CodexMessageDto {

    private String id;
    private String role;
    private String timestamp;
    private String content;

    public CodexMessageDto() {
    }

    public CodexMessageDto(String id, String role, String timestamp, String content) {
        this.id = id;
        this.role = role;
        this.timestamp = timestamp;
        this.content = content;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
