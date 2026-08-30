/**
 * Incremental parser for a `text/event-stream` body read through `fetch`.
 *
 * We read SSE with `fetch` rather than the native `EventSource` because the
 * streams are authenticated with a bearer token and `EventSource` cannot send
 * headers. That trade means we own the wire parsing, so it has to follow the
 * spec rather than the shape one server happens to emit: the colon in a field
 * line may be followed by an optional single space, and both forms mean the
 * same thing. Spring's `SseEmitter` writes `event:foo` / `data:{...}` with no
 * space, so a parser that only matched `"data: "` silently dropped every event.
 *
 * Returns a `feed(chunk)` function; call it with each decoded chunk. Complete
 * events are handed to `onEvent`. Partial lines are buffered across chunks, so
 * an event split over two network reads still arrives intact.
 */
export function createSseParser(onEvent: (type: string, data: string) => void) {
  let buf = '';
  let eventType = 'message';
  let data = '';

  return function feed(chunk: string): void {
    buf += chunk;
    const lines = buf.split('\n');
    // The trailing element is an incomplete line — hold it for the next chunk.
    buf = lines.pop() ?? '';

    for (const rawLine of lines) {
      const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine;

      // Blank line = end of event.
      if (line === '') {
        if (data) onEvent(eventType, data);
        eventType = 'message';
        data = '';
        continue;
      }

      // Lines starting with a colon are comments (our keep-alive heartbeats).
      if (line.startsWith(':')) continue;

      const colon = line.indexOf(':');
      const field = colon === -1 ? line : line.slice(0, colon);
      let value = colon === -1 ? '' : line.slice(colon + 1);
      // The spec strips exactly one optional leading space after the colon.
      if (value.startsWith(' ')) value = value.slice(1);

      if (field === 'event') {
        eventType = value;
      } else if (field === 'data') {
        data += (data ? '\n' : '') + value;
      }
      // `id` and `retry` are unused — we reconnect on our own schedule.
    }
  };
}
