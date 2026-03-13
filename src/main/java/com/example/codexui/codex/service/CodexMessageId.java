package com.example.codexui.codex.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * messageId 格式：{threadId}-line-{lineNumber}
 * lineNumber 是 rollout 文件中的 1-based 行号（对应 response_item message 那一行）。
 */
public final class CodexMessageId {

    public final String threadId;
    public final int lineNumber;

    private CodexMessageId(String threadId, int lineNumber) {
        this.threadId = threadId;
        this.lineNumber = lineNumber;
    }

    public static CodexMessageId parse(String messageId) {
        if (messageId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messageId 不能为空");
        }
        int idx = messageId.lastIndexOf("-line-");
        if (idx <= 0 || idx + 6 >= messageId.length()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messageId 格式不正确: " + messageId);
        }
        String threadId = messageId.substring(0, idx);
        String tail = messageId.substring(idx + 6);
        try {
            int n = Integer.parseInt(tail);
            if (n <= 0) {
                throw new NumberFormatException("line_number <= 0");
            }
            return new CodexMessageId(threadId, n);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messageId lineNumber 不正确: " + messageId, e);
        }
    }
}

