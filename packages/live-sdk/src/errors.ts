export type LiveApiErrorCode =
  | 'INVALID_UNIQUE_ID'
  | 'USER_NOT_FOUND'
  | 'OFFLINE'
  | 'DISCOVERY_FAILED'
  | 'TIKTOK_REFUSED'
  | 'SIGNER_UNAVAILABLE'
  | 'SIGN_FAILED'
  | 'RATE_LIMITED'
  | 'AUTHENTICATION_REQUIRED'
  | 'INVALID_API_KEY'
  | 'INTERNAL_ERROR'
  | 'PROTOCOL_ERROR'
  | 'SOCKET_ERROR';

export class LiveApiError extends Error {
  readonly code: LiveApiErrorCode | string;
  readonly retryable: boolean;
  readonly retryAfterMs?: number;
  readonly status?: number;
  readonly requestId?: string;

  constructor(
    code: LiveApiErrorCode | string,
    message: string,
    options: {
      retryable?: boolean;
      retryAfterMs?: number;
      status?: number;
      requestId?: string;
    } = {},
  ) {
    super(message);
    this.name = 'LiveApiError';
    this.code = code;
    this.retryable = options.retryable ?? false;
    this.retryAfterMs = options.retryAfterMs;
    this.status = options.status;
    this.requestId = options.requestId;
  }
}

export class ProtocolError extends LiveApiError {
  constructor(message: string) {
    super('PROTOCOL_ERROR', message, { retryable: false });
    this.name = 'ProtocolError';
  }
}

export class SocketError extends LiveApiError {
  readonly closeCode?: number;

  constructor(message: string, closeCode?: number) {
    super('SOCKET_ERROR', message, { retryable: true });
    this.name = 'SocketError';
    this.closeCode = closeCode;
  }
}

export function asError(error: unknown): Error {
  return error instanceof Error ? error : new Error(String(error));
}

export function isRetryable(error: unknown): boolean {
  return error instanceof LiveApiError ? error.retryable : true;
}
