/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2026 National Library of Australia and the jwarc contributors
 */

package org.netpreserve.jwarc;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

import static java.nio.file.StandardOpenOption.READ;

/**
 * Resolves the payload of {@link WarcRevisit} records by following their <code>WARC-Refers-To</code> references
 * across one or more local WARC files.
 * <p>
 * Only the standard <code>identical-payload-digest</code> revisit profiles
 * ({@link WarcRevisit#IDENTICAL_PAYLOAD_DIGEST_1_0} and {@link WarcRevisit#IDENTICAL_PAYLOAD_DIGEST_1_1}) are
 * supported. A chain may cross file boundaries, point forwards (to records appearing later) and contain multiple
 * revisit records before terminating at a <code>response</code> or <code>resource</code> record. The order in which
 * files are supplied and the order of records within them never affects the result as all files are indexed first.
 * <p>
 * Both uncompressed WARC files and record-at-a-time gzip compressed WARC files are supported. Streaming gzip
 * (multiple records inside one gzip member) and Zstandard compressed WARCs cannot be randomly accessed and are
 * therefore rejected.
 * <p>
 * Indexing only reads record headers; payload bytes are streamed on demand when {@link #resolve(WarcRevisit)} or
 * {@link #resolve(URI)} succeeds. Payload bytes are never fully buffered, so the extra memory used stays bounded
 * regardless of payload size. Any file channels opened by this class are released by {@link #close()} or by closing
 * the returned {@link ResolvedPayload}.
 * <p>
 * This class performs no network access.
 * <p>
 * This class is not thread safe.
 */
public class RevisitResolver implements Closeable {
    private final List<Path> files;
    private final Map<URI, IndexEntry> index = new HashMap<>();
    private final List<FileChannel> openChannels = new ArrayList<>();
    private boolean indexed = false;
    private boolean closed = false;

    private static final class IndexEntry {
        final int fileIndex;
        final long position;
        final String type;
        final Optional<WarcDigest> payloadDigest;
        final Optional<URI> refersTo;
        final URI profile;
        final boolean recordAtATimeGzip;

        IndexEntry(int fileIndex, long position, String type, Optional<WarcDigest> payloadDigest,
                   Optional<URI> refersTo, URI profile, boolean recordAtATimeGzip) {
            this.fileIndex = fileIndex;
            this.position = position;
            this.type = type;
            this.payloadDigest = payloadDigest;
            this.refersTo = refersTo;
            this.profile = profile;
            this.recordAtATimeGzip = recordAtATimeGzip;
        }
    }

