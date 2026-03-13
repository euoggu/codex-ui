package com.example.codexui.codex.model;

import java.util.ArrayList;
import java.util.List;

public class CodexMessageSearchResponse {

    private String query;
    private int count;
    private List<CodexMessageSearchSessionDto> items = new ArrayList<CodexMessageSearchSessionDto>();

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public List<CodexMessageSearchSessionDto> getItems() {
        return items;
    }

    public void setItems(List<CodexMessageSearchSessionDto> items) {
        this.items = items;
    }
}

