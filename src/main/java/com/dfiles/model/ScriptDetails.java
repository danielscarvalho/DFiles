package com.dfiles.model;

/**
 * The full saved state of one script: the AI prompt (if any) that produced it, its bash content,
 * the output captured the last time it was run and tested, and a SHA-256 hash of the content
 * that changes whenever the script is edited.
 */
public record ScriptDetails(String prompt, String content, String lastOutput, String hash) {
}
