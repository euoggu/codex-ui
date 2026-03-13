package com.example.codexui.codex.model;

import java.util.ArrayList;
import java.util.List;

public class CodexSessionListResponse {

    private String codexHome;
    private int limit;
    private int count;
    private List<CodexSessionSummaryDto> items = new ArrayList<CodexSessionSummaryDto>();

    public String getCodexHome() {
        return codexHome;
    }

    public void setCodexHome(String codexHome) {
        this.codexHome = codexHome;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public List<CodexSessionSummaryDto> getItems() {
        return items;
    }

    public void setItems(List<CodexSessionSummaryDto> items) {
        this.items = items;
    }
}
