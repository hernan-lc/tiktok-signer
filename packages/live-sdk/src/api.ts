import { LiveApiError, ProtocolError } from './errors.js';
import { normalizeUniqueId } from './unique-id.js';

export type ConnectionStatus = 'live' | 'offline';

export interface ConnectionDescriptor {
  url: string;
  cookies: string;
  userAgent: string;
}

export interface ConnectResponse {
  version: 1;
  uniqueId: string;
  roomId?: string;
  status: ConnectionStatus;
  connection?: ConnectionDescriptor;
}

export type FetchLike = typeof fetch;

export interface ConnectApiOptions {
  apiUrl?: string;
  /** Alias retained for callers that name the broker setting `apiBaseUrl`. */
  apiBaseUrl?: string;
  apiKey?: string;
  timeoutMs?: number;
  fetchImpl?: FetchLike;
}

const DEFAULT_API_URL = 'http://127.0.0.1:8080';

/** Typed client for the narrow `/v1/connect` broker endpoint. */
export class ConnectApi {
  readonly apiUrl: string;
  readonly apiKey?: string;
  readonly timeoutMs: number;
  readonly #fetch: FetchLike;

  constructor(options: ConnectApiOptions = {}) {
    this.apiUrl = normalizeApiUrl(options.apiUrl ?? options.apiBaseUrl ?? DEFAULT_API_URL);
    this.apiKey = options.apiKey;
    this.timeoutMs = boundedTimeout(options.timeoutMs ?? 10_000);
    this.#fetch = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
  }

  async connect(uniqueId: string): Promise<ConnectResponse> {
    uniqueId = normalizeUniqueId(uniqueId);
    const url = new URL('/v1/connect', this.apiUrl).toString();
    const headers: Record<string, string> = {
      accept: 'application/json',
      'content-type': 'application/json',
    };
    if (this.apiKey) headers.authorization = `Bearer ${this.apiKey}`;

    let response: Response;
    try {
      response = await this.#fetch(url, {
        method: 'POST',
        headers,
        body: JSON.stringify({ uniqueId }),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch (error) {
      const detail = error instanceof Error ? `: ${error.message}` : '';
      throw new LiveApiError('DISCOVERY_FAILED', `connection broker request failed${detail}`, {
        retryable: true,
      });
    }

    const requestId = response.headers.get('x-request-id') ?? undefined;
    const raw = await readBody(response);
    let body: unknown;
    try {
      body = raw ? JSON.parse(raw) : undefined;
    } catch {
      throw new ProtocolError(`connection broker returned invalid JSON (HTTP ${response.status})`);
    }

    if (!response.ok) throw parseApiError(response.status, requestId, body);
    return parseConnectResponse(body, uniqueId);
  }
}

function normalizeApiUrl(value: string): string {
  try {
    const url = new URL(value);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') {
      throw new Error('API URL must use HTTP or HTTPS');
    }
    return url.toString().replace(/\/$/, '');
  } catch (error) {
    throw new TypeError(`invalid API URL: ${String(error)}`);
  }
}

function boundedTimeout(value: number): number {
  if (!Number.isFinite(value) || value <= 0) return 10_000;
  return Math.min(Math.floor(value), 2_147_483_647);
}

async function readBody(response: Response): Promise<string> {
  try {
    return await response.text();
  } catch {
    throw new ProtocolError(`connection broker response body could not be read (HTTP ${response.status})`);
  }
}

function parseApiError(status: number, requestId: string | undefined, body: unknown): LiveApiError {
  const root = isRecord(body) && isRecord(body.error) ? body.error : {};
  const code = typeof root.code === 'string' ? root.code : status === 429 ? 'RATE_LIMITED' : 'INTERNAL_ERROR';
  const message = typeof root.message === 'string' ? root.message : `connection broker returned HTTP ${status}`;
  const retryable = typeof root.retryable === 'boolean' ? root.retryable : status >= 500 || status === 429;
  const retryAfterMs = typeof root.retryAfterMs === 'number' && Number.isFinite(root.retryAfterMs)
    ? Math.max(0, Math.floor(root.retryAfterMs))
    : undefined;
  return new LiveApiError(code, message, { retryable, retryAfterMs, status, requestId });
}

function parseConnectResponse(value: unknown, requestedUniqueId: string): ConnectResponse {
  if (!isRecord(value)) throw new ProtocolError('connection broker response is not an object');
  if (value.version !== 1 || typeof value.uniqueId !== 'string' || value.uniqueId !== requestedUniqueId) {
    throw new ProtocolError('connection broker returned mismatched connection metadata');
  }
  if (value.status !== 'live' && value.status !== 'offline') {
    throw new ProtocolError('connection broker returned an unknown connection status');
  }
  if (value.status === 'offline') {
    return {
      version: 1,
      uniqueId: value.uniqueId,
      ...(isDigits(value.roomId) ? { roomId: value.roomId } : {}),
      status: 'offline',
    };
  }
  if (!isDigits(value.roomId) || value.roomId === '0' || !isRecord(value.connection)) {
    throw new ProtocolError('live connection response is missing a valid descriptor');
  }
  const connection = value.connection;
  if (typeof connection.url !== 'string' || !isAllowedSocketUrl(connection.url, value.roomId)
      || typeof connection.cookies !== 'string' || typeof connection.userAgent !== 'string') {
    throw new ProtocolError('live connection descriptor is invalid');
  }
  return {
    version: 1,
    uniqueId: value.uniqueId,
    roomId: value.roomId,
    status: 'live',
    connection: {
      url: connection.url,
      cookies: connection.cookies,
      userAgent: connection.userAgent,
    },
  };
}

function isAllowedSocketUrl(value: string, roomId: string): boolean {
  try {
    const url = new URL(value);
    const signature = url.searchParams.get('X-Gnarly');
    return url.protocol === 'wss:'
      && ['webcast-ws.tiktok.com', 'webcast-ws.us.tiktok.com', 'webcast-ws.eu.tiktok.com'].includes(url.hostname)
      && url.pathname === '/webcast/im/ws_proxy/ws_reuse_supplement/'
      && url.searchParams.get('room_id') === roomId
      && signature !== null && signature.length > 0;
  } catch {
    return false;
  }
}

function isDigits(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9]+$/.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
