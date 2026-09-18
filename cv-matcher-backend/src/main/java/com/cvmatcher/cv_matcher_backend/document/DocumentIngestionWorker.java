package com.cvmatcher.cv_matcher_backend.document;

import com.cvmatcher.cv_matcher_backend.job.application.DocumentIngestionJobPort;
import com.cvmatcher.cv_matcher_backend.outlook.application.AttachmentException;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookAttachmentPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "app.job.documents", name = "enabled", havingValue = "true")
final class DocumentIngestionWorker {
    private final DocumentIngestionJobPort jobs; private final OutlookAttachmentPort attachments; private final DocumentPersistence documents;
    private final EncryptedDocumentStorage storage; private final AntivirusPort antivirus; private final DocumentIngestionProperties properties; private final MeterRegistry metrics;
    private final String workerId = "document-ingestion-" + UUID.randomUUID();
    DocumentIngestionWorker(DocumentIngestionJobPort jobs, OutlookAttachmentPort attachments, DocumentPersistence documents, EncryptedDocumentStorage storage, AntivirusPort antivirus, DocumentIngestionProperties properties, MeterRegistry metrics) {
        this.jobs = jobs; this.attachments = attachments; this.documents = documents; this.storage = storage; this.antivirus = antivirus; this.properties = properties; this.metrics = metrics;
    }
    @Scheduled(fixedDelayString = "${app.job.documents.poll-delay}") void ingestNext() { jobs.claimNextIngestion(workerId, properties.leaseDuration()).ifPresent(this::ingest); }
    void ingest(DocumentIngestionJobPort.IngestionClaim job) {
        long bytes = 0; int processed = 0;
        try {
            for (var message : jobs.discoveredMessages(job.jobId(), workerId)) {
                if (jobs.isCancelled(job.jobId())) return;
                var metadata = attachments.listAttachments(message.graphMessageId(), () -> renew(job));
                int seen = 0;
                for (var attachment : metadata) {
                    if (jobs.isCancelled(job.jobId())) return;
                    if (++seen > properties.maxAttachmentsPerMessage() || ++processed > properties.maxDocumentsPerJob()) { warning("JOB_DOCUMENT_LIMIT_REACHED"); break; }
                    if (documents.resolved(job.jobId(), message.graphMessageId(), attachment.id())) continue;
                    if (attachment.kind() != OutlookAttachmentPort.Attachment.Kind.FILE || attachment.inline()) { save(job, message, attachment.id(), null, 0, null, null, "IGNORED", "UNSUPPORTED_FORMAT"); continue; }
                    if (attachment.size() <= 0) { save(job, message, attachment.id(), null, 0, null, null, "IGNORED", "EMPTY_DOCUMENT"); continue; }
                    if (attachment.size() > properties.maxDocumentBytes() || bytes + attachment.size() > properties.maxBytesPerJob()) { save(job, message, attachment.id(), null, 0, null, null, "IGNORED", "FILE_TOO_LARGE"); warning("JOB_DOCUMENT_LIMIT_REACHED"); continue; }
                     var content = attachments.download(message.graphMessageId(), attachment.id(), () -> renew(job));
                    if (jobs.isCancelled(job.jobId())) return;
                    if (content.length > properties.maxDocumentBytes() || bytes + content.length > properties.maxBytesPerJob()) { save(job, message, attachment.id(), null, 0, null, null, "IGNORED", "FILE_TOO_LARGE"); continue; }
                    bytes += content.length;
                    var inspected = DocumentInspector.inspect(content);
                    if (inspected.reason() != null) { save(job, message, attachment.id(), null, 0, null, null, "IGNORED", inspected.reason()); ignored(inspected.reason()); continue; }
                    var result = antivirus.scan(content);
                    if (jobs.isCancelled(job.jobId())) return;
                    if (result == AntivirusPort.Result.UNAVAILABLE) { antivirus("unavailable"); jobs.finishIngestion(job.jobId(), workerId, DocumentIngestionJobPort.IngestionTerminalStatus.FAILED, "DOCUMENT_INGESTION_UNAVAILABLE"); return; }
                    if (result == AntivirusPort.Result.DETECTED) { antivirus("detected"); save(job, message, attachment.id(), inspected.format(), content.length, DocumentPersistence.hash(content), null, "QUARANTINED", "MALWARE_DETECTED"); outcome("quarantined"); continue; }
                    antivirus("clean"); String key;
                    try { key = storage.store(content); } catch (RuntimeException exception) { storage("failed"); throw exception; }
                    if (jobs.isCancelled(job.jobId())) { storage.delete(key); return; }
                    try { save(job, message, attachment.id(), inspected.format(), content.length, DocumentPersistence.hash(content), key, "ACCEPTED", null); storage("stored"); outcome("accepted"); }
                    catch (RuntimeException exception) { storage.delete(key); storage("failed"); throw exception; }
                }
            }
            jobs.completeIngestion(job.jobId(), workerId, documents.hasAvailable(job.jobId()));
        } catch (AttachmentException exception) {
            jobs.finishIngestion(job.jobId(), workerId, exception.kind() == AttachmentException.Kind.REAUTHORIZATION_REQUIRED ? DocumentIngestionJobPort.IngestionTerminalStatus.REAUTHORIZATION_REQUIRED : DocumentIngestionJobPort.IngestionTerminalStatus.FAILED, exception.kind() == AttachmentException.Kind.REAUTHORIZATION_REQUIRED ? "OUTLOOK_REAUTH_REQUIRED" : "DOCUMENT_INGESTION_UNAVAILABLE"); outcome("failed");
        } catch (RuntimeException exception) { jobs.finishIngestion(job.jobId(), workerId, DocumentIngestionJobPort.IngestionTerminalStatus.FAILED, "DOCUMENT_INGESTION_UNAVAILABLE"); outcome("failed"); }
    }
    private void save(DocumentIngestionJobPort.IngestionClaim job, DocumentIngestionJobPort.DiscoveredMessageReference message, String attachmentId, DocumentFormat format, long size, String hash, String key, String disposition, String reason) {
        var document = new DocumentIngestionJobPort.DocumentWrite(message.graphMessageId(), attachmentId, message.receivedAt(), format == null ? null : format.name(), size, hash, key, properties.encryptionKeyVersion(), disposition, reason);
        if (!jobs.persistDocument(job.jobId(), workerId, document)) throw new IllegalStateException("lease lost");
    }
    private void renew(DocumentIngestionJobPort.IngestionClaim job) { if (!jobs.renewLease(job.jobId(), workerId, properties.leaseDuration())) throw new IllegalStateException("lease lost"); }
    private void outcome(String value) { metrics.counter("documents.ingestion", "outcome", value).increment(); }
    private void ignored(String reason) { metrics.counter("documents.ignored", "reason", reason).increment(); outcome("ignored"); }
    private void warning(String reason) { metrics.counter("documents.ignored", "reason", reason).increment(); }
    private void antivirus(String value) { metrics.counter("documents.antivirus", "outcome", value).increment(); }
    private void storage(String value) { metrics.counter("documents.storage", "outcome", value).increment(); }
}
