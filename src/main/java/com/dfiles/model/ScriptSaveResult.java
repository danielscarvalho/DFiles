package com.dfiles.model;

/** The values a script save recomputes: its new content hash and the timestamp the save was
 * recorded at, returned together so the UI can refresh both without a second database query. */
public record ScriptSaveResult(String hash, long updatedAt) {
}
