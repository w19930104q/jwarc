/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2026 National Library of Australia and the jwarc contributors
 */

package org.netpreserve.jwarc.tools;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.netpreserve.jwarc.*;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.*;

public class ExtractToolTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path newWarc(String name) throws Exception {
        return temporaryFolder.newFile(name).toPath();
    }

    private static class CapturedOutput {
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        final WritableByteChannel channel = Channels.newChannel(stream);

        byte[] bytes() {
            return stream.toByteArray();
        }
    }

    @Test
    public void revisitModeOutputsOriginalPayload() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("<html>original body</html>");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("revisit.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest),
                RevisitTestFixtures.responseRecord(responseId, payload, digest));

        CapturedOutput out = new CapturedOutput();
        int exit = ExtractTool.run(new String[]{"--payload", "--revisit", warc.toString(), "0"}, out.channel);
        assertEquals(0, exit);
        assertArrayEquals(payload, out.bytes());
    }

    @Test
    public void revisitModeOutputsDecodedBodyForContentEncodedResponse() throws Exception {
        byte[] decoded = RevisitTestFixtures.utf8("decoded CLI output");
        byte[] encoded = RevisitTestFixtures.gzip(decoded);
        WarcDigest digest = RevisitTestFixtures.sha1(encoded);
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

        CapturedOutput out = new CapturedOutput();
        int exit = ExtractTool.run(new String[]{"--payload", "--revisit", warc.toString(), "0"}, out.channel);
        assertEquals(0, exit);
        assertArrayEquals(decoded, out.bytes());
    }

    @Test
    public void defaultPayloadModeKeepsLegacySemanticsForRevisit() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("legacy");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("legacy.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest),
                RevisitTestFixtures.responseRecord(responseId, payload, digest));

        CapturedOutput out = new CapturedOutput();
        int exit = ExtractTool.run(new String[]{"--payload", warc.toString(), "0"}, out.channel);
        assertEquals(0, exit);
        // without --revisit the original payload must not appear in the output
        assertFalse(Arrays.equals(payload, out.bytes()));
    }

    @Test
    public void resolvesAcrossFilesWithWarcFileOption() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("cross file payload");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path revisitWarc = newWarc("revisits.warc");
        Path payloadWarc = newWarc("payloads.warc");
        RevisitTestFixtures.writeWarc(revisitWarc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest));
        RevisitTestFixtures.writeWarc(payloadWarc, WarcCompression.NONE,
                RevisitTestFixtures.responseRecord(responseId, payload, digest));

        CapturedOutput out = new CapturedOutput();
        int exit = ExtractTool.run(new String[]{"--payload", "--revisit",
                "--warc-file", payloadWarc.toString(), revisitWarc.toString(), "0"}, out.channel);
        assertEquals(0, exit);
        assertArrayEquals(payload, out.bytes());

        CapturedOutput out2 = new CapturedOutput();
        int exit2 = ExtractTool.run(new String[]{"--payload", "--revisit",
                "--warc-file=" + payloadWarc, revisitWarc.toString(), "0"}, out2.channel);
        assertEquals(0, exit2);
        assertArrayEquals(payload, out2.bytes());
    }

    @Test
    public void worksWithRecordAtATimeGzip() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("gzip cli payload");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("revisit.warc.gz");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.GZIP,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, digest),
                RevisitTestFixtures.responseRecord(responseId, payload, digest));

        CapturedOutput out = new CapturedOutput();
        int exit = ExtractTool.run(new String[]{"--payload", "--revisit", warc.toString(), "0"}, out.channel);
        assertEquals(0, exit);
        assertArrayEquals(payload, out.bytes());
    }

    @Test
    public void missingTargetExitsWithDistinctCode() throws Exception {
        WarcDigest digest = RevisitTestFixtures.sha1(RevisitTestFixtures.utf8("x"));
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("missing.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, RevisitTestFixtures.uuid(), digest));

        CapturedOutput out = new CapturedOutput();
        int exit = ExtractTool.run(new String[]{"--payload", "--revisit", warc.toString(), "0"}, out.channel);
        assertEquals(ExtractTool.EXIT_TARGET_NOT_FOUND, exit);
        assertEquals(0, out.bytes().length);
    }

    @Test
    public void cycleExitsWithDistinctCode() throws Exception {
        WarcDigest digest = RevisitTestFixtures.sha1(RevisitTestFixtures.utf8("x"));
        URI a = RevisitTestFixtures.uuid();
        URI b = RevisitTestFixtures.uuid();
        Path warc = newWarc("cycle.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(a, b, digest),
                RevisitTestFixtures.revisitRecord(b, a, digest));

        CapturedOutput out = new CapturedOutput();
        int exit = ExtractTool.run(new String[]{"--payload", "--revisit", warc.toString(), "0"}, out.channel);
        assertEquals(ExtractTool.EXIT_REFERENCE_CYCLE, exit);
    }

    @Test
    public void duplicateRecordIdExitsWithDistinctCode() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("dup");
        WarcDigest digest = RevisitTestFixtures.sha1(payload);
        URI sameId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("dup.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, sameId, digest),
                RevisitTestFixtures.resourceRecord(sameId, payload, digest),
                RevisitTestFixtures.resourceRecord(sameId, payload, digest));

        CapturedOutput out = new CapturedOutput();
        int exit = ExtractTool.run(new String[]{"--payload", "--revisit", warc.toString(), "0"}, out.channel);
        assertEquals(ExtractTool.EXIT_DUPLICATE_RECORD_ID, exit);
    }

    @Test
    public void digestMismatchExitsWithDistinctCode() throws Exception {
        byte[] payload = RevisitTestFixtures.utf8("payload");
        WarcDigest actual = RevisitTestFixtures.sha1(payload);
        WarcDigest wrong = RevisitTestFixtures.sha1(RevisitTestFixtures.utf8("other"));
        URI responseId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("mismatch.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.revisitRecord(revisitId, responseId, wrong),
                RevisitTestFixtures.responseRecord(responseId, payload, actual));

        CapturedOutput out = new CapturedOutput();
        int exit = ExtractTool.run(new String[]{"--payload", "--revisit", warc.toString(), "0"}, out.channel);
        assertEquals(ExtractTool.EXIT_DIGEST_MISMATCH, exit);
    }

    @Test
    public void unsupportedProfileExitsWithDistinctCode() throws Exception {
        URI targetId = RevisitTestFixtures.uuid();
        URI revisitId = RevisitTestFixtures.uuid();
        Path warc = newWarc("profile.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE,
                RevisitTestFixtures.serverNotModifiedRevisit(revisitId, targetId));

        CapturedOutput out = new CapturedOutput();
        int exit = ExtractTool.run(new String[]{"--payload", "--revisit", warc.toString(), "0"}, out.channel);
        assertEquals(ExtractTool.EXIT_UNSUPPORTED_PROFILE, exit);
    }

    @Test
    public void revisitWithoutPayloadFlagIsRejected() throws Exception {
        Path warc = newWarc("flag.warc");
        RevisitTestFixtures.writeWarc(warc, WarcCompression.NONE);

        CapturedOutput out = new CapturedOutput();
        int exit = ExtractTool.run(new String[]{"--revisit", warc.toString(), "0"}, out.channel);
        assertNotEquals(0, exit);
    }

    @Test
    public void noArgumentsShowsUsage() throws Exception {
        CapturedOutput out = new CapturedOutput();
        int exit = ExtractTool.run(new String[]{}, out.channel);
        assertNotEquals(0, exit);
    }

    @Test
    public void helpShowsUsageAndSucceeds() throws Exception {
        CapturedOutput out = new CapturedOutput();
        int exit = ExtractTool.run(new String[]{"--help"}, out.channel);
        assertEquals(0, exit);
    }
}
