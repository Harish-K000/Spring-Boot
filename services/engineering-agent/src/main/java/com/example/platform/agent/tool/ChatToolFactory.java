package com.example.platform.agent.tool;

import org.springframework.ai.chat.client.ChatClient;

/** Adds only chat-allowed callbacks with the current request's safety budget. */
public interface ChatToolFactory {
    ChatClient.ChatClientRequestSpec attach(ChatClient.ChatClientRequestSpec prompt,
                                             AgentSafetyPolicy.Budget budget);
}
