/** Bound retained decoded HTTP bytes, including compressed/chunked Fetch responses. */
export async function readResponseText(response: Response, limit: number): Promise<string> {
  const declared = Number(response.headers?.get('content-length'));
  if (Number.isFinite(declared) && declared > limit) throw new Error(`HTTP body exceeds ${limit} bytes`);
  if (!response.body) {
    const text = await response.text();
    if (Buffer.byteLength(text) > limit) throw new Error(`HTTP body exceeds ${limit} bytes`);
    return text;
  }
  const reader = response.body.getReader();
  let buffer = Buffer.allocUnsafe(Math.min(limit, 64 * 1024));
  let bytes = 0;
  try {
    for (;;) {
      const item = await reader.read();
      if (item.done) break;
      if (item.value.byteLength > limit - bytes) throw new Error(`HTTP body exceeds ${limit} bytes`);
      const needed = bytes + item.value.byteLength;
      if (needed > buffer.length) {
        const grown = Buffer.allocUnsafe(Math.min(limit, Math.max(needed, buffer.length * 2)));
        buffer.copy(grown, 0, 0, bytes);
        buffer = grown;
      }
      buffer.set(item.value, bytes);
      bytes = needed;
    }
    return buffer.toString('utf8', 0, bytes);
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error;
  } finally {
    reader.releaseLock();
  }
}
