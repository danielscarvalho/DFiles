package com.dfiles.model;

/**
 * The full saved state of one script: the AI prompt (if any) that produced it, its bash content,
 * and the output captured the last time it was run and tested.
 */
public record ScriptDetails(String prompt, String content, String lastOutput) {
}
