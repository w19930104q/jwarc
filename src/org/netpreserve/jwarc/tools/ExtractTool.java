/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2020-2026 National Library of Australia and the jwarc contributors
 */

package org.netpreserve.jwarc.tools;

import org.netpreserve.jwarc.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

public class ExtractTool {

    private enum ExtractAction { RECORD, HEADERS, PAYLOAD }

    /** Exit codes used when revisit payload resolution fails. Distinct values so failures are distinguishable. */
    static final int EXIT_TARGET_NOT_FOUND = 2;
    static final int EXIT_REFERENCE_CYCLE = 3;
    static final int EXIT_DUPLICATE_RECORD_ID = 4;
    static final int EXIT_DIGEST_MISMATCH = 5;
    static final int EXIT_UNSUPPORTED_PROFILE = 6;
    static final int EXIT_UNSUPPORTED_TARGET_OR_COMPRESSION = 7;

    private static void writeWarcHeaders(WritableByteChannel out, WarcRecord record) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(record.version().toString()).append("\r\n");
        record.headers().appendTo(sb);
        sb.append("\r\n");
        out.write(ByteBuffer.wrap(sb.toString().getBytes(UTF_8)));
    }

    private static void writeHttpHeaders(WritableByteChannel out, WarcRecord record) throws IOException {
        if (record instanceof WarcResponse) {
            HttpResponse response = ((WarcResponse) record).http();
            out.write(ByteBuffer.wrap(response.serializeHeader()));
        } else if (record instanceof WarcRequest) {
            HttpRequest request = ((WarcRequest) record).http();
            out.write(ByteBuffer.wrap(request.serializeHeader()));
        }
    }

    private static void writePayload(WritableByteChannel out, WarcRecord record) throws IOException {
        try {
            MessageBody payload;
            if (record instanceof WarcResponse) {
                if (record.contentType().base().equals(MediaType.HTTP)) {
                    HttpResponse response = ((WarcResponse) record).http();
                    payload = response.bodyDecoded();
                } else {
                    payload = ((WarcResponse) record).payload().map(WarcPayload::body).orElse(MessageBody.empty());
                }
            } else if (record instanceof WarcRequest) {
                if (record.contentType().base().equals(MediaType.HTTP)) {
                    HttpRequest request = ((WarcRequest) record).http();
                    payload = request.bodyDecoded();
                } else {
                    payload = ((WarcRequest) record).payload().map(WarcPayload::body).orElse(MessageBody.empty());
                }
            } else {
                payload = record.body();
            }
            writeBody(out, payload);
        } catch (IOException e) {
            System.err.println("Failed to read payload: " + e.getMessage());
        }
    }

    /**
     * Outputs the payload of a revisit record by following its WARC-Refers-To chain through the given WARC files.
     * The decoded body (HTTP Content-Encoding removed) is written in the same encoding semantics as an ordinary
     * --payload extraction. The stored bytes are first streamed through the declared payload digest so that a
     * digest mismatch fails before anything is written to the output.
     *
     * @return a process exit code: 0 on success, otherwise one of the distinct EXIT_* failure codes
     */
    static int writeRevisitPayload(WritableByteChannel out, WarcRevisit revisit, List<Path> warcFiles) {
        try (RevisitResolver resolver = new RevisitResolver(warcFiles)) {
            // first pass: stream the stored payload solely to verify the declared digest, buffering nothing wholesale
            try (ResolvedPayload verification = resolver.resolve(revisit)) {
                MessageBody stored = verification.body();
                ByteBuffer sink = ByteBuffer.allocate(8192);
                while (stored.read(sink) > -1) {
                    sink.clear();
                }
                verification.verifyDigest();
            }
            // second pass: re-open the terminal record and output the decoded body
            try (ResolvedPayload resolved = resolver.resolve(revisit)) {
                writeBody(out, resolved.decodedBody());
            }
            return 0;
        } catch (RevisitResolutionException e) {
            System.err.println("Cannot resolve revisit payload: " + e.getMessage());
            return exitCodeFor(e.reason());
        } catch (IOException e) {
            System.err.println("Failed to read payload: " + e.getMessage());
            return 1;
        }
    }

    static int exitCodeFor(RevisitResolutionException.Reason reason) {
        switch (reason) {
            case TARGET_NOT_FOUND:
                return EXIT_TARGET_NOT_FOUND;
            case REFERENCE_CYCLE:
                return EXIT_REFERENCE_CYCLE;
            case DUPLICATE_RECORD_ID:
                return EXIT_DUPLICATE_RECORD_ID;
            case DIGEST_MISMATCH:
                return EXIT_DIGEST_MISMATCH;
            case UNSUPPORTED_PROFILE:
                return EXIT_UNSUPPORTED_PROFILE;
            case UNSUPPORTED_TARGET_TYPE:
            case UNSUPPORTED_COMPRESSION:
            default:
                return EXIT_UNSUPPORTED_TARGET_OR_COMPRESSION;
        }
    }

    private static void writeBody(WritableByteChannel out, ReadableByteChannel body) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        while (body.read(buffer) > -1) {
            buffer.flip();
            out.write(buffer);
            buffer.compact();
        }
    }

    private static int usage(int exitValue) {
        System.err.println();
        System.err.println("ExtractTool [-h] [--payload [--revisit]] [--headers] [--warc-file FILE]... warc-file offset...");
        System.err.println();
        System.err.println("Options:");
        System.err.println();
        System.err.println(" --concurrent\talso outputs any immediately following concurrent records");
        System.err.println(" --headers\toutput only record (and HTTP) headers");
        System.err.println(" --payload\toutput only record payload, if necessary");
        System.err.println("          \tdecode transfer and/or content encoding");
        System.err.println(" --revisit\twith --payload: resolve revisit records by following the");
        System.err.println("          \tWARC-Refers-To chain to the original response/resource");
        System.err.println(" --warc-file FILE\tadditional WARC file to search for referenced records");
        System.err.println("          \t(may be repeated; the main warc-file is always searched too)");
        return exitValue;
    }

    public static void main(String[] args) throws IOException {
        System.exit(run(args, Channels.newChannel(System.out)));
    }

    /**
     * Runs the tool writing any extracted bytes to <code>out</code> and returns a process exit code.
     *
     * @return 0 on success or a non-zero EXIT_* code on failure
     */
    static int run(String[] args, WritableByteChannel out) throws IOException {
        ExtractAction action = ExtractAction.RECORD;
        Path warcFile = null;
        List<Long> offsets = new ArrayList<>();
        List<Path> additionalFiles = new ArrayList<>();
        boolean extractConcurrent = false;
        boolean revisitMode = false;
        boolean expectingWarcFile = false;
        for (String arg : args) {
            if (expectingWarcFile) {
                Path additional = Paths.get(arg);
                if (!additional.toFile().canRead()) {
                    System.err.println("Cannot read WARC file: " + additional);
                    return usage(1);
                }
                additionalFiles.add(additional);
                expectingWarcFile = false;
                continue;
            }
            switch (arg) {
            case "-h":
            case "--help":
                return usage(0);
            case "--concurrent":
                extractConcurrent = true;
                break;
            case "--headers":
                action = ExtractAction.HEADERS;
                break;
            case "--payload":
                action = ExtractAction.PAYLOAD;
                break;
            case "--revisit":
                revisitMode = true;
                break;
            case "--warc-file":
                expectingWarcFile = true;
                break;
            default:
                if (arg.startsWith("--warc-file=")) {
                    Path additional = Paths.get(arg.substring("--warc-file=".length()));
                    if (!additional.toFile().canRead()) {
                        System.err.println("Cannot read WARC file: " + additional);
                        return usage(1);
                    }
                    additionalFiles.add(additional);
                } else if (warcFile == null) {
                    warcFile = Paths.get(arg);
                    if (!warcFile.toFile().canRead()) {
                        System.err.println("Cannot read WARC file: " + warcFile);
                        return usage(1);
                    }
                } else if (arg.startsWith("-")) {
                    System.err.println("Unknown argument: " + arg);
                    return usage(1);
                } else {
                    try {
                        offsets.add(Long.parseLong(arg));
                    } catch (NumberFormatException e) {
                        System.err.println(e.getMessage());
                        return usage(1);
                    }
                }
            }
        }
        if (expectingWarcFile) {
            System.err.println("--warc-file requires a path argument");
            return usage(1);
        }
        if (warcFile == null || offsets.isEmpty()) {
            return usage(1);
        }
        if (revisitMode && action != ExtractAction.PAYLOAD) {
            System.err.println("--revisit can only be used together with --payload");
            return 1;
        }
        List<Path> resolverFiles = new ArrayList<>();
        resolverFiles.add(warcFile);
        resolverFiles.addAll(additionalFiles);
        for (long offset : offsets) {
            try (FileChannel channel = FileChannel.open(warcFile);
                 WarcReader reader = new WarcReader(channel.position(offset))) {
                Optional<WarcRecord> record = reader.next();
                if (!record.isPresent()) {
                    System.err.println("No record found at position " + offset);
                    return 1;
                }

                int failure = writeRecord(record.get(), out, action, revisitMode, resolverFiles);
                if (failure != 0) return failure;

                if (extractConcurrent) {
                    ConcurrentRecordSet concurrentSet = new ConcurrentRecordSet();
                    while (true) {
                        concurrentSet.add(record.get());
                        record = reader.next();
                        if (!record.isPresent() || !concurrentSet.contains(record.get())) break;
                        failure = writeRecord(record.get(), out, action, revisitMode, resolverFiles);
                        if (failure != 0) return failure;
                    }
                }
            }
        }
        return 0;
    }

    private static int writeRecord(WarcRecord record, WritableByteChannel out, ExtractAction action,
                                   boolean revisitMode, List<Path> resolverFiles) throws IOException {
        switch (action) {
            case RECORD:
                writeWarcHeaders(out, record);
                writeBody(out, record.body());
                out.write(ByteBuffer.wrap("\r\n\r\n".getBytes(US_ASCII)));
                return 0;
            case HEADERS:
                writeWarcHeaders(out, record);
                writeHttpHeaders(out, record);
                return 0;
            case PAYLOAD:
                if (revisitMode && record instanceof WarcRevisit) {
                    return writeRevisitPayload(out, (WarcRevisit) record, resolverFiles);
                }
                writePayload(out, record);
                return 0;
            default:
                throw new IllegalStateException("Unknown action " + action);
        }
    }
}
