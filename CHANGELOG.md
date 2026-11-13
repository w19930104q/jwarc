# Changelog

## Unreleased

### New features

- WarcServer: proxy replay of requests with a body (e.g. POST) now matches the archived request/response pair by
  request identity (target URI, method, base Content-Type and digest of the raw request body) linked with
  `WARC-Concurrent-To`, rather than indexing by URL and date alone. Pairs may be stored out of order and across
  multiple WARC files, including record-at-a-time gzip. Reordered request parameters are treated as different
  identities. Missing or conflicting associations and same-date candidates fail with an explicit 409 and there is
  no fallback to a response captured for another request (e.g. a GET); unassociated responses remain available for
  ordinary GET replay. Request body handling is memory bounded (16 MiB limit, 413 if exceeded).

## 0.37.0 (2026-09-01)

### New features

- `pack` command: pack local files as WARC resource records
- `Message.contentTypeHeader()`, `WarcPayload.typeHeader()`: returns the Content-Type header value without defaulting to `application/octet-stream` [#113](https://github.com/iipc/jwarc/pull/113)
- ValidateTool: validate WARC compression [#107](https://github.com/iipc/jwarc/pull/107)

### Fixed

- SavebackTool: correctly handle HTTP error responses (status >= 400) by reading the error stream

## 0.36.0 (2026-04-07)

### Fixed

- Made zstd-jni dependency optional. It was included as a required dependency in 0.33.0 by mistake.

## 0.35.0 (2026-02-26)

### New features

- CdxWriter: additional record types (e.g. conversion) can now be included by setting an appropriate filter [#105](https://github.com/iipc/jwarc/pull/105)
- CdxTool: `--record-type` option [#105](https://github.com/iipc/jwarc/pull/105)

### Fixed

- Added command-line help to all tools [#103](https://github.com/iipc/jwarc/pull/103)

## 0.34.0 (2026-01-15)

### New features

- Added `view` command: interactive TUI for exploring WARC files
  - view captures, WARC and HTTP headers
  - filter captures by type, status, method or url
  - save payload to a file, open in browser or external editor

### Fixed

- HttpParser: lenient mode now accepts "0" as a status code for compatibility with Browsertrix WARCs

## 0.33.0 (2025-12-24)

### New features

- CdxRecord: surt(), format(), values() and toString()
- CdxWriter
  - CDXJ output support
  - sort option
- HttpMessage: `Content-Encoding: zstd` support
- HttpRequest: `Content-Encoding: chunked` support
- WarcReader: [Zstandard compressed WARC Files](https://iipc.github.io/warc-specifications/specifications/warc-zstd/) support
- WarcServer: resource record support

### Fixed

- URIs.toNormalizedSurt(): Improved compatibility with Python [surt](https://github.com/internetarchive/surt).

## 0.32.0

### Added

- HeaderValidator with WARC/1.1 standard ruleset
- ExtractTool: can now extract sequential concurrent records (`--concurrent` option)
- DedupeTool
  - In-memory cache for cross-URL digest-based deduplication (`--cache-size` option)
  - Now prints deduplication statistics (`--dry-run` and `--quiet` options)
  - Multi-threaded deduplication (`--threads` option)
- ValidateTool
  - Multi-threaded validation (`--threads` option) 
- ParsingException message is now annotated with the source filename and record offset when available

### Fixed

- RFC5952 canonical form is now used for IPv6 addresses in WARC-IP-Address
- HttpParser in lenient mode now:
  - accepts responses missing version number
  - ignores header lines missing :
  - ignores folded status lines
- WarcParser: treats `alexa/dat` ARC records as not HTTP type
