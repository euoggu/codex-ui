package com.example.codexui.codex.model;

import java.util.ArrayList;
import java.util.List;

public class CodexMessageSearchSessionDto {

    private String sessionId;
    private String title;
    private String cwd;
    private String folderId;
    private String updatedAt;
    private int matchCount;
    private List<CodexMessageSearchMatchDto> matches = new ArrayList<CodexMessageSearchMatchDto>();

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCwd() {
        return cwd;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd;
    }

    public String getFolderId() {
        return folderId;
    }

    public void setFolderId(String folderId) {
        this.folderId = folderId;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getMatchCount() {
        return matchCount;
    }

    public void setMatchCount(int matchCount) {
        this.matchCount = matchCount;
    }

    public List<CodexMessageSearchMatchDto> getMatches() {
        return matches;
    }

    public void setMatches(List<CodexMessageSearchMatchDto> matches) {
        this.matches = matches;
    }
}

