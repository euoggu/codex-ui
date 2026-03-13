package com.example.codexui.codex.model;

public class CodexFolderDto {

    private String id;
    private String name;
    private int sortIndex;

    public CodexFolderDto() {
    }

    public CodexFolderDto(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public CodexFolderDto(String id, String name, int sortIndex) {
        this.id = id;
        this.name = name;
        this.sortIndex = sortIndex;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getSortIndex() {
        return sortIndex;
    }

    public void setSortIndex(int sortIndex) {
        this.sortIndex = sortIndex;
    }
}
