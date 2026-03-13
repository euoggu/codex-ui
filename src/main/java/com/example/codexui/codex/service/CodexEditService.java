package com.example.codexui.codex.service;

import com.example.codexui.codex.repo.CodexUiOverlayRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CodexEditService {

    private final CodexUiOverlayRepository overlayRepository;
    private final CodexMessageFileService codexMessageFileService;

    public CodexEditService(CodexUiOverlayRepository overlayRepository,
                            CodexMessageFileService codexMessageFileService) {
        this.overlayRepository = overlayRepository;
        this.codexMessageFileService = codexMessageFileService;
    }

    public void updateSession(String sessionId, String title, String folderId, Boolean archived) {
        String normalizedTitle = title == null ? null : title.trim();
        if (normalizedTitle != null && normalizedTitle.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title 不能为空字符串");
        }
        Integer archivedOverride = archived == null ? null : (archived.booleanValue() ? 1 : 0);
        overlayRepository.upsertSessionOverride(sessionId, normalizedTitle, folderId, archivedOverride);
    }

    public void updateMessage(String messageId, String content) {
        CodexMessageId parsed = CodexMessageId.parse(messageId);
        codexMessageFileService.updateMessage(parsed.threadId, parsed.lineNumber, content);
    }

    public void deleteMessage(String messageId) {
        CodexMessageId parsed = CodexMessageId.parse(messageId);
        codexMessageFileService.deleteMessage(parsed.threadId, parsed.lineNumber);
    }
}
