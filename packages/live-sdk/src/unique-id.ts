/** Canonical unique-id representation shared by the API client and reconnect path. */
export function normalizeUniqueId(input: string): string {
  const value = input.trim();
  if (value.startsWith('@@')) throw new RangeError('uniqueId must be a TikTok username');
  const uniqueId = value.startsWith('@') ? value.slice(1) : value;
  if (!/^[A-Za-z0-9._]{1,24}$/.test(uniqueId)) {
    throw new RangeError('uniqueId must be a TikTok username');
  }
  return uniqueId;
}
