import type { ConnectionDescriptor } from './api.js';
import { SocketError } from './errors.js';
import type { WebSocketConstructor, WebSocketLike } from './protocol.js';

export function socketHeaders(descriptor: ConnectionDescriptor): Record<string, string> {
  return {
    cookie: descriptor.cookies,
    'user-agent': descriptor.userAgent,
    origin: 'https://www.tiktok.com',
  };
}

export function createDirectSocket(
  descriptor: ConnectionDescriptor,
  implementation: WebSocketConstructor,
): WebSocketLike {
  try {
    const socket = new implementation(descriptor.url, { headers: socketHeaders(descriptor) });
    socket.binaryType = 'arraybuffer';
    return socket;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new SocketError(`TikTok WebSocket could not be created: ${message}`);
  }
}
