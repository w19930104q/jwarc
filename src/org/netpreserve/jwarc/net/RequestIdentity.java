package org.netpreserve.jwarc.net;

import org.netpreserve.jwarc.HttpRequest;
import org.netpreserve.jwarc.MediaType;
import org.netpreserve.jwarc.MessageBody;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/**
 * An identity used to match a replayed HTTP request against an archived {@link org.netpreserve.jwarc.WarcRequest}.
 * <p>
 * The identity consists of:
 * <ul>
 *     <li>the target URI</li>
 *     <li>the request method</li>
 *     <li>the base Content-Type (type and subtype without parameters)</li>
 *     <li>a digest of the complete, raw request body</li>
 * </ul>
 * Because the raw body bytes are digested, requests whose parameters appear in a different order are considered
 * different identities.
 * <p>
 * The body is digested with a fixed-size buffer so that arbitrarily large request bodies can be handled without
 * retaining the body in memory. Bodies larger than {@link #MAX_BODY_BYTES} are rejected with
 * {@link BodyTooLargeException}.
 */
class RequestIdentity {
    /**
     * Maximum size of request body that will be read while calculating an identity (16 MiB).
     */
    static final long MAX_BODY_BYTES = 16L * 1024 * 1024;

    private static final int BUFFER_SIZE = 8192;
    private static final String DIGEST_ALGORITHM = "SHA-256";

    private final String uri;
    private final String method;
    private final MediaType baseContentType;
    private final byte[] bodyDigest;

    RequestIdentity(String uri, String method, MediaType baseContentType, byte[] bodyDigest) {
        this.uri = uri;
        this.method = method;
        this.baseContentType = baseContentType;
        this.bodyDigest = bodyDigest;
    }

    /**
     * Calculates the identity of an HTTP request. This method fully consumes (but does not retain) the request body.
     *
     * @param uri     the absolute target URI of the request
     * @param request the parsed HTTP request
     * @throws BodyTooLargeException if the request body exceeds {@link #MAX_BODY_BYTES}
     */
    static RequestIdentity from(String uri, HttpRequest request) throws IOException {
        MessageDigest digest = newDigest();
        MessageBody body = request.body();
        long declaredSize = body.size();
        if (declaredSize >= 0 && declaredSize > MAX_BODY_BYTES) {
            throw new BodyTooLargeException(uri, declaredSize);
        }
        digestBody(uri, body, digest);
        return new RequestIdentity(uri, request.method(), request.contentType().base(), digest.digest());
    }

    private static void digestBody(String uri, MessageBody body, MessageDigest digest) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        long total = 0;
        while (true) {
            buffer.clear();
            int n = body.read(buffer);
            if (n < 0) break;
            total += n;
            if (total > MAX_BODY_BYTES) {
                throw new BodyTooLargeException(uri, total);
            }
            buffer.flip();
            digest.update(buffer);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " digest not available", e);
        }
    }

    String uri() {
        return uri;
    }

    String method() {
        return method;
    }

    MediaType baseContentType() {
        return baseContentType;
    }

    byte[] bodyDigest() {
        return bodyDigest.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RequestIdentity that = (RequestIdentity) o;
        return Objects.equals(uri, that.uri) &&
                Objects.equals(method, that.method) &&
                Objects.equals(baseContentType, that.baseContentType) &&
                Arrays.equals(bodyDigest, that.bodyDigest);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(uri, method, baseContentType);
        result = 31 * result + Arrays.hashCode(bodyDigest);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(uri);
        sb.append(" content-type=").append(baseContentType);
        sb.append(" body-").append(DIGEST_ALGORITHM).append('=');
        for (byte b : bodyDigest) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /**
     * Thrown when a request body is larger than {@link #MAX_BODY_BYTES} and so cannot be used to calculate an
     * identity.
     */
    static class BodyTooLargeException extends IOException {
        BodyTooLargeException(String uri, long size) {
            super("Request body for " + uri + " is " + size + " bytes which exceeds the identity limit of "
                    + MAX_BODY_BYTES + " bytes");
        }
    }
}
