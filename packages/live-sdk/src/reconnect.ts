export interface ReconnectPolicy {
  attempts: number;
  initialMs: number;
  maxMs: number;
}

export const DEFAULT_RECONNECT: Readonly<ReconnectPolicy> = Object.freeze({
  attempts: 5,
  initialMs: 2_000,
  maxMs: 60_000,
});

/** Exponential delay with a 0–50% positive jitter, injectable for deterministic tests. */
export function reconnectDelay(
  policy: ReconnectPolicy,
  attempt: number,
  random: () => number = Math.random,
): number {
  const exponent = Math.max(0, Math.min(30, Math.floor(attempt) - 1));
  const base = Math.min(policy.initialMs * 2 ** exponent, policy.maxMs);
  const jitter = Math.min(1, Math.max(0, random()));
  return Math.min(policy.maxMs, Math.floor(base * (1 + jitter * 0.5)));
}
