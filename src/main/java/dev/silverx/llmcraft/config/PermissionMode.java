package dev.silverx.llmcraft.config;

public enum PermissionMode {
    /** Tools of this category are not offered to the AI at all. */
    OFF,
    /** The player has to approve each call (clickable chat buttons or approve/deny keybinds). */
    ASK,
    /** Runs without asking. */
    ALLOW
}
