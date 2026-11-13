package org.netpreserve.jwarc.net;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.netpreserve.jwarc.HttpRequest;
import org.netpreserve.jwarc.HttpResponse;
import org.netpreserve.jwarc.MediaType;
import org.netpreserve.jwarc.WarcCompression;
import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcRecord;
import org.netpreserve.jwarc.WarcRequest;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.jwarc.WarcWriter;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

public class RequestIndexTest {
    private static final String URL = "http://example.org/api";
    private static final Instant T1 = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2020-02-01T00:00:00Z");
    private static final Instant T3 = Instant.parse("2020-03-01T00:00:00Z");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static class Pair {
        final WarcResponse response;
        final WarcRequest request;

        Pair(WarcResponse response, WarcRequest request) {
            this.response = response;
            this.request = request;
        }

        Pair concurrent() {
            // request points at the response via WARC-Concurrent-To
            try {
                WarcRequest linked = new WarcRequest.Builder(URL)
                        .recordId(request.id())
                        .date(request.date())
                        .concurrentTo(response.id())
                        .body(httpRequest(request))
                        .build();
                return new Pair(response, linked);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static HttpRequest httpRequest(WarcRequest record) {
        try {
            return record.http();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static WarcResponse response(String bodyText, Instant date) {
        try {
            return new WarcResponse.Builder(URL)
                    .date(date)
                    .body(new HttpResponse.Builder(200, "OK")
                            .body(MediaType.PLAIN_TEXT, bodyText.getBytes(UTF_8))
                            .build())
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static WarcRequest request(String bodyText, MediaType contentType, Instant date) {
        try {
            HttpRequest http = new HttpRequest.Builder("POST", "/api")
                    .setHeader("Host", "example.org")
                    .body(contentType, bodyText.getBytes(UTF_8))
                    .build();
            return new WarcRequest.Builder(URL).date(date).body(http).build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Pair pair(String requestBody, String responseBody, MediaType type, Instant date) {
        WarcResponse response = response(responseBody, date);
        WarcRequest request = request(requestBody, type, date);
        return new Pair(response, request);
    }

    private Path writeWarc(String name, WarcRecord... records) throws IOException {
        Path file = tmp.newFile(name).toPath();
        try (WarcWriter writer = new WarcWriter(file)) {
            for (WarcRecord record : records) {
                writer.write(record);
            }
        }
        return file;
    }

    private Path writeGzipWarc(String name, WarcRecord... records) throws IOException {
        Path file = tmp.newFile(name).toPath();
        try (WarcWriter writer = new WarcWriter(
                Files.newByteChannel(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING),
                WarcCompression.GZIP)) {
            for (WarcRecord record : records) {
                writer.write(record);
            }
        }
        return file;
    }

    private RequestIndex indexFromWarc(Path... files) throws IOException {
        RequestIndex index = new RequestIndex();
        for (Path file : Arrays.asList(files)) {
            try (WarcReader reader = new WarcReader(file)) {
                for (WarcRecord record : reader) {
                    if (record instanceof WarcRequest) {
                        index.addRequest((WarcRequest) record);
                    } else if (record instanceof WarcResponse) {
                        index.addResponse((WarcResponse) record, file, reader.position());
                    }
                }
            }
        }
        index.build();
        return index;
    }

    private static RequestIdentity identity(String bodyText, MediaType type) {
        try {
            HttpRequest http = new HttpRequest.Builder("POST", "/api")
                    .setHeader("Host", "example.org")
                    .body(type, bodyText.getBytes(UTF_8))
                    .build();
            return RequestIdentity.from(URL, http);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void sameUrlDifferentPostBodiesMatchDifferentResponses() throws IOException {
        Pair a = pair("a=1", "response-a", MediaType.WWW_FORM_URLENCODED, T1).concurrent();
        Pair b = pair("a=2", "response-b", MediaType.WWW_FORM_URLENCODED, T2).concurrent();
        Path warc = writeWarc("diff.warc", a.response, a.request, b.response, b.request);
        RequestIndex index = indexFromWarc(warc);

        Capture gotA = index.select(identity("a=1", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH);
        Capture gotB = index.select(identity("a=2", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH);
        assertEquals(T1, gotA.date());
        assertEquals(T2, gotB.date());
    }

    @Test
    public void reorderedParametersAreDifferentIdentities() throws IOException {
        // identical logical parameters, different raw byte order
        Pair a = pair("a=1&b=2", "response-ab", MediaType.WWW_FORM_URLENCODED, T1).concurrent();
        Path warc = writeWarc("order.warc", a.response, a.request);
        RequestIndex index = indexFromWarc(warc);

        assertNotNull(index.select(identity("a=1&b=2", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH));
        try {
            index.select(identity("b=2&a=1", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH);
            fail("Expected reordered parameters to be a different identity");
        } catch (ReplayException e) {
            assertEquals(404, e.statusCode());
        }
    }

    @Test
    public void differentBaseContentTypeIsDifferentIdentity() throws IOException {
        Pair a = pair("{\"a\":1}", "json-response", MediaType.JSON, T1).concurrent();
        Path warc = writeWarc("ct.warc", a.response, a.request);
        RequestIndex index = indexFromWarc(warc);

        assertNotNull(index.select(identity("{\"a\":1}", MediaType.JSON), Instant.EPOCH));
        try {
            index.select(identity("{\"a\":1}", MediaType.PLAIN_TEXT), Instant.EPOCH);
            fail("Expected different base Content-Type to be a different identity");
        } catch (ReplayException e) {
            assertEquals(404, e.statusCode());
        }
    }

    @Test
    public void contentTypeNameParametersAreIgnored() throws IOException {
        // charset is a parameter, base type application/json is the same
        MediaType withCharset = MediaType.parse("application/json; charset=utf-8");
        Pair a = pair("{\"a\":1}", "json-response", withCharset, T1).concurrent();
        Path warc = writeWarc("ctparam.warc", a.response, a.request);
        RequestIndex index = indexFromWarc(warc);

        assertNotNull(index.select(identity("{\"a\":1}", MediaType.JSON), Instant.EPOCH));
    }

    @Test
    public void choosesNearestDateOnlyWithinIdentity() throws IOException {
        Pair a = pair("a=1", "old", MediaType.WWW_FORM_URLENCODED, T1).concurrent();
        Pair b = pair("a=1", "new", MediaType.WWW_FORM_URLENCODED, T3).concurrent();
        // unrelated identity in between
        Pair c = pair("a=2", "other", MediaType.WWW_FORM_URLENCODED, T2).concurrent();
        Path warc = writeWarc("dates.warc", a.response, a.request, b.response, b.request, c.response, c.request);
        RequestIndex index = indexFromWarc(warc);

        RequestIdentity id = identity("a=1", MediaType.WWW_FORM_URLENCODED);
        assertEquals(T1, index.select(id, T1).date());
        // equidistant from T1 and T3: later capture wins, matching WarcServer.closest()
        assertEquals(T3, index.select(id, T2).date());
        assertEquals(T3, index.select(id, T3).date());
        // selection never crosses into another identity
        assertEquals(T2, index.select(identity("a=2", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH).date());
    }

    @Test
    public void sameDateCollisionFailsExplicitly() throws IOException {
        Pair a = pair("a=1", "first", MediaType.WWW_FORM_URLENCODED, T1).concurrent();
        Pair b = pair("a=1", "second", MediaType.WWW_FORM_URLENCODED, T1).concurrent();
        Path warc = writeWarc("collision.warc", a.response, a.request, b.response, b.request);
        RequestIndex index = indexFromWarc(warc);

        try {
            index.select(identity("a=1", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH);
            fail("Expected same-date collision to fail");
        } catch (ReplayException e) {
            assertEquals(409, e.statusCode());
            assertTrue(e.getMessage().contains("share date"));
        }
    }

    @Test
    public void recordsMayBeInterleavedAcrossFiles() throws IOException {
        Pair a = pair("a=1", "response-a", MediaType.WWW_FORM_URLENCODED, T1).concurrent();
        Pair b = pair("a=2", "response-b", MediaType.WWW_FORM_URLENCODED, T2).concurrent();
        // requests in file 1, responses in file 2; b's request precedes a's (out of order)
        Path requests = writeWarc("requests.warc", b.request, a.request);
        Path responses = writeWarc("responses.warc", a.response, b.response);
        RequestIndex index = indexFromWarc(requests, responses);

        assertEquals(T1, index.select(identity("a=1", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH).date());
        assertEquals(T2, index.select(identity("a=2", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH).date());
    }

    @Test
    public void recordsMayBeInterleavedWithinOneFile() throws IOException {
        Pair a = pair("a=1", "response-a", MediaType.WWW_FORM_URLENCODED, T1).concurrent();
        Pair b = pair("a=2", "response-b", MediaType.WWW_FORM_URLENCODED, T2).concurrent();
        // request, response, request, response
        Path warc = writeWarc("interleaved.warc", a.request, a.response, b.request, b.response);
        RequestIndex index = indexFromWarc(warc);

        assertEquals(T1, index.select(identity("a=1", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH).date());
        assertEquals(T2, index.select(identity("a=2", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH).date());
    }

    @Test
    public void missingConcurrentTargetFailsExplicitly() throws IOException {
        Pair a = pair("a=1", "orphan", MediaType.WWW_FORM_URLENCODED, T1);
        WarcRequest dangling = new WarcRequest.Builder(URL)
                .date(T1)
                .concurrentTo(URI.create("urn:uuid:00000000-0000-0000-0000-000000000000"))
                .body(httpRequest(a.request))
                .build();
        Path warc = writeWarc("missing.warc", dangling);
        RequestIndex index = indexFromWarc(warc);

        try {
            index.select(identity("a=1", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH);
            fail("Expected missing WARC-Concurrent-To target to fail");
        } catch (ReplayException e) {
            assertEquals(409, e.statusCode());
            assertTrue(e.getMessage().contains("no such response record exists"));
        }
    }

    @Test
    public void conflictingTargetUriFailsExplicitly() throws IOException {
        WarcResponse response = new WarcResponse.Builder("http://example.org/other")
                .date(T1)
                .body(new HttpResponse.Builder(200, "OK").body(MediaType.PLAIN_TEXT, "x".getBytes(UTF_8)).build())
                .build();
        WarcRequest request = new WarcRequest.Builder(URL)
                .date(T1)
                .concurrentTo(response.id())
                .body(httpRequest(pair("a=1", "x", MediaType.WWW_FORM_URLENCODED, T1).request))
                .build();
        Path warc = writeWarc("conflict.warc", response, request);
        RequestIndex index = indexFromWarc(warc);

        try {
            index.select(identity("a=1", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH);
            fail("Expected mismatched target URI to fail");
        } catch (ReplayException e) {
            assertEquals(409, e.statusCode());
            assertTrue(e.getMessage().contains("targets"));
        }
    }

    @Test
    public void oneResponseClaimedByDifferentIdentitiesFails() throws IOException {
        WarcResponse shared = response("shared", T1);
        WarcRequest r1 = new WarcRequest.Builder(URL).date(T1).concurrentTo(shared.id())
                .body(httpRequest(request("a=1", MediaType.WWW_FORM_URLENCODED, T1))).build();
        WarcRequest r2 = new WarcRequest.Builder(URL).date(T2).concurrentTo(shared.id())
                .body(httpRequest(request("a=2", MediaType.WWW_FORM_URLENCODED, T2))).build();
        Path warc = writeWarc("claimed.warc", shared, r1, r2);
        RequestIndex index = indexFromWarc(warc);

        try {
            index.select(identity("a=1", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH);
            fail("Expected conflicting identity claim to fail");
        } catch (ReplayException e) {
            assertEquals(409, e.statusCode());
        }
        try {
            index.select(identity("a=2", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH);
            fail("Expected conflicting identity claim to fail");
        } catch (ReplayException e) {
            assertEquals(409, e.statusCode());
        }
    }

    @Test
    public void duplicateRequestForTheSameIdentityIsNotACollision() throws IOException {
        WarcResponse shared = response("shared", T1);
        // two request records with the same identity both point at the same response
        WarcRequest r1 = new WarcRequest.Builder(URL).date(T1).concurrentTo(shared.id())
                .body(httpRequest(request("a=1", MediaType.WWW_FORM_URLENCODED, T1))).build();
        WarcRequest r2 = new WarcRequest.Builder(URL).date(T1).concurrentTo(shared.id())
                .body(httpRequest(request("a=1", MediaType.WWW_FORM_URLENCODED, T1))).build();
        Path warc = writeWarc("dup.warc", shared, r1, r2);
        RequestIndex index = indexFromWarc(warc);

        assertEquals(T1, index.select(identity("a=1", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH).date());
    }

    @Test
    public void unassociatedResponseIsNotReachableByIdentity() throws IOException {
        // response with no request record pointing at it
        WarcResponse lone = response("lone", T1);
        Path warc = writeWarc("lone.warc", lone);
        RequestIndex index = indexFromWarc(warc);

        try {
            index.select(identity("anything", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH);
            fail("Unassociated response must not be reachable by request identity");
        } catch (ReplayException e) {
            assertEquals(404, e.statusCode());
        }
    }

    @Test
    public void largeRequestBodyIsBounded() throws IOException {
        // request body larger than the identity limit
        byte[] big = new byte[(int) RequestIdentity.MAX_BODY_BYTES + 1024];
        Arrays.fill(big, (byte) 'x');
        WarcRequest oversized = new WarcRequest.Builder(URL).date(T1)
                .body(new HttpRequest.Builder("POST", "/api").setHeader("Host", "example.org")
                        .body(MediaType.OCTET_STREAM, big).build())
                .build();
        Path warc = writeWarc("big.warc", oversized);
        RequestIndex index = indexFromWarc(warc);

        // indexing skipped it with a warning rather than retaining the body in memory
        assertEquals(1, index.warnings().size());
        assertTrue(index.warnings().get(0).contains("exceeds the identity limit"));
        try {
            HttpRequest http = new HttpRequest.Builder("POST", "/api").setHeader("Host", "example.org")
                    .body(MediaType.OCTET_STREAM, big).build();
            RequestIdentity.from(URL, http);
            fail("Expected BodyTooLargeException");
        } catch (RequestIdentity.BodyTooLargeException e) {
            // expected
        }
    }

    @Test
    public void bodyAtExactlyTheLimitIsAccepted() throws IOException {
        byte[] exact = new byte[(int) RequestIdentity.MAX_BODY_BYTES];
        Arrays.fill(exact, (byte) 'y');
        HttpRequest http = new HttpRequest.Builder("POST", "/api").setHeader("Host", "example.org")
                .body(MediaType.OCTET_STREAM, exact).build();
        RequestIdentity identity = RequestIdentity.from(URL, http);
        assertNotNull(identity);
        assertEquals("POST", identity.method());
    }

    @Test
    public void perRecordGzipWarcIsSupported() throws IOException {
        Pair a = pair("a=1", "gz-response", MediaType.WWW_FORM_URLENCODED, T1).concurrent();
        Path warc = writeGzipWarc("pairs.warc.gz", a.request, a.response);
        RequestIndex index = indexFromWarc(warc);

        Capture capture = index.select(identity("a=1", MediaType.WWW_FORM_URLENCODED), Instant.EPOCH);
        assertEquals(T1, capture.date());
        // the stored position points at the gzip member
        try (WarcReader reader = new WarcReader(warc)) {
            assertEquals(WarcCompression.GZIP, reader.compression());
            reader.position(capture.position());
            WarcRecord record = reader.next().orElseThrow(AssertionError::new);
            assertTrue(record instanceof WarcResponse);
        }
    }

    @Test
    public void identityEqualityAndHashing() throws IOException {
        RequestIdentity a = identity("a=1", MediaType.WWW_FORM_URLENCODED);
        RequestIdentity b = identity("a=1", MediaType.WWW_FORM_URLENCODED);
        RequestIdentity c = identity("a=2", MediaType.WWW_FORM_URLENCODED);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        // usable as a map key
        assertEquals(1, Collections.singletonMap(a, 1).size());
    }
}
