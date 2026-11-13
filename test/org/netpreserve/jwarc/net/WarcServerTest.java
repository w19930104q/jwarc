package org.netpreserve.jwarc.net;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.netpreserve.jwarc.HttpRequest;
import org.netpreserve.jwarc.HttpResponse;
import org.netpreserve.jwarc.MediaType;
import org.netpreserve.jwarc.WarcCompression;
import org.netpreserve.jwarc.WarcRecord;
import org.netpreserve.jwarc.WarcRequest;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.jwarc.WarcWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

public class WarcServerTest {
    private static final String URL = "http://example.org/api";
    private static final Instant T0 = Instant.parse("2019-12-31T00:00:00Z");
    private static final Instant T1 = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2020-02-01T00:00:00Z");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static class RawResponse {
        final int status;
        final byte[] body;

        RawResponse(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }
    }

    @Test
    public void emptyCollection() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket()) {
            // so far just testing that we can instantiate it and load the .js files
            WarcServer server = new WarcServer(serverSocket, Collections.emptyList());
        }
    }

    private WarcResponse response(String responseText, Instant date) throws IOException {
        return new WarcResponse.Builder(URL)
                .date(date)
                .body(new HttpResponse.Builder(200, "OK")
                        .body(MediaType.PLAIN_TEXT, responseText.getBytes(UTF_8))
                        .build())
                .build();
    }

    private WarcRequest request(String bodyText, MediaType type, Instant date) throws IOException {
        HttpRequest http = new HttpRequest.Builder("POST", "/api")
                .setHeader("Host", "example.org")
                .body(type, bodyText.getBytes(UTF_8))
                .build();
        return new WarcRequest.Builder(URL).date(date).body(http).build();
    }

    private WarcRequest linked(WarcRequest template, WarcResponse response) throws IOException {
        return new WarcRequest.Builder(URL)
                .recordId(template.id())
                .date(template.date())
                .concurrentTo(response.id())
                .body(template.http())
                .build();
    }

    private Path writeWarc(String name, WarcCompression compression, WarcRecord... records) throws IOException {
        Path file = tmp.newFile(name).toPath();
        try (WarcWriter writer = new WarcWriter(
                Files.newByteChannel(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING),
                compression)) {
            for (WarcRecord record : records) {
                writer.write(record);
            }
        }
        return file;
    }

    private WarcServer startServer(Path... warcs) throws IOException {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        WarcServer server = new WarcServer(serverSocket, java.util.Arrays.asList(warcs));
        Thread thread = new Thread(server::listen, "test-WarcServer");
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    /**
     * Sends a request in proxy absolute-form over a raw socket so we have full control over the body bytes.
     */
    private RawResponse proxyRequest(WarcServer server, String method, MediaType contentType, String body)
            throws IOException {
        int port = ((InetSocketAddress) server.serverSocket.getLocalSocketAddress()).getPort();
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(30000);
            socket.connect(new InetSocketAddress("127.0.0.1", port));
            OutputStream out = socket.getOutputStream();
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(URL).append(" HTTP/1.1\r\n");
            head.append("Host: example.org\r\n");
            if (contentType != null) {
                head.append("Content-Type: ").append(contentType.toString()).append("\r\n");
            }
            byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(UTF_8);
            head.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(bodyBytes);
            out.flush();
            // half-close: signal the end of the request so the server does not wait for a pipelined request
            socket.shutdownOutput();

            InputStream in = socket.getInputStream();
            // read exactly the first response: status line + headers, then Content-Length body bytes.
            // (the toy server appends a trailing 404 after the matched route which a real client treats
            // as the start of a later response)
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int ch = in.read();
                if (ch < 0) throw new IOException("Unexpected EOF in response headers");
                headerBuf.write(ch);
                if (matched == 0 || matched == 2) {
                    if (ch == '\r') matched++;
                    else matched = 0;
                } else if (matched == 1 || matched == 3) {
                    if (ch == '\n') matched++;
                    else matched = 0;
                }
            }
            String header = headerBuf.toString("ISO-8859-1");
            int status = Integer.parseInt(header.split(" ")[1]);
            long contentLength = -1;
            for (String line : header.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            byte[] respBody = new byte[(int) contentLength];
            int off = 0;
            while (off < respBody.length) {
                int n = in.read(respBody, off, respBody.length - off);
                if (n < 0) throw new IOException("Unexpected EOF in response body");
                off += n;
            }
            return new RawResponse(status, respBody);
        }
    }

    @Test
    public void postMatchesTheResponseCapturedForItsExactBody() throws IOException {
        WarcResponse r1 = response("answer-one", T1);
        WarcResponse r2 = response("answer-two", T2);
        WarcRequest q1 = linked(request("a=1", MediaType.WWW_FORM_URLENCODED, T1), r1);
        WarcRequest q2 = linked(request("a=2", MediaType.WWW_FORM_URLENCODED, T2), r2);
        Path warc = writeWarc("post.warc", WarcCompression.NONE, r1, q1, r2, q2);

        try (WarcServer server = startServer(warc)) {
            RawResponse got1 = proxyRequest(server, "POST", MediaType.WWW_FORM_URLENCODED, "a=1");
            RawResponse got2 = proxyRequest(server, "POST", MediaType.WWW_FORM_URLENCODED, "a=2");
            assertEquals(200, got1.status);
            assertEquals(200, got2.status);
            assertEquals("answer-one", new String(got1.body, UTF_8));
            assertEquals("answer-two", new String(got2.body, UTF_8));
        }
    }

    @Test
    public void postWithReorderedParametersDoesNotMatchAndDoesNotFallBackToGet() throws IOException {
        WarcResponse r1 = response("answer-one", T1);
        WarcRequest q1 = linked(request("a=1&b=2", MediaType.WWW_FORM_URLENCODED, T1), r1);
        // an old-style response with no concurrent request: reachable for GET but never for POST identity
        WarcResponse getResponse = response("get-answer", T0);
        Path warc = writeWarc("reorder.warc", WarcCompression.NONE, r1, q1, getResponse);

        try (WarcServer server = startServer(warc)) {
            RawResponse got = proxyRequest(server, "POST", MediaType.WWW_FORM_URLENCODED, "b=2&a=1");
            assertEquals(404, got.status);
            // ordinary GET replay of the unassociated response still works
            RawResponse getGot = proxyRequest(server, "GET", null, null);
            assertEquals(200, getGot.status);
            assertEquals("get-answer", new String(getGot.body, UTF_8));
        }
    }

    @Test
    public void sameDateCollisionReturnsExplicitError() throws IOException {
        WarcResponse r1 = response("first", T1);
        WarcResponse r2 = response("second", T1);
        WarcRequest q1 = linked(request("a=1", MediaType.WWW_FORM_URLENCODED, T1), r1);
        WarcRequest q2 = linked(request("a=1", MediaType.WWW_FORM_URLENCODED, T1), r2);
        Path warc = writeWarc("collision.warc", WarcCompression.NONE, q1, r1, q2, r2);

        try (WarcServer server = startServer(warc)) {
            RawResponse got = proxyRequest(server, "POST", MediaType.WWW_FORM_URLENCODED, "a=1");
            assertEquals(409, got.status);
            assertTrue(new String(got.body, UTF_8).contains("share date"));
        }
    }

    @Test
    public void missingAssociationReturnsExplicitError() throws IOException {
        WarcRequest dangling = new WarcRequest.Builder(URL)
                .date(T1)
                .concurrentTo(URI.create("urn:uuid:00000000-0000-0000-0000-000000000000"))
                .body(request("a=1", MediaType.WWW_FORM_URLENCODED, T1).http())
                .build();
        Path warc = writeWarc("missing.warc", WarcCompression.NONE, dangling);

        try (WarcServer server = startServer(warc)) {
            RawResponse got = proxyRequest(server, "POST", MediaType.WWW_FORM_URLENCODED, "a=1");
            assertEquals(409, got.status);
            assertTrue(new String(got.body, UTF_8).contains("no such response record exists"));
        }
    }

    @Test
    public void conflictingAssociationReturnsExplicitError() throws IOException {
        WarcResponse other = new WarcResponse.Builder("http://example.org/other")
                .date(T1)
                .body(new HttpResponse.Builder(200, "OK").body(MediaType.PLAIN_TEXT, "x".getBytes(UTF_8)).build())
                .build();
        WarcRequest bad = new WarcRequest.Builder(URL)
                .date(T1)
                .concurrentTo(other.id())
                .body(request("a=1", MediaType.WWW_FORM_URLENCODED, T1).http())
                .build();
        Path warc = writeWarc("conflict.warc", WarcCompression.NONE, other, bad);

        try (WarcServer server = startServer(warc)) {
            RawResponse got = proxyRequest(server, "POST", MediaType.WWW_FORM_URLENCODED, "a=1");
            assertEquals(409, got.status);
        }
    }

    @Test
    public void interleavedPairsAcrossTwoGzipWarcsReplay() throws IOException {
        WarcResponse r1 = response("answer-one", T1);
        WarcResponse r2 = response("answer-two", T2);
        WarcRequest q1 = linked(request("a=1", MediaType.WWW_FORM_URLENCODED, T1), r1);
        WarcRequest q2 = linked(request("a=2", MediaType.WWW_FORM_URLENCODED, T2), r2);
        // requests and responses split across files, order deliberately scrambled
        Path requests = writeWarc("requests.warc.gz", WarcCompression.GZIP, q2, q1);
        Path responses = writeWarc("responses.warc.gz", WarcCompression.GZIP, r1, r2);

        try (WarcServer server = startServer(requests, responses)) {
            RawResponse got1 = proxyRequest(server, "POST", MediaType.WWW_FORM_URLENCODED, "a=1");
            RawResponse got2 = proxyRequest(server, "POST", MediaType.WWW_FORM_URLENCODED, "a=2");
            assertEquals(200, got1.status);
            assertEquals(200, got2.status);
            assertEquals("answer-one", new String(got1.body, UTF_8));
            assertEquals("answer-two", new String(got2.body, UTF_8));
        }
    }

    @Test
    public void oversizedRequestBodyIsRejectedBounded() throws IOException {
        WarcResponse r1 = response("answer", T1);
        WarcRequest q1 = linked(request("a=1", MediaType.WWW_FORM_URLENCODED, T1), r1);
        Path warc = writeWarc("small.warc", WarcCompression.NONE, r1, q1);

        try (WarcServer server = startServer(warc)) {
            StringBuilder huge = new StringBuilder();
            // larger than RequestIdentity.MAX_BODY_BYTES but only ~16 MiB allocated in the test
            int target = (int) RequestIdentity.MAX_BODY_BYTES + 4096;
            for (int i = 0; i < target; i++) huge.append('z');
            RawResponse got = proxyRequest(server, "POST", MediaType.OCTET_STREAM, huge.toString());
            assertEquals(413, got.status);
        }
    }

    @Test
    public void contentTypeBaseTypeDistinguishesPosts() throws IOException {
        WarcResponse r1 = response("json-answer", T1);
        WarcRequest q1 = linked(request("{\"a\":1}", MediaType.JSON, T1), r1);
        Path warc = writeWarc("ct.warc", WarcCompression.NONE, r1, q1);

        try (WarcServer server = startServer(warc)) {
            assertEquals(200, proxyRequest(server, "POST", MediaType.JSON, "{\"a\":1}").status);
            assertEquals(404, proxyRequest(server, "POST", MediaType.PLAIN_TEXT, "{\"a\":1}").status);
        }
    }
}
