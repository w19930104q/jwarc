/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2026 National Library of Australia and the jwarc contributors
 */

package org.netpreserve.jwarc;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

/**
 * The result of successfully resolving a {@link WarcRevisit}: the original payload recovered from the terminal
 * <code>response</code> or <code>resource</code> record together with the reference chain that led to it.
 * <p>
 * The payload is streamed, never fully buffered. When at least one record in the chain declared a
 * <code>WARC-Payload-Digest</code>, reading the stored bytes from {@link #body()} also calculates the payload
 * digest; call {@link #verifyDigest()} after the body has been fully read to check it against the declared digest.
 * Closing an instance releases the underlying WARC file channel.
 */
public class ResolvedPayload implements Closeable {
    private final RevisitResolver resolver;
    private final WarcReader reader;
    private final FileChannel fileChannel;
    private final WarcRecord terminal;
    private final List<URI> chain;
    private final MessageBody rawPayload;
    private final MessageDigest digester;
    private final WarcDigest expectedDigest;
    private MessageBody body;
    private MessageBody decodedBody;
    private boolean closed = false;

    ResolvedPayload(RevisitResolver resolver, WarcReader reader, FileChannel fileChannel, WarcRecord terminal,
                    List<URI> chain, MessageBody rawPayload, MessageDigest digester, WarcDigest expectedDigest) {
        this.resolver = resolver;
        this.reader = reader;
        this.fileChannel = fileChannel;
        this.terminal = terminal;
        this.chain = chain;
        this.rawPayload = rawPayload;
        this.digester = digester;
        this.expectedDigest = expectedDigest;
    }

    /**
     * The WARC-Record-IDs of the reference chain, starting with the revisit record and ending with the terminal
     * response or resource record.
     */
    public List<URI> referenceChain() {
        return Collections.unmodifiableList(chain);
    }

    /**
     * The terminal response or resource record the payload was read from.
     */
    public WarcRecord terminalRecord() {
        return terminal;
    }

    /**
     * The declared payload digest that will be checked by {@link #verifyDigest()}, or null if no record in the
     * chain declared one.
     */
    public WarcDigest expectedDigest() {
        return expectedDigest;
    }

    /**
     * A channel over the stored payload bytes of the terminal record. For an HTTP response this is the HTTP
     * message body before any Content-Encoding decoding, which is exactly the bytes a WARC-Payload-Digest is
     * calculated over. When verification is enabled the returned channel updates the running digest as it is read.
     * <p>
     * This channel and {@link #decodedBody()} draw from the same underlying bytes, so only one of them may be read
     * for a given payload, and it must be fully consumed (or this instance closed) before resolving another payload
     * with the same resolver.
     */
    public MessageBody body() {
        if (body == null) {
            body = digester != null ? new DigestingMessageBody(rawPayload, digester) : rawPayload;
        }
        return body;
    }

    /**
     * A channel over the payload with HTTP Content-Encoding and Transfer-Encoding decoded, equivalent to what
     * <code>extract --payload</code> outputs for an ordinary response record.
     * <p>
     * If the terminal record is not an HTTP response, or it declares no Content-Encoding, this returns the same
     * channel as {@link #body()}. Note that the payload digest is defined over the stored (still encoded) bytes, so
     * for a content-encoded response read {@link #body()} rather than this channel before calling
     * {@link #verifyDigest()}.
     */
    public MessageBody decodedBody() throws IOException {
        if (decodedBody == null) {
            if (terminal instanceof WarcResponse && terminal.contentType().base().equals(MediaType.HTTP)) {
                MessageBody candidate = ((WarcResponse) terminal).http().bodyDecoded();
                decodedBody = candidate == rawPayload ? body() : candidate;
            } else {
                decodedBody = body();
            }
        }
        return decodedBody;
    }

    /**
     * Checks the digest calculated while reading the stored payload against the declared WARC-Payload-Digest.
     * <p>
     * The whole payload should first be read from {@link #body()}. If no record in the reference chain declared a
     * payload digest, or the declared digest uses an algorithm unavailable in this JVM, this method returns without
     * error as there is nothing to verify against.
     *
     * @throws RevisitResolutionException with reason {@link RevisitResolutionException.Reason#DIGEST_MISMATCH} if
     *                                    the calculated digest differs from the declared one
     * @throws IOException                if an I/O error occurs
     */
    public void verifyDigest() throws IOException {
        if (digester == null || expectedDigest == null) return;
        MessageBody readBody = body();
        if (readBody.size() >= 0 && readBody.position() < readBody.size()) {
            throw new IllegalStateException("Payload must be fully read before the digest can be verified");
        }
        checkDigest();
    }

    private void checkDigest() throws RevisitResolutionException {
        WarcDigest actual = new WarcDigest(digester);
        if (!actual.equals(expectedDigest)) {
            throw new RevisitResolutionException(RevisitResolutionException.Reason.DIGEST_MISMATCH,
                    "Calculated payload digest " + actual + " does not match declared digest " + expectedDigest,
                    chain);
        }
    }

    /** Visible for testing: the underlying WARC file channel. */
    FileChannel fileChannel() {
        return fileChannel;
    }

    /**
     * Closes the WARC reader and the underlying file channel. Safe to call more than once.
     */
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            reader.close();
        } finally {
            resolver.release(fileChannel);
            fileChannel.close();
        }
    }
}
