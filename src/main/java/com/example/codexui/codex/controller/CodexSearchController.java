package com.example.codexui.codex.controller;

import com.example.codexui.codex.model.CodexMessageSearchResponse;
import com.example.codexui.codex.service.CodexSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/codex/search")
public class CodexSearchController {

    private final CodexSearchService codexSearchService;

    public CodexSearchController(CodexSearchService codexSearchService) {
        this.codexSearchService = codexSearchService;
    }

    @GetMapping("/messages")
    public CodexMessageSearchResponse searchMessages(
            @RequestParam("q") String query,
            @RequestParam(value = "archived", required = false) Boolean archived) {
        return codexSearchService.searchMessages(query, archived);
    }
}

