package org.netpreserve.jwarc.net;

import org.netpreserve.jwarc.URIs;
import org.netpreserve.jwarc.WarcCaptureRecord;
import org.netpreserve.jwarc.WarcRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Comparator.comparing;

/**
 * Indexes archived request/response capture events so that replayed requests with a body can be matched against the
 * exact response captured for them.
 * <p>
 * A response is only reachable through this index when an archived {@link WarcRequest} explicitly declares the
 * relationship with a {@code WARC-Concurrent-To} header. Records may be indexed in any order and may span multiple
 * WARC files: association is resolved in {@link #build()} after every file has been scanned. Responses which are not
 * referenced by any request record remain available for ordinary (e.g. GET) replay via {@link CaptureIndex} but are
 * never returned by this index.
 * <p>
 * Ambiguous or broken associations are recorded as problems and cause an explicit {@link ReplayException} when the
 * affected request identity is queried, rather than falling back to some other capture:
 * <ul>
 *     <li>a declared {@code WARC-Concurrent-To} target does not exist</li>
 *     <li>the associated record's target URI differs from the request's target URI</li>
 *     <li>one request declares several concurrent response records</li>
 *     <li>one response record is claimed by requests with different identities</li>
 *     <li>duplicate WARC-Record-IDs</li>
 *     <li>several responses for the same identity share the same capture date</li>
 * </ul>
 */
class RequestIndex {
    private static class IndexedResponse {
        final Capture capture;
        final URI recordId;

        IndexedResponse(Capture capture, URI recordId) {
            this.capture = capture;
            this.recordId = recordId;
        }
    }

    private static class IndexedRequest {
        final RequestIdentity identity;
        final URI recordId;
        final List<URI> concurrentTo;

        IndexedRequest(RequestIdentity identity, URI recordId, List<URI> concurrentTo) {
            this.identity = identity;
            this.recordId = recordId;
            this.concurrentTo = concurrentTo;
        }
    }