    /**
     * Creates a resolver over the given WARC files. The files are not opened until the first call to
     * {@link #index()}, {@link #resolve(WarcRevisit)} or {@link #resolve(URI)}.
     *
     * @param files the WARC files to search when following references
     */
    public RevisitResolver(List<Path> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("At least one WARC file is required");
        }
        this.files = new ArrayList<>(files);
    }

    /**
     * Creates a resolver over the given WARC files.
     *
     * @see #RevisitResolver(List)
     */
    public RevisitResolver(Path... files) {
        this(toList(files));
    }

    private static List<Path> toList(Path[] files) {
        ArrayList<Path> list = new ArrayList<>();
        Collections.addAll(list, files);
        return list;
    }

    /**
     * Reads the headers of every record in every configured WARC file and builds an in-memory index mapping
     * WARC-Record-ID to the file and offset the record starts at.
     * <p>
     * Payload bytes are skipped, not buffered. Calling this method more than once has no effect.
     *
     * @throws RevisitResolutionException if a duplicate WARC-Record-ID or an unsupported compression layout is found
     * @throws IOException                if a WARC file cannot be read or is corrupt
     */
    public synchronized void index() throws IOException {
        if (indexed) return;
        ensureOpen();
        for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
            indexFile(fileIndex);
        }
        indexed = true;
    }

    private void indexFile(int fileIndex) throws IOException {
        Path path = files.get(fileIndex);
        try (WarcReader reader = new WarcReader(path)) {
            if (reader.compression() == WarcCompression.ZSTD) {
                throw new RevisitResolutionException(RevisitResolutionException.Reason.UNSUPPORTED_COMPRESSION,
                        "Zstandard compressed WARC files cannot be randomly accessed, cannot resolve revisit "
                                + "references in " + path);
            }
            boolean recordAtATimeGzip = true;
            while (true) {
                Optional<WarcRecord> maybeRecord;
                try {
                    maybeRecord = reader.next();
                } catch (java.io.EOFException e) {
                    if (isAtCleanGzipBoundary(reader)) break;
                    throw e;
                } catch (ParsingException e) {
                    if (isAtCleanGzipBoundary(reader)) break;
                    throw e;
                }
                if (!maybeRecord.isPresent()) break;
                WarcRecord record = maybeRecord.get();
                if (reader.compression() == WarcCompression.GZIP && reader.misalignedRecords() > 0) {
                    recordAtATimeGzip = false;
                }
                URI id = record.id();
                if (index.containsKey(id)) {
                    throw new RevisitResolutionException(RevisitResolutionException.Reason.DUPLICATE_RECORD_ID,
                            "Duplicate WARC-Record-ID " + id + " found in " + path);
                }
                Optional<WarcDigest> payloadDigest = Optional.empty();
                Optional<URI> refersTo = Optional.empty();
                URI profile = null;
                if (record instanceof WarcTargetRecord) {
                    payloadDigest = ((WarcTargetRecord) record).payloadDigest();
                }
                if (record instanceof WarcRevisit) {
                    WarcRevisit revisit = (WarcRevisit) record;
                    refersTo = revisit.refersTo();
                    profile = revisit.profile();
                }
                index.put(id, new IndexEntry(fileIndex, record.position(), record.type(), payloadDigest,
                        refersTo, profile, recordAtATimeGzip));
                record.body().consume();
            }
        }
    }

    /**
     * Resolves the payload of the given revisit record. The record must be present in one of the configured WARC
     * files (matched by its WARC-Record-ID); the passed instance is only used to identify the starting record.
     *
     * @param revisit the revisit record whose payload should be recovered
     * @return the resolved payload along with the reference chain
     * @throws RevisitResolutionException if the reference chain cannot be followed to a response or resource
     * @throws IOException                if a WARC file cannot be read or is corrupt
     */
    public synchronized ResolvedPayload resolve(WarcRevisit revisit) throws IOException {
        Objects.requireNonNull(revisit);
        return resolve(revisit.id());
    }

    /**
     * Resolves the payload of the revisit record with the given WARC-Record-ID.
     *
     * @param recordId WARC-Record-ID of a revisit record in one of the configured files
     * @return the resolved payload along with the reference chain
     * @throws RevisitResolutionException if no record with that ID exists, it is not a revisit or the reference chain
     *                                    cannot be followed to a response or resource
     * @throws IOException                if a WARC file cannot be read or is corrupt
     */
    public synchronized ResolvedPayload resolve(URI recordId) throws IOException {
        Objects.requireNonNull(recordId);
        ensureOpen();
        index();
        IndexEntry entry = index.get(recordId);
        if (entry == null) {
            throw new RevisitResolutionException(RevisitResolutionException.Reason.TARGET_NOT_FOUND,
                    "No record found with WARC-Record-ID " + recordId);
        }
        if (!"revisit".equals(entry.type)) {
            throw new RevisitResolutionException(RevisitResolutionException.Reason.UNSUPPORTED_TARGET_TYPE,
                    "Record " + recordId + " is of type " + entry.type + ", not revisit");
        }
        List<URI> chain = new ArrayList<>();
        chain.add(recordId);
        if (!isRecordAtATimeGzip(entry)) {
            throw unsupportedCompression(entry);
        }
        if (!isIdenticalPayloadDigestProfile(entry.profile)) {
            throw new RevisitResolutionException(RevisitResolutionException.Reason.UNSUPPORTED_PROFILE,
                    "Unsupported revisit profile " + entry.profile + " on record " + recordId, chain);
        }
        URI nextId = entry.refersTo
                .orElseThrow(() -> new RevisitResolutionException(RevisitResolutionException.Reason.TARGET_NOT_FOUND,
                        "Revisit record " + recordId + " has no WARC-Refers-To header", chain));
        return walkChain(nextId, entry.payloadDigest, chain);
    }

    private ResolvedPayload walkChain(URI nextId, Optional<WarcDigest> expectedDigest, List<URI> chain)
            throws IOException {
        URI currentId = nextId;
        while (true) {
            if (chain.contains(currentId)) {
                throw new RevisitResolutionException(RevisitResolutionException.Reason.REFERENCE_CYCLE,
                        "Reference cycle detected at record " + currentId, chain);
            }
            IndexEntry entry = index.get(currentId);
            if (entry == null) {
                throw new RevisitResolutionException(RevisitResolutionException.Reason.TARGET_NOT_FOUND,
                        "Referenced record " + currentId + " was not found in any of the configured WARC files",
                        chain);
            }
            if (!isRecordAtATimeGzip(entry)) {
                throw unsupportedCompression(entry);
            }
            chain.add(currentId);

            if (entry.payloadDigest.isPresent() && expectedDigest.isPresent()
                    && !entry.payloadDigest.get().equals(expectedDigest.get())) {
                throw new RevisitResolutionException(RevisitResolutionException.Reason.DIGEST_MISMATCH,
                        "Declared payload digest of " + currentId + " (" + entry.payloadDigest.get()
                                + ") does not match the digest declared earlier in the chain ("
                                + expectedDigest.get() + ")", chain);
            }
            if (!expectedDigest.isPresent()) {
                expectedDigest = entry.payloadDigest;
            }

            if ("revisit".equals(entry.type)) {
                if (!isIdenticalPayloadDigestProfile(entry.profile)) {
                    throw new RevisitResolutionException(RevisitResolutionException.Reason.UNSUPPORTED_PROFILE,
                            "Unsupported revisit profile " + entry.profile + " on record " + currentId, chain);
                }
                final URI revisitId = currentId;
                URI refersTo = entry.refersTo.orElseThrow(
                        () -> new RevisitResolutionException(RevisitResolutionException.Reason.TARGET_NOT_FOUND,
                                "Revisit record " + revisitId + " has no WARC-Refers-To header", chain));
                currentId = refersTo;
                continue;
            }

            if ("response".equals(entry.type) || "resource".equals(entry.type)) {
                return openTerminal(entry, chain, expectedDigest);
            }

            throw new RevisitResolutionException(RevisitResolutionException.Reason.UNSUPPORTED_TARGET_TYPE,
                    "Reference chain ends at record " + currentId + " of unsupported type " + entry.type, chain);
        }
    }

    private boolean isRecordAtATimeGzip(IndexEntry entry) {
        return entry.recordAtATimeGzip;
    }

    private RevisitResolutionException unsupportedCompression(IndexEntry entry) {
        return new RevisitResolutionException(RevisitResolutionException.Reason.UNSUPPORTED_COMPRESSION,
                "Record at offset " + entry.position + " in " + files.get(entry.fileIndex) + " is in a WARC file "
                        + "that cannot be randomly accessed (streaming gzip or Zstandard)");
    }

    private static boolean isIdenticalPayloadDigestProfile(URI profile) {
        return WarcRevisit.IDENTICAL_PAYLOAD_DIGEST_1_0.equals(profile)
                || WarcRevisit.IDENTICAL_PAYLOAD_DIGEST_1_1.equals(profile);
    }

    /**
     * A gzip reader positioned exactly on a member boundary at EOF has consumed every complete member and is simply
     * out of data: a normal end of file. Anywhere else an EOF or parse error means a truncated or corrupt member.
     */
    private static boolean isAtCleanGzipBoundary(WarcReader reader) {
        return reader.channel() instanceof GunzipChannel
                && ((GunzipChannel) reader.channel()).atMemberBoundary();
    }

    private ResolvedPayload openTerminal(IndexEntry entry, List<URI> chain, Optional<WarcDigest> expectedDigest)
            throws IOException {
        FileChannel fileChannel = FileChannel.open(files.get(entry.fileIndex), READ);
        openChannels.add(fileChannel);
        boolean handedOff = false;
        try {
            WarcReader reader = new WarcReader(fileChannel.position(entry.position));
            WarcRecord terminal = reader.next().orElseThrow(() -> new IOException(
                    "Indexed record at offset " + entry.position + " in " + files.get(entry.fileIndex)
                            + " could not be read"));
            MessageBody rawPayload = rawPayload(terminal);
            MessageDigest digester = null;
            if (expectedDigest.isPresent()) {
                try {
                    digester = expectedDigest.get().getDigester();
                } catch (NoSuchAlgorithmException e) {
                    // unknown digest algorithm: nothing we can verify, stream the payload without digesting
                }
            }
            ResolvedPayload resolved = new ResolvedPayload(this, reader, fileChannel, terminal,
                    new ArrayList<>(chain), rawPayload, digester, expectedDigest.orElse(null));
            handedOff = true;
            return resolved;
        } finally {
            // if construction failed before the ResolvedPayload took ownership, release the channel ourselves
            if (!handedOff) {
                openChannels.remove(fileChannel);
                fileChannel.close();
            }
        }
    }

    private static MessageBody rawPayload(WarcRecord terminal) throws IOException {
        if (terminal instanceof WarcResponse) {
            WarcResponse response = (WarcResponse) terminal;
            if (response.contentType().base().equals(MediaType.HTTP)) {
                return response.http().body();
            }
            return response.payload().map(WarcPayload::body).orElse(terminal.body());
        } else if (terminal instanceof WarcResource) {
            return terminal.body();
        } else {
            throw new RevisitResolutionException(RevisitResolutionException.Reason.UNSUPPORTED_TARGET_TYPE,
                    "Reference chain ends at record " + terminal.id() + " of unsupported type " + terminal.type());
        }
    }

    void release(FileChannel channel) {
        openChannels.remove(channel);
    }

    /** Visible for testing: whether this resolver currently holds the given channel open. */
    boolean holdsChannel(FileChannel channel) {
        return openChannels.contains(channel);
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("RevisitResolver has already been closed");
        }
    }

    /**
     * Closes every file channel still held by this resolver, including channels belonging to
     * {@link ResolvedPayload} instances that have not themselves been closed.
     */
    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (FileChannel channel : new ArrayList<>(openChannels)) {
            try {
                channel.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
            }
        }
        openChannels.clear();
        if (failure != null) throw failure;
    }
}
