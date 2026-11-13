/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2026 National Library of Australia and the jwarc contributors
 */

package org.netpreserve.jwarc;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;

/**
 * Thrown when the payload of a {@link WarcRevisit} record cannot be resolved locally.
 * <p>
 * The {@link Reason} returned by {@link #reason()} distinguishes between the different ways resolution can fail so
 * that callers can react programmatically rather than having to parse an error message:
 * <ul>
 *   <li>{@link Reason#TARGET_NOT_FOUND} &mdash; no record with the referenced WARC-Record-ID is present in any of the
 *       configured WARC files
 *   <li>{@link Reason#REFERENCE_CYCLE} &mdash; following the WARC-Refers-To chain loops back to a record already seen
 *   <li>{@link Reason#DUPLICATE_RECORD_ID} &mdash; the same WARC-Record-ID is declared by more than one record
 *   <li>{@link Reason#DIGEST_MISMATCH} &mdash; the declared WARC-Payload-Digest values along the reference chain do not
 *       agree, or the digest calculated while reading the resolved payload does not match the declared digest
 *   <li>{@link Reason#UNSUPPORTED_PROFILE} &mdash; a revisit record uses a profile other than the two standard
 *       <code>identical-payload-digest</code> profiles
 *   <li>{@link Reason#UNSUPPORTED_TARGET_TYPE} &mdash; the reference chain ends in a record type that carries no
 *       payload that could be returned
 *   <li>{@link Reason#UNSUPPORTED_COMPRESSION} &mdash; a WARC file uses a compression layout (streaming gzip or
 *       Zstandard) that does not allow seeking to an individual record
 * </ul>
 */
public class RevisitResolutionException extends IOException {
    public enum Reason {
        /** No record with the referenced WARC-Record-ID could be found. */
        TARGET_NOT_FOUND,
        /** The WARC-Refers-To chain contains a cycle. */
        REFERENCE_CYCLE,
        /** More than one record declares the same WARC-Record-ID. */
        DUPLICATE_RECORD_ID,
        /** A declared payload digest did not match another declared digest or the calculated one. */
        DIGEST_MISMATCH,
        /** The revisit record uses a profile other than identical-payload-digest. */
        UNSUPPORTED_PROFILE,
        /** The reference chain ends at a record type that has no payload. */
        UNSUPPORTED_TARGET_TYPE,
        /** The WARC file uses a compression layout that cannot be randomly accessed. */
        UNSUPPORTED_COMPRESSION;
    }

    private final Reason reason;
    private final List<URI> chain;

    public RevisitResolutionException(Reason reason, String message) {
        this(reason, message, Collections.emptyList());
    }

    public RevisitResolutionException(Reason reason, String message, List<URI> chain) {
        super(message);
        this.reason = reason;
        this.chain = Collections.unmodifiableList(chain);
    }

    /**
     * The category of failure that prevented resolution.
     */
    public Reason reason() {
        return reason;
    }

    /**
     * The WARC-Record-IDs visited before the failure was detected, in reference order (the revisit record itself
     * first). Empty unless the failure occurred while walking a reference chain.
     */
    public List<URI> chain() {
        return chain;
    }
}