    private static final Comparator<Capture> BY_DATE = comparing(Capture::date)
            .thenComparing(c -> c.file() == null ? null : c.file().toString(),
                    Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparingLong(Capture::position);

    private final Map<URI, List<IndexedResponse>> responsesById = new HashMap<>();
    private final List<IndexedRequest> requests = new ArrayList<>();
    private final Map<RequestIdentity, NavigableSet<Capture>> capturesByIdentity = new HashMap<>();
    private final Map<RequestIdentity, Set<Instant>> seenDates = new HashMap<>();
    private final Map<RequestIdentity, String> problems = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private boolean built;

    /**
     * Records an archived response (or resource) that a request may point at with WARC-Concurrent-To.
     */
    void addResponse(WarcCaptureRecord record, Path file, long position) {
        if (!URIs.hasHttpOrHttpsScheme(record.target())) return;
        Capture capture = new Capture(record.target(), record.date(), file, position);
        IndexedResponse indexed = new IndexedResponse(capture, record.id());
        responsesById.computeIfAbsent(record.id(), id -> new ArrayList<>()).add(indexed);
    }

    /**
     * Records an archived request, calculating its {@link RequestIdentity} by reading (but not retaining) its body.
     * Requests whose HTTP content cannot be parsed or whose body is too large are skipped with a warning: any
     * responses they point at simply remain available for ordinary GET replay.
     */
    void addRequest(WarcRequest record) {
        if (!URIs.hasHttpOrHttpsScheme(record.target())) return;
        RequestIdentity identity;
        try {
            identity = RequestIdentity.from(record.target(), record.http());
        } catch (RequestIdentity.BodyTooLargeException e) {
            warnings.add("Skipping request record " + record.id() + ": " + e.getMessage());
            return;
        } catch (IOException | RuntimeException e) {
            warnings.add("Skipping unparseable request record " + record.id() + ": " + e);
            return;
        }
        requests.add(new IndexedRequest(identity, record.id(), record.concurrentTo()));
    }

    /**
     * Resolves all WARC-Concurrent-To associations. Must be called once after every WARC file has been scanned.
     */
    synchronized void build() {
        if (built) return;
        built = true;
        Map<URI, RequestIdentity> responseOwner = new HashMap<>();

        for (IndexedRequest request : requests) {
            List<URI> concurrentTo = request.concurrentTo;
            if (concurrentTo.isEmpty()) {
                // an unassociated request cannot identify a response; nothing to index
                continue;
            }

            if (concurrentTo.size() > 1) {
                addProblem(request.identity, "Request record " + request.recordId
                        + " declares " + concurrentTo.size() + " WARC-Concurrent-To targets: " + concurrentTo);
            }

            for (URI targetId : concurrentTo) {
                List<IndexedResponse> candidates = responsesById.get(targetId);
                if (candidates == null) {
                    addProblem(request.identity, "Request record " + request.recordId
                            + " declares WARC-Concurrent-To " + targetId + " but no such response record exists");
                    continue;
                }
                if (candidates.size() > 1) {
                    addProblem(request.identity, "Duplicate WARC-Record-ID " + targetId
                            + " found in " + candidates.size() + " records");
                    continue;
                }

                IndexedResponse response = candidates.get(0);
                if (!response.capture.uri().equals(request.identity.uri())) {
                    addProblem(request.identity, "Request record " + request.recordId + " targets "
                            + request.identity.uri() + " but its WARC-Concurrent-To response " + targetId
                            + " targets " + response.capture.uri());
                    continue;
                }

                RequestIdentity existingOwner = responseOwner.get(targetId);
                if (existingOwner != null && !existingOwner.equals(request.identity)) {
                    addProblem(request.identity, "Response record " + targetId
                            + " is claimed by request records with different identities");
                    addProblem(existingOwner, "Response record " + targetId
                            + " is claimed by request records with different identities");
                    continue;
                }

                responseOwner.put(targetId, request.identity);
                addCapture(request.identity, response.capture);
            }
        }
    }

    private void addCapture(RequestIdentity identity, Capture capture) {
        NavigableSet<Capture> set = capturesByIdentity.computeIfAbsent(identity, id -> new TreeSet<>(BY_DATE));
        if (!set.add(capture)) {
            // the same capture was already indexed (e.g. a duplicate request record for the same pair)
            return;
        }
        Set<Instant> dates = seenDates.computeIfAbsent(identity, id -> new HashSet<>());
        if (!dates.add(capture.date())) {
            addProblem(identity, "Multiple captured responses for the same request identity share date "
                    + capture.date());
        }
    }

    private void addProblem(RequestIdentity identity, String message) {
        problems.merge(identity, message, (a, b) -> a + "; " + b);
    }

    /**
     * Selects the response captured for the given request identity.
     *
     * @param identity the identity of the incoming request
     * @param date     the requested capture date; the response nearest in time within the identity is chosen
     * @throws ReplayException if no matching response exists, the association is broken/ambiguous or multiple
     *                         candidates share the same capture date
     */
    synchronized Capture select(RequestIdentity identity, Instant date) throws ReplayException {
        if (!built) throw new IllegalStateException("build() has not been called");

        String problem = problems.get(identity);
        if (problem != null) {
            throw new ReplayException(409, "Cannot replay " + identity + ": " + problem);
        }

        NavigableSet<Capture> versions = capturesByIdentity.get(identity);
        if (versions == null || versions.isEmpty()) {
            throw new ReplayException(404, "No archived response matching request identity: " + identity);
        }

        Capture key = new Capture(identity.uri(), date);
        Capture a = versions.floor(key);
        Capture b = versions.higher(key);
        if (a == null) return b;
        if (b == null) return a;
        // nearest in time within the identity; an exact tie resolves to the later capture, like WarcServer.closest()
        Duration da = Duration.between(a.date(), date);
        Duration db = Duration.between(date, b.date());
        return da.compareTo(db) < 0 ? a : b;
    }

    /**
     * Non-fatal problems encountered while indexing (e.g. unparseable request records).
     */
    synchronized List<String> warnings() {
        return new ArrayList<>(warnings);
    }
}
