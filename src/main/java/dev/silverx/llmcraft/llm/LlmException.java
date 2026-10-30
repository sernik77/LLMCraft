package dev.silverx.llmcraft.llm;

public class LlmException extends RuntimeException {
    public final int status;

    public LlmException(String message) {
        this(message, -1, null);
    }

    public LlmException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }
}
