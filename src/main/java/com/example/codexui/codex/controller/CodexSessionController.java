package com.example.codexui.codex.controller;

import com.example.codexui.codex.model.CodexMessageDto;
import com.example.codexui.codex.model.CodexResumeResultDto;
import com.example.codexui.codex.model.CodexSessionDetailDto;
import com.example.codexui.codex.model.CodexSessionListResponse;
import com.example.codexui.codex.service.CodexCloneService;
import com.example.codexui.codex.service.CodexSessionService;
import com.example.codexui.codex.model.requests.UpdateSessionRequest;
import com.example.codexui.codex.service.CodexEditService;
import com.example.codexui.codex.service.CodexResumeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/codex/sessions")
public class CodexSessionController {

    private final CodexSessionService codexSessionService;
    private final CodexEditService codexEditService;
    private final CodexResumeService codexResumeService;
    private final CodexCloneService codexCloneService;

    public CodexSessionController(CodexSessionService codexSessionService,
                                  CodexEditService codexEditService,
                                  CodexResumeService codexResumeService,
                                  CodexCloneService codexCloneService) {
        this.codexSessionService = codexSessionService;
        this.codexEditService = codexEditService;
        this.codexResumeService = codexResumeService;
        this.codexCloneService = codexCloneService;
    }

    @GetMapping
    public CodexSessionListResponse listSessions(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "archived", required = false) Boolean archived,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return codexSessionService.listSessions(query, archived, limit);
    }

    @GetMapping("/{sessionId}")
    public CodexSessionDetailDto getSession(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "includeMessages", defaultValue = "true") boolean includeMessages,
            @RequestParam(value = "includeNonChatRoles", defaultValue = "false") boolean includeNonChatRoles) {
        return codexSessionService.getSession(sessionId, includeMessages, includeNonChatRoles);
    }

    @PatchMapping("/{sessionId}")
    public CodexSessionDetailDto updateSession(
            @PathVariable("sessionId") String sessionId,
            @org.springframework.web.bind.annotation.RequestBody UpdateSessionRequest request) {
        codexEditService.updateSession(sessionId, request.getTitle(), request.getFolderId(), request.getArchived());
        return codexSessionService.getSession(sessionId, false, false);
    }

    @GetMapping("/{sessionId}/messages")
    public List<CodexMessageDto> getMessages(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "includeNonChatRoles", defaultValue = "false") boolean includeNonChatRoles) {
        return codexSessionService.getSession(sessionId, true, includeNonChatRoles).getMessages();
    }

    @PostMapping("/{sessionId}/resume")
    public CodexResumeResultDto resumeSession(@PathVariable("sessionId") String sessionId) {
        return codexResumeService.resumeInIterm(sessionId);
    }

    @PostMapping("/{sessionId}/clone")
    public CodexSessionDetailDto cloneSession(@PathVariable("sessionId") String sessionId) {
        return codexCloneService.cloneSession(sessionId);
    }
}
