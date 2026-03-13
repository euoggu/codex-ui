package com.example.codexui.codex.model;

import java.util.ArrayList;
import java.util.List;

public class CodexSessionDetailDto extends CodexSessionSummaryDto {

    private String cliVersion;
    private int tokensUsed;
    private int messageCount;
    private List<CodexMessageDto> messages = new ArrayList<CodexMessageDto>();

    public String getCliVersion() {
        return cliVersion;
    }

    public void setCliVersion(String cliVersion) {
        this.cliVersion = cliVersion;
    }

    public int getTokensUsed() {
        return tokensUsed;
    }

    public void setTokensUsed(int tokensUsed) {
        this.tokensUsed = tokensUsed;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    public List<CodexMessageDto> getMessages() {
        return messages;
    }

    public void setMessages(List<CodexMessageDto> messages) {
        this.messages = messages;
    }
}
