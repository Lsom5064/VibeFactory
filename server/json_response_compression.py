"""Compress complete JSON reads without changing file downloads or streams."""

import gzip

from starlette.concurrency import run_in_threadpool
from starlette.datastructures import Headers, MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send


def accepts_gzip(value: str) -> bool:
    for item in value.lower().split(","):
        encoding, *parameters = item.strip().split(";")
        if encoding != "gzip":
            continue
        quality = 1.0
        for parameter in parameters:
            key, separator, raw_value = parameter.strip().partition("=")
            if key == "q" and separator:
                try:
                    quality = float(raw_value)
                except ValueError:
                    quality = 0.0
        return 0 < quality <= 1
    return False


class JsonResponseCompressionMiddleware:
    def __init__(self, app: ASGIApp, minimum_size: int = 2048) -> None:
        self.app = app
        self.minimum_size = minimum_size

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or scope.get("method") != "GET":
            await self.app(scope, receive, send)
            return
        request_headers = Headers(scope=scope)
        if "range" in request_headers:
            await self.app(scope, receive, send)
            return
        gzip_allowed = accepts_gzip(request_headers.get("accept-encoding", ""))
        pending_start: Message | None = None

        async def send_response(message: Message) -> None:
            nonlocal pending_start
            if message["type"] == "http.response.start":
                headers = Headers(raw=message.get("headers", []))
                if (
                    message["status"] == 200
                    and headers.get("content-type", "").split(";")[0].strip() == "application/json"
                    and "content-encoding" not in headers
                    and "content-range" not in headers
                ):
                    pending_start = {**message, "headers": list(message.get("headers", []))}
                else:
                    await send(message)
                return

            if pending_start is not None:
                start = pending_start
                pending_start = None
                body = message.get("body", b"")
                if (
                    message["type"] == "http.response.body"
                    and not message.get("more_body", False)
                    and len(body) >= self.minimum_size
                ):
                    # Compression must not block the event loop on large XML/JSON payloads.
                    headers = MutableHeaders(raw=start["headers"])
                    headers.add_vary_header("Accept-Encoding")
                    if gzip_allowed:
                        compressed = await run_in_threadpool(
                            gzip.compress, body, compresslevel=3, mtime=0
                        )
                        if len(compressed) < len(body):
                            headers["Content-Encoding"] = "gzip"
                            headers["Content-Length"] = str(len(compressed))
                            message = {**message, "body": compressed}
                await send(start)
            await send(message)

        await self.app(scope, receive, send_response)
