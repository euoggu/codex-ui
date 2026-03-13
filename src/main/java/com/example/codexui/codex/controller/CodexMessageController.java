package com.example.codexui.codex.controller;

import com.example.codexui.codex.model.CodexSessionDetailDto;
import com.example.codexui.codex.model.requests.UpdateMessageRequest;
import com.example.codexui.codex.service.CodexCloneService;
import com.example.codexui.codex.service.CodexEditService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/codex/messages")
public class CodexMessageController {

    private final CodexEditService codexEditService;
    private final CodexCloneService codexCloneService;

    public CodexMessageController(CodexEditService codexEditService,
                                 CodexCloneService codexCloneService) {
        this.codexEditService = codexEditService;
        this.codexCloneService = codexCloneService;
    }

    @PatchMapping("/{messageId}")
    public void updateMessage(@PathVariable("messageId") String messageId, @RequestBody UpdateMessageRequest request) {
        codexEditService.updateMessage(messageId, request.getContent());
    }

    @DeleteMapping("/{messageId}")
    public void deleteMessage(@PathVariable("messageId") String messageId) {
        codexEditService.deleteMessage(messageId);
    }

    @PostMapping("/{messageId}/branch")
    public CodexSessionDetailDto branchFromHere(@PathVariable("messageId") String messageId) {
        return codexCloneService.branchFromMessage(messageId);
    }
}
