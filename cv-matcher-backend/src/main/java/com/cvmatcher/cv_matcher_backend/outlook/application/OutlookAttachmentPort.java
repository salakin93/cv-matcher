package com.cvmatcher.cv_matcher_backend.outlook.application;

import java.util.List;

/** Internal attachment boundary. Callers may use only immutable IDs obtained during discovery. */
public interface OutlookAttachmentPort {
    List<Attachment> listAttachments(String immutableMessageId, Runnable beforeRequest);
    byte[] download(String immutableMessageId, String attachmentId, Runnable beforeRequest);

    record Attachment(String id, long size, boolean inline, Kind kind) {
        public enum Kind { FILE, ITEM, REFERENCE }
    }
}
