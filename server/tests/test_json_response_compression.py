import asyncio
import gzip
import unittest

from starlette.responses import JSONResponse, Response, StreamingResponse

from server.json_response_compression import JsonResponseCompressionMiddleware


class JsonResponseCompressionTests(unittest.TestCase):
    def capture(self, response, *, accept="gzip", method="GET", extra_headers=()):
        async def run():
            messages = []
            async def receive():
                await asyncio.sleep(60)
                return {"type": "http.disconnect"}
            async def send(message):
                messages.append(message)
            middleware = JsonResponseCompressionMiddleware(response)
            await middleware({"type": "http", "method": method, "path": "/test",
                              "asgi": {"spec_version": "2.4"},
                              "headers": [(b"accept-encoding", accept.encode()), *extra_headers]}, receive, send)
            start = messages[0]
            headers = {key.decode(): value.decode() for key, value in start["headers"]}
            body = b"".join(message.get("body", b"") for message in messages[1:])
            return start["status"], headers, body
        return asyncio.run(run())

    def test_json_round_trip_content_length_and_vary(self):
        payload = {"xml": "<Button>버튼</Button>" * 1000, "coordinates": [0.2, 0.5], "instruction": "모양 변경"}
        original = JSONResponse(payload, headers={"Vary": "Origin"}).body
        status, headers, body = self.capture(JSONResponse(payload, headers={"Vary": "Origin"}))
        self.assertEqual(200, status)
        self.assertEqual("gzip", headers["content-encoding"])
        self.assertEqual(len(body), int(headers["content-length"]))
        self.assertEqual(original, gzip.decompress(body))
        self.assertIn("Origin", headers["vary"])
        self.assertIn("Accept-Encoding", headers["vary"])
        self.assertLess(len(body), len(original) // 5)

    def test_identity_and_disabled_gzip_keep_original_bytes(self):
        for accept in ("identity", "", "gzip;q=0", "gzip;q=invalid"):
            with self.subTest(accept=accept):
                response = JSONResponse({"text": "텍스트" * 1000})
                _, headers, body = self.capture(response, accept=accept)
                self.assertNotIn("content-encoding", headers)
                self.assertEqual(response.body, body)
                self.assertIn("Accept-Encoding", headers["vary"])

    def test_reused_response_keeps_its_original_headers(self):
        response = JSONResponse({"text": "reused JSON" * 1000})
        original_headers = list(response.raw_headers)
        self.capture(response)
        self.assertEqual(original_headers, response.raw_headers)
        _, headers, body = self.capture(response, accept="identity")
        self.assertNotIn("content-encoding", headers)
        self.assertEqual(response.body, body)

    def test_files_range_small_json_and_writes_are_unchanged(self):
        cases = [
            (Response(b"APK data" * 1000, media_type="application/vnd.android.package-archive"), {}),
            (Response(b"image" * 1000, media_type="image/png"), {}),
            (JSONResponse({"text": "x" * 3000}), {"extra_headers": [(b"range", b"bytes=0-100")]}),
            (JSONResponse({"text": "x" * 3000}), {"method": "POST"}),
            (JSONResponse({"ok": True}), {}),
            (Response(b"already compressed", media_type="application/json", headers={"Content-Encoding": "gzip"}), {}),
            (Response(b"partial" * 1000, status_code=206, media_type="application/json", headers={"Content-Range": "bytes 0-6999/10000"}), {}),
        ]
        for response, options in cases:
            with self.subTest(options=options, media_type=response.media_type):
                _, headers, body = self.capture(response, **options)
                self.assertEqual(response.body, body)
                self.assertEqual(len(body), int(headers["content-length"]))
                self.assertEqual(response.headers.get("content-encoding"), headers.get("content-encoding"))

    def test_json_and_sse_streams_are_not_buffered_or_compressed(self):
        for media_type in ("application/json", "text/event-stream"):
            response = StreamingResponse(iter([b"first" * 1000, b"second" * 1000]), media_type=media_type)
            _, headers, body = self.capture(response)
            self.assertNotIn("content-encoding", headers)
            self.assertEqual(b"first" * 1000 + b"second" * 1000, body)

    def test_concurrent_responses_do_not_mix_bodies(self):
        async def run():
            async def endpoint(scope, receive, send):
                await JSONResponse({"value": scope["path"] * 3000})(scope, receive, send)
            middleware = JsonResponseCompressionMiddleware(endpoint)
            async def request(path):
                messages = []
                async def receive():
                    return {"type": "http.request"}
                async def send(message):
                    messages.append(message)
                await middleware({"type": "http", "method": "GET", "path": path,
                                  "headers": [(b"accept-encoding", b"gzip")]}, receive, send)
                return gzip.decompress(messages[1]["body"])
            return await asyncio.gather(*(request(str(index)) for index in range(12)))
        bodies = asyncio.run(run())
        self.assertEqual(12, len(set(bodies)))
