/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2026 National Library of Australia and the jwarc contributors
 */

package org.netpreserve.jwarc;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.Assert.*;

public class RevisitResolverTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path newWarc(String name) throws java.io.IOException {
        return temporaryFolder.newFile(name).toPath();
    }

    private static byte[] readAll(MessageBody body) throws Exception {
        return RevisitTestFixtures.readBody(body);
    }

    @Test
    public void resolvesDirectRevisitToResponse() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("<html>hello</html>");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("direct.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest),
                RevisitTestFixtures.responseRecord(responseId, payload, digest));

        try (RevisitResolver resolver = new RevisitResolver(warc);
             ResolvedPayload resolved = resolver.resolve(revisitId)) {
            assertEquals(Arrays.asList(revisitId, responseId), resolved.referenceChain());
            assertArrayEquals(payload, readAll(resolved.body()));
            resolved.verifyDigest();
            assertTrue(resolved.terminalRecord() instanceof WarcResponse);
            assertEquals(digest, resolved.expectedDigest());
        }
    }

    @Test
    public void fileAndRecordOrderDoesNotMatter() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("order independent payload");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path first = newWarc("a.warc");
        Path second = newWarc("b.warc");
        // revisit in the first file, target in a different file, supplied in reverse order
        RevisitTestFixtures.writeWarc(second, WarcCompression.NONE,
                RevisitTestFixtures.responseRecord(responseId, payload, digest));
        RevisitTestFixtures.writeWarc(first, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest));

        try (RevisitResolver resolver = new RevisitResolver(Arrays.asList(second, first));
             ResolvedPayload resolved = resolver.resolve(revisitId)) {
            assertArrayEquals(payload, readAll(resolved.body()));
            assertEquals(Arrays.asList(revisitId, responseId), resolved.referenceChain());
        }
    }

    @Test
    public void resolvesMultiLevelChainAcrossFiles() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("multi level");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI resourceId = RevisitTestFixtures.uuid();
        URI middleId = RevisitTestFixtures.uuid();
        URI topId = RevisitTestFixtures.uuid();
        Path f1 = newWarc("f1.warc");
        Path f2 = newWarc("f2.warc");
        Path f3 = newWarc("f3.warc");
        RevisitTestFixtures.writeWarc(f1, WarcCompression.NONE,
                RevisitTestFixtures.resourceRecord(resourceId, payload, digest));
        RevisitTestFixtures.writeWarc(f2, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(middleId, resourceId, digest));
        RevisitTestFixtures.writeWarc(f3, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(topId, middleId, digest));

        try (RevisitResolver resolver = new RevisitResolver(f3, f2, f1);
             ResolvedPayload resolved = resolver.resolve(topId)) {
            assertEquals(Arrays.asList(topId, middleId, resourceId), resolved.referenceChain());
            assertArrayEquals(payload, readAll(resolved.body()));
            resolved.verifyDigest();
            assertTrue(resolved.terminalRecord() instanceof WarcResource);
        }
    }

    @Test
    public void resolvesThroughRecordAtATimeGzip() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("gzipped warc payload");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path gz1 = newWarc("one.warc.gz");
        Path gz2 = newWarc("two.warc.gz");
        RevisitTestFixtures.writeWarc(gz1, WarcCompression.GZIP,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest));
        RevisitTestFixtures.writeWarc(gz2, WarcCompression.GZIP,
                RevisitTestFixtures.responseRecord(responseId, payload, digest));

        try (WarcReader reader = new WarcReader(gz1)) {
            assertEquals(WarcCompression.GZIP, reader.compression());
            assertEquals(0, reader.misalignedRecords());
        }

        try (RevisitResolver resolver = new RevisitResolver(Arrays.asList(gz1, gz2));
             ResolvedPayload resolved = resolver.resolve(revisitId)) {
            assertArrayEquals(payload, readAll(resolved.body()));
            resolved.verifyDigest();
        }
    }

    @Test
    public void rejectsStreamingGzip() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("streaming gzip");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("streaming.warc.gz");
        RevisitTestFixtures.writeStreamingGzipWarc(warc,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest),
                RevisitTestFixtures.responseRecord(responseId, payload, digest));

        try (WarcReader reader = new WarcReader(warc)) {
            reader.next();
            reader.next();
            assertTrue(reader.misalignedRecords() > 0);
        }

        try (RevisitResolver resolver = new RevisitResolver(Collections.singletonList(warc))) {
            RevisitResolutionException e = assertThrows(RevisitResolutionException.class,
                    () -> resolver.resolve(revisitId));
            assertEquals(RevisitResolutionException.Reason.UNSUPPORTED_COMPRESSION, e.reason());
        }
    }

    @Test
    public void missingTargetFailsWithDistinctReason() throws Exception {
        WarcDigest digest = RevisitTestFixtures.sha1(RevisitTestFixtures.utf8("x"));
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("missing.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, RevisitTestFixtures.uuid(), digest));

        try (RevisitResolver resolver = new RevisitResolver(warc)) {
            RevisitResolutionException e = assertThrows(RevisitResolutionException.class,
                    () -> resolver.resolve(revisitId));
            assertEquals(RevisitResolutionException.Reason.TARGET_NOT_FOUND, e.reason());
            assertEquals(Collections.singletonList(revisitId), e.chain());
        }
    }

    @Test
    public void referenceCycleFailsWithDistinctReason() throws Exception {
        WarcDigest digest = RevisitTestFixtures.sha1(RevisitTestFixtures.utf8("x"));
        URI a = RevisitTestFixtures.uuid();
        URI b = RevisitTestFixtures.uuid();
        Path warc = newWarc("cycle.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(a, b, digest),
                RevisitTestFixtures.revisitRecord(b, a, digest));

        try (RevisitResolver resolver = new RevisitResolver(warc)) {
            RevisitResolutionException e = assertThrows(RevisitResolutionException.class,
                    () -> resolver.resolve(a));
            assertEquals(RevisitResolutionException.Reason.REFERENCE_CYCLE, e.reason());
            assertEquals(Arrays.asList(a, b), e.chain());
        }
    }

    @Test
    public void duplicateRecordIdFailsAtIndexTime() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("dup");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI sameId = RevisitTestFixtures.uuid();
        Path warc = newWarc("dup.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.resourceRecord(sameId, payload, digest),
                RevisitTestFixtures.resourceRecord(sameId, payload, digest));

        try (RevisitResolver resolver = new RevisitResolver(warc)) {
            RevisitResolutionException e = assertThrows(RevisitResolutionException.class, resolver::index);
            assertEquals(RevisitResolutionException.Reason.DUPLICATE_RECORD_ID, e.reason());
        }
    }

    @Test
    public void conflictingDeclaredDigestsFail() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("payload");
        WarcDigest actual = RevisitTestFixtures.sha1(payload);
        WarcDigest wrong = RevisitTestFixtures.sha1(RevisitTestFixtures.utf8("different"));
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("conflict.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, wrong),
                RevisitTestFixtures.responseRecord(responseId, payload, actual));

        try (RevisitResolver resolver = new RevisitResolver(warc)) {
            RevisitResolutionException e = assertThrows(RevisitResolutionException.class,
                    () -> resolver.resolve(revisitId));
            assertEquals(RevisitResolutionException.Reason.DIGEST_MISMATCH, e.reason());
        }
    }

    @Test
    public void calculatedDigestMismatchFails() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("actual stored payload");
        WarcDigest wrong = RevisitTestFixtures.sha1(RevisitTestFixtures.utf8("something else"));
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("baddigest.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, wrong),
                RevisitTestFixtures.responseRecord(responseId, payload, wrong));

        try (RevisitResolver resolver = new RevisitResolver(warc);
             ResolvedPayload resolved = resolver.resolve(revisitId)) {
            assertArrayEquals(payload, readAll(resolved.body()));
            RevisitResolutionException e = assertThrows(RevisitResolutionException.class,
                    resolved::verifyDigest);
            assertEquals(RevisitResolutionException.Reason.DIGEST_MISMATCH, e.reason());
        }
    }

    @Test
    public void unsupportedProfileFails() throws Exception {
        URI targetId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("profile.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.serverNotModifiedRevisit(revisitId, targetId));

        try (RevisitResolver resolver = new RevisitResolver(warc)) {
            RevisitResolutionException e = assertThrows(RevisitResolutionException.class,
                    () -> resolver.resolve(revisitId));
            assertEquals(RevisitResolutionException.Reason.UNSUPPORTED_PROFILE, e.reason());
        }
    }

    @Test
    public void unsupportedTerminalTypeFails() throws Exception {
        WarcDigest digest = RevisitTestFixtures.sha1(RevisitTestFixtures.utf8("x"));
        URI metadataId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        WarcMetadata metadata = new WarcMetadata.Builder()
                .recordId(metadataId)
                .date(RevisitTestFixtures.DATE)
                .body(MediaType.OCTET_STREAM, RevisitTestFixtures.utf8("meta"))
                .build();
        Path warc = newWarc("badtype.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, metadataId, digest), metadata);

        try (RevisitResolver resolver = new RevisitResolver(warc)) {
            RevisitResolutionException e = assertThrows(RevisitResolutionException.class,
                    () -> resolver.resolve(revisitId));
            assertEquals(RevisitResolutionException.Reason.UNSUPPORTED_TARGET_TYPE, e.reason());
        }
    }

    @Test
    public void corruptedWarcRaisesIOException() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("corrupt me");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("corrupt.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest),
                RevisitTestFixtures.responseRecord(responseId, payload, digest));
        // truncate the file, cutting off the terminal record
        try (FileChannel ch = FileChannel.open(warc, WRITE)) {
            ch.truncate(ch.size() - 20);
        }

        try (RevisitResolver resolver = new RevisitResolver(warc)) {
            assertThrows(java.io.IOException.class, () -> resolver.resolve(revisitId));
        }
    }

    @Test
    public void truncatedGzipMemberRaisesIOException() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("truncated gzip payload");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path revisitWarc = newWarc("rev.warc");
        Path responseGz = newWarc("resp.warc.gz");
        RevisitTestFixtures.writeWarc(revisitWarc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest));
        RevisitTestFixtures.writeWarc(responseGz, WarcCompression.GZIP,
                RevisitTestFixtures.responseRecord(responseId, payload, digest));
        // drop the final 15 bytes, cutting off the second half of the sole gzip member (not on a member boundary)
        byte[] gz = Files.readAllBytes(responseGz);
        Files.write(responseGz, Arrays.copyOfRange(gz, 0, gz.length - 15));

        try (RevisitResolver resolver = new RevisitResolver(Arrays.asList(revisitWarc, responseGz))) {
            assertThrows(java.io.IOException.class, () -> resolver.resolve(revisitId));
        }
    }

    @Test
    public void malformedHttpResponseFailsWithIoException() throws Exception {
        WarcDigest digest = RevisitTestFixtures.sha1(RevisitTestFixtures.utf8("x"));
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        // a response record whose block is not valid HTTP
        WarcResponse broken = new WarcResponse.Builder(RevisitTestFixtures.TARGET)
                .recordId(responseId)
                .date(RevisitTestFixtures.DATE)
                .payloadDigest(digest)
                .body(MediaType.HTTP_RESPONSE, RevisitTestFixtures.utf8("not an HTTP response at all"))
                .build();
        Path warc = newWarc("badhttp.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest), broken);

        try (RevisitResolver resolver = new RevisitResolver(warc)) {
            assertThrows(java.io.IOException.class, () -> resolver.resolve(revisitId));
        }
    }

    @Test
    public void corruptedGzipMemberRaisesIOException() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("corrupt gzip payload");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path revisitWarc = newWarc("rev.warc");
        Path responseGz = newWarc("resp.warc.gz");
        RevisitTestFixtures.writeWarc(revisitWarc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest));
        RevisitTestFixtures.writeWarc(responseGz, WarcCompression.GZIP,
                RevisitTestFixtures.responseRecord(responseId, payload, digest));
        // flip a byte inside the deflate stream, after the gzip header
        byte[] gz = Files.readAllBytes(responseGz);
        gz[40] ^= (byte) 0xff;
        Files.write(responseGz, gz);

        // the corruption is detected either while indexing or while streaming the payload
        RevisitResolver resolver = new RevisitResolver(Arrays.asList(revisitWarc, responseGz));
        try {
            ResolvedPayload resolved;
            try {
                resolved = resolver.resolve(revisitId);
            } catch (java.io.IOException e) {
                resolver.close();
                return; // detected during indexing
            }
            try (ResolvedPayload r = resolved) {
                assertThrows(java.io.IOException.class, () -> readAll(r.body()));
            }
        } finally {
            resolver.close();
        }
    }

    @Test
    public void resolvesMultiLevelChainAcrossPlainAndGzipFiles() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("mixed compression chain");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI resourceId = RevisitTestFixtures.uuid();
        URI middleId = RevisitTestFixtures.uuid();
        URI topId = RevisitTestFixtures.uuid();
        Path plain = newWarc("plain.warc");
        Path gz = newWarc("gzipped.warc.gz");
        RevisitTestFixtures.writeWarc(plain, WarcCompression.NONE,
                RevisitTestFixtures.resourceRecord(resourceId, payload, digest),
                RevisitTestFixtures.revisitRecord(topId, middleId, digest));
        RevisitTestFixtures.writeWarc(gz, WarcCompression.GZIP,
                RevisitTestFixtures.revisitRecord(middleId, resourceId, digest));

        try (RevisitResolver resolver = new RevisitResolver(Arrays.asList(plain, gz));
             ResolvedPayload resolved = resolver.resolve(topId)) {
            assertEquals(Arrays.asList(topId, middleId, resourceId), resolved.referenceChain());
            assertArrayEquals(payload, readAll(resolved.body()));
            resolved.verifyDigest();
        }
    }

    @Test
    public void largePayloadIsStreamedWithBoundedMemory() throws Exception {
        // 5 MiB payload, much larger than the 8 KiB transfer buffers
        int size = 5 * 1024 * 1024;
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) payload[i] = (byte) (i % 251);
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("large.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest),
                RevisitTestFixtures.responseRecord(responseId, payload, digest));

        try (RevisitResolver resolver = new RevisitResolver(warc);
             ResolvedPayload resolved = resolver.resolve(revisitId)) {
            MessageBody body = resolved.body();
            byte[] firstChunk = new byte[1024];
            int read = 0;
            while (read < firstChunk.length) {
                int n = body.read(java.nio.ByteBuffer.wrap(firstChunk, read, firstChunk.length - read));
                if (n < 0) break;
                read += n;
            }
            assertEquals(1024, read);
            assertArrayEquals(java.util.Arrays.copyOfRange(payload, 0, 1024), firstChunk);
            // drain the rest in small buffers to prove nothing is buffered wholesale
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(4096);
            int total = read;
            while (body.read(buffer) > -1) {
                total += buffer.position();
                buffer.clear();
            }
            assertEquals(size, total);
            resolved.verifyDigest();
        }
    }

    @Test
    public void closingResolvedPayloadReleasesFileChannel() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("close me");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("close.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest),
                RevisitTestFixtures.responseRecord(responseId, payload, digest));

        RevisitResolver resolver = new RevisitResolver(warc);
        ResolvedPayload resolved = resolver.resolve(revisitId);
        FileChannel channel = resolved.fileChannel();
        assertTrue(channel.isOpen());
        assertTrue(resolver.holdsChannel(channel));
        readAll(resolved.body());
        resolved.verifyDigest();
        resolved.close();
        assertFalse(channel.isOpen());
        assertFalse(resolver.holdsChannel(channel));
        resolver.close();

        // the file must remain deletable on all platforms once everything is closed
        Files.delete(warc);
        assertFalse(Files.exists(warc));
    }

    @Test
    public void closingResolverReleasesUnclosedPayloadChannel() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("closed via resolver");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("close2.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest),
                RevisitTestFixtures.responseRecord(responseId, payload, digest));

        RevisitResolver resolver = new RevisitResolver(warc);
        ResolvedPayload resolved = resolver.resolve(revisitId);
        FileChannel channel = resolved.fileChannel();
        assertTrue(channel.isOpen());
        assertNotNull(resolved);
        resolver.close(); // without closing resolved first
        assertFalse(channel.isOpen());
        assertFalse(resolver.holdsChannel(channel));
        Files.delete(warc);
        assertFalse(Files.exists(warc));
    }

    @Test
    public void contentEncodedResponseDecodesForDecodedBodyButVerifiesStoredBytes() throws Exception {
        byte[] decoded = RevisitTestFixtures.utf8("<html>encoded content</html>");
        byte[] encoded = RevisitTestFixtures.gzip(decoded);
        WarcDigest digest = RevisitTestFixtures.sha1(encoded); // payload digest is over the stored bytes
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("encoded.warc");
        WarcResponse response = new WarcResponse.Builder(RevisitTestFixtures.TARGET)
                .recordId(responseId)
                .date(RevisitTestFixtures.DATE)
                .payloadDigest(digest)
                .body(RevisitTestFixtures.gzippedHttpResponse(encoded, decoded.length))
                .build();
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest), response);

        try (RevisitResolver resolver = new RevisitResolver(warc)) {
            try (ResolvedPayload resolved = resolver.resolve(revisitId)) {
                // the digest is over the stored, still content-encoded bytes
                assertArrayEquals(encoded, readAll(resolved.body()));
                resolved.verifyDigest();
            }
            try (ResolvedPayload resolved = resolver.resolve(revisitId)) {
                // a fresh resolution can instead stream the decoded body
                assertArrayEquals(decoded, readAll(resolved.decodedBody()));
            }
        }
    }

    @Test
    public void resolveByRecordInstanceAndIdAreEquivalent() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("instance resolve");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("instance.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest),
                RevisitTestFixtures.responseRecord(responseId, payload, digest));

        WarcRevisit revisit;
        try (WarcReader reader = new WarcReader(warc)) {
            revisit = (WarcRevisit) reader.next().get();
            try (RevisitResolver resolver = new RevisitResolver(warc);
                 ResolvedPayload resolved = resolver.resolve(revisit)) {
                assertArrayEquals(payload, readAll(resolved.body()));
                assertEquals(Arrays.asList(revisitId, responseId), resolved.referenceChain());
            }
        }
    }

    @Test
    public void chainWithoutAnyDeclaredDigestStillResolves() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("no digest anywhere");
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        WarcResponse response = new WarcResponse.Builder(RevisitTestFixtures.TARGET)
                .recordId(responseId)
                .date(RevisitTestFixtures.DATE)
                .body(RevisitTestFixtures.httpResponse(payload))
                .build();
        WarcRevisit revisit = new WarcRevisit.Builder(RevisitTestFixtures.TARGET,
                WarcRevisit.IDENTICAL_PAYLOAD_DIGEST_1_0)
                .recordId(revisitId)
                .date(RevisitTestFixtures.DATE)
                .refersTo(responseId)
                .build();
        Path warc = newWarc("nodigest.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE, revisit, response);

        try (RevisitResolver resolver = new RevisitResolver(warc);
             ResolvedPayload resolved = resolver.resolve(revisitId)) {
            assertArrayEquals(payload, readAll(resolved.body()));
            assertNull(resolved.expectedDigest());
            resolved.verifyDigest(); // nothing to verify against: must not throw
        }
    }
}
