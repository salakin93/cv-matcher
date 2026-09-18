package com.cvmatcher.cv_matcher_backend.document;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** The only built-in production behavior is fail-closed; deployments supply an AV adapter. */
@Component
@ConditionalOnProperty(prefix = "app.job.documents", name = "enabled", havingValue = "true")
final class ConfiguredAntivirusPort implements AntivirusPort {
    private final DocumentIngestionProperties properties;
    ConfiguredAntivirusPort(DocumentIngestionProperties properties) { this.properties = properties; }
    @Override
    public Result scan(byte[] content) {
        if ("clean".equals(properties.antivirusMode())) return Result.CLEAN;
        if (!"clamav".equals(properties.antivirusMode())) return Result.UNAVAILABLE;
        try (var socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(properties.antivirusHost(), properties.antivirusPort()), (int) properties.antivirusTimeout().toMillis());
            socket.setSoTimeout((int) properties.antivirusTimeout().toMillis());
            var output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            output.writeInt(content.length);
            output.write(content);
            output.writeInt(0);
            output.flush();
            var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            return response.contains("FOUND") ? Result.DETECTED : response.contains("OK") ? Result.CLEAN : Result.UNAVAILABLE;
        } catch (Exception exception) {
            return Result.UNAVAILABLE;
        }
    }
}
