/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2026 National Library of Australia and the jwarc contributors
 */

package org.netpreserve.jwarc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * Builds local WARC fixtures (plain and record-at-a-time gzip) for revisit resolution tests.
 */
public class RevisitTestFixtures {
    public static final URI TARGET = URI.create("http://example.org/index.html");
    public static final Instant DATE = Instant.parse("2026-01-02T03:04:05Z");

    private RevisitTestFixtures() {
    }

    public static URI uuid() {
        return URI.create("urn:uuid:" + UUID.randomUUID());
    }

    public static WarcDigest sha1(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(data);
        return new WarcDigest(md);
    }

    public static HttpResponse httpResponse(byte[] payload) {
        return new HttpResponse.Builder(200, "OK")
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .addHeader("Content-Length", Integer.toString(payload.length))
                .body(MediaType.HTML, payload)
                .build();
    }

    public static HttpResponse gzippedHttpResponse(byte[] encodedPayload, int decodedLength) {
        return new HttpResponse.Builder(200, "OK")
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .addHeader("Content-Encoding", "gzip")
                .addHeader("Content-Length", Integer.toString(encodedPayload.length))
                .body(MediaType.HTML, encodedPayload)
                .build();
    }

    public static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(data);
        }
        return baos.toByteArray();
    }

    public static WarcResponse responseRecord(URI id, byte[] payload, WarcDigest digest) throws IOException {
        return new WarcResponse.Builder(TARGET)
                .recordId(id)
                .date(DATE)
                .payloadDigest(digest)
                .body(httpResponse(payload))
                .build();
    }

    public static WarcResource resourceRecord(URI id, byte[] payload, WarcDigest digest) {
        return new WarcResource.Builder(TARGET)
                .recordId(id)
                .date(DATE)
                .payloadDigest(digest)
                .body(MediaType.HTML, payload)
                .build();
    }

    public static WarcRevisit revisitRecord(URI id, URI refersTo, WarcDigest digest, URI profile) {
        return new WarcRevisit.Builder(TARGET, profile)
                .recordId(id)
                .date(DATE)
                .payloadDigest(digest)
                .refersTo(refersTo)
                .build();
    }

    public static WarcRevisit revisitRecord(URI id, URI refersTo, WarcDigest digest) {
        return revisitRecord(id, refersTo, digest, WarcRevisit.IDENTICAL_PAYLOAD_DIGEST_1_1);
    }

    public static WarcRevisit serverNotModifiedRevisit(URI id, URI refersTo) {
        return new WarcRevisit.Builder(TARGET, WarcRevisit.SERVER_NOT_MODIFIED_1_1)
                .recordId(id)
                .date(DATE)
                .refersTo(refersTo)
                .build();
    }

    public static void writeWarc(Path path, WarcCompression compression, WarcRecord... records) throws IOException {
        if (compression == WarcCompression.NONE) {
            try (WarcWriter writer = new WarcWriter(path)) {
                writeAll(writer, records);
            }
        } else {
            try (java.nio.channels.FileChannel fileChannel =
                         java.nio.channels.FileChannel.open(path,
                                 java.nio.file.StandardOpenOption.WRITE,
                                 java.nio.file.StandardOpenOption.CREATE,
                                 java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                 WarcWriter writer = new WarcWriter(fileChannel, compression)) {
                writeAll(writer, records);
            }
        }
    }

    private static void writeAll(WarcWriter writer, WarcRecord... records) throws IOException {
        for (WarcRecord record : records) {
            writer.write(record);
        }
    }

    /**
     * Writes a single-stream gzip WARC: all records compressed together inside one gzip member rather than
     * record-at-a-time.
     */
    public static void writeStreamingGzipWarc(Path path, WarcRecord... records) throws IOException {
        try (GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(path));
             WarcWriter writer = new WarcWriter(Channels.newChannel(gz), WarcCompression.NONE)) {
            for (WarcRecord record : records) {
                writer.write(record);
            }
        }
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] readBody(MessageBody body) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        WritableByteChannel out = Channels.newChannel(baos);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(8192);
        while (body.read(buffer) > -1) {
            buffer.flip();
            out.write(buffer);
            buffer.compact();
        }
        return baos.toByteArray();
    }
}
