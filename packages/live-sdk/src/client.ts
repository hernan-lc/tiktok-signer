import { EventDelivery, type DeliveryOptions, type DeliveryMetrics } from "../../tiktok-live/src/delivery.js";
import { EventEmitter } from 'node:events';
import WebSocket from 'ws';

import { ConnectApi } from './api.js';
import type { ConnectApiOptions, ConnectionDescriptor, ConnectionStatus } from './api.js';
import { asError, isRetryable, LiveApiError, ProtocolError, SocketError } from './errors.js';
import { boundedHeartbeatMs, startHeartbeat } from './heartbeat.js';
import { DEFAULT_RECONNECT, reconnectDelay } from './reconnect.js';
import {
  ackFrame,
  carriesEvents,
  decodeBatch,
  decodePushFrame,
  decompress,
  enterRoomFrame,
} from './protocol.js';
import { createDirectSocket } from './transport.js';
import { normalizeUniqueId } from './unique-id.js';
import { EVENT, decodeEvent } from './events.js';
import type {
  ChatEvent,
  Gift,
  GiftEvent,
  LikeEvent,
  LiveEvent,
  MemberEvent,
  RoomUserEvent,
  SocialEvent,
  UnknownEvent,
} from './events.js';
import { Discovery } from '../../tiktok-live/src/discovery.js';
import type { WebSocketConstructor, WebSocketLike } from './protocol.js';

const OPEN_TIMEOUT_MS = 15_000;

export interface TikTokLiveOptions extends ConnectApiOptions {
  eventDelivery?: DeliveryOptions;
  /** Fetch the room gift catalog directly from TikTok once per live descriptor. */
  fetchGifts?: boolean;
  reconnect?: Partial<ReconnectPolicy>;
  WebSocketImpl?: WebSocketConstructor;
}

export interface ReconnectPolicy {
  attempts: number;
  initialMs: number;
  maxMs: number;
}

export interface LiveState {
  uniqueId: string;
  roomId: string | null;
  status: ConnectionStatus;
  connected: boolean;
}

export interface TikTokLiveEvents {
  connected: [LiveState];
  offline: [];
  disconnected: [{ code: number; reason: string }];
  reconnecting: [{ attempt: number; delayMs: number }];
  error: [Error];
  event: [LiveEvent];
  chat: [ChatEvent];
  gift: [GiftEvent];
  like: [LikeEvent];
  member: [MemberEvent];
  social: [SocialEvent];
  roomUser: [RoomUserEvent];
  unknown: [UnknownEvent];
}

/**
 * Direct Node.js TikTok LIVE client backed by a connection-ticket API.
 *
 * Only `uniqueId` crosses the public API boundary. The room id and signed URL are broker metadata;
 * they are never accepted as constructor options and a reconnect always obtains a fresh descriptor.
 */
export class TikTokLive extends EventEmitter<TikTokLiveEvents> {
  readonly uniqueId: string;
  readonly api: ConnectApi;

  readonly #options: {
    fetchGifts: boolean;
    reconnect: ReconnectPolicy;
    WebSocketImpl?: WebSocketConstructor;
  };
  #roomId: string | null = null;
  #status: ConnectionStatus = 'offline';
  #socket: WebSocketLike | null = null;
  // Set only on the socket `open` event. `#socket` is assigned (and `#status` becomes
  // `live`) as soon as the handshake starts, so neither alone means the connection is
  // usable — `connected` must wait for the actual open.
  #socketOpen = false;
  #heartbeat: NodeJS.Timeout | null = null;
  #reconnectTimer: NodeJS.Timeout | null = null;
  #connecting: Promise<LiveState> | null = null;
  #closing = false;
  #generation = 0;
  #listenerErrors = 0;
  readonly #delivery: EventDelivery<LiveEvent>;
  #cancelOpen: (() => void) | null = null;
  #attempt = 0;
  #gifts = new Map<string, Gift>();

  constructor(uniqueId: string, options: TikTokLiveOptions = {}) {
    super();
    this.#delivery = new EventDelivery(options.eventDelivery,
      (event, generation) => this.#dispatch(event, generation),
      (error) => { this.disconnect(); this.#emitError(error); });
    this.uniqueId = normalizeUniqueId(uniqueId);
    this.api = new ConnectApi(options);
    this.#options = {
      fetchGifts: options.fetchGifts ?? false,
      reconnect: {
        ...DEFAULT_RECONNECT,
        ...(options.reconnect ?? {}),
      },
      WebSocketImpl: options.WebSocketImpl,
    };
  }

  get roomId(): string | null {
    return this.#roomId;
  }

  get status(): ConnectionStatus {
    return this.#status;
  }

  get connected(): boolean {
    return this.#socketOpen && this.#status === 'live';
  }

  get gifts(): ReadonlyMap<string, Gift> {
    return this.#gifts;
  }

  connect(): Promise<LiveState> {
    if (this.connected) return Promise.resolve(this.state());
    if (this.#connecting) return this.#connecting;
    this.#closing = false;
    this.#closing = false;
    if (this.#reconnectTimer) clearTimeout(this.#reconnectTimer);
    this.#reconnectTimer = null;
    const generation = ++this.#generation;
    const connecting = this.#connectFromApi(generation);
    this.#connecting = connecting;
    void connecting.finally(() => {
      if (this.#connecting === connecting) this.#connecting = null;
    }).catch(() => undefined);
    return connecting;
  }

  state(): LiveState {
    return {
      uniqueId: this.uniqueId,
      roomId: this.#roomId,
      status: this.#status,
      connected: this.connected,
    };
  }

  disconnect(): void {
    this.#closing = true;
    this.#generation += 1;
    this.#delivery.clear();
    this.#connecting = null;
    this.#cancelOpen?.();
    this.#cancelOpen = null;
    this.#socketOpen = false;
    if (this.#reconnectTimer) clearTimeout(this.#reconnectTimer);
    this.#reconnectTimer = null;
    this.#stopHeartbeat();
    const socket = this.#socket;
    this.#socket = null;
    if (socket) {
      try {
        socket.close();
      } catch {
        // The peer may already have closed it.
      }
    }
  }

  async #connectFromApi(generation: number): Promise<LiveState> {
    const descriptor = await this.api.connect(this.uniqueId);
    this.#checkGeneration(generation);

    this.#roomId = descriptor.roomId ?? null;
    this.#status = descriptor.status;
    if (descriptor.status === 'offline') {
      this.#stopHeartbeat();
      this.#announceOffline();
      return this.state();
    }
    if (!descriptor.connection || !descriptor.roomId) {
      throw new ProtocolError('live response did not include connection metadata');
    }

    if (this.#options.fetchGifts) await this.#loadGifts(descriptor.connection, descriptor.roomId, generation);
    this.#checkGeneration(generation);
    await this.#open(descriptor.connection, descriptor.roomId, generation);
    return this.state();
  }

  async #loadGifts(descriptor: ConnectionDescriptor, roomId: string, generation: number): Promise<void> {
    try {
      const discovery = new Discovery({
        cookie: descriptor.cookies,
        userAgent: descriptor.userAgent,
        timeoutMs: this.api.timeoutMs,
      });
      const gifts = await discovery.giftList(roomId);
      this.#checkGeneration(generation);
      this.#gifts = gifts;
    } catch (error) {
      this.#checkGeneration(generation);
      this.#emitError(error);
      this.#gifts = new Map<string, Gift>();
    }
  }

  async #open(descriptor: ConnectionDescriptor, roomId: string, generation: number): Promise<void> {
    this.#checkGeneration(generation);
    const implementation = this.#options.WebSocketImpl
      ?? (WebSocket as unknown as WebSocketConstructor);

    const socket = createDirectSocket(descriptor, implementation);
    if (this.#closing || generation !== this.#generation) {
      try { socket.close(); } catch { /* Already closed. */ }
      throw new SocketError('connection cancelled');
    }
    this.#socket = socket;
    await new Promise<void>((resolve, reject) => {
      let settled = false;
      let opened = false;
      const timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        try {
          socket.close();
        } catch {
          // Already closed.
        }
        reject(new SocketError(`TikTok WebSocket did not open within ${OPEN_TIMEOUT_MS} ms`));
      }, OPEN_TIMEOUT_MS);
      timer.unref?.();

      this.#cancelOpen = () => {
        clearTimeout(timer);
        if (!settled) {
          settled = true;
          reject(new SocketError('connection cancelled'));
        }
      };

      socket.addEventListener('open', () => {
        if (settled) return;
        if (this.#closing || generation !== this.#generation || this.#socket !== socket) {
          settled = true;
          clearTimeout(timer);
          socket.close();
          reject(new SocketError('connection cancelled'));
          return;
        }
        try {
          socket.send(enterRoomFrame({ roomId }));
        } catch (error) {
          settled = true;
          clearTimeout(timer);
          try {
            socket.close();
          } catch {
            // Already closed.
          }
          reject(new SocketError(`enter-room frame could not be sent: ${String(error)}`));
          return;
        }
        opened = true;
        this.#startHeartbeat(socket, roomId, 10_000);
        this.#status = 'live';
        this.#socketOpen = true;
        this.#attempt = 0;
        clearTimeout(timer);
        settled = true;
        resolve();
        this.#cancelOpen = null;
        this.#notify(() => this.emit('connected', this.state()));
      });

      socket.addEventListener('message', (message) => {
        if (!this.#closing && this.#socket === socket && generation === this.#generation) this.#receive(socket, message.data);
      });

      socket.addEventListener('error', (event) => {
        if (this.#socket !== socket || generation !== this.#generation) return;
        const error = new SocketError(event.message ?? 'TikTok WebSocket error');
        if (settled) this.#emitError(error);
      });

      socket.addEventListener('close', (event) => {
        clearTimeout(timer);
        if (this.#socket !== socket) return;
        this.#cancelOpen = null;
        if (this.#socket === socket) this.#socket = null;
        this.#socketOpen = false;
        this.#stopHeartbeat();
        clearTimeout(timer);
        if (!opened) {
          if (!settled) {
            settled = true;
            reject(new SocketError(
              `TikTok WebSocket handshake was rejected (code ${event.code})`,
              event.code,
            ));
          }
          return;
        }
        this.#status = 'live';
        this.#notify(() => this.emit('disconnected', { code: event.code, reason: String(event.reason ?? '') }));
        if (!this.#closing) this.#scheduleReconnect();
      });
    });
  }

  #receive(socket: WebSocketLike, data: ArrayBuffer | ArrayBufferView): void {
    const generation = this.#generation;
    let frame;
    try {
      frame = decodePushFrame(data);
    } catch (error) {
      this.#emitError(error);
      return;
    }
    if (!carriesEvents(frame)) return;

    let batch;
    try {
      batch = decodeBatch(decompress(frame));
    } catch (error) {
      this.#emitError(error);
      return;
    }

    if (batch.heartbeatDuration > 0n) {
      this.#startHeartbeat(socket, this.#roomId ?? '0', boundedHeartbeatMs(batch.heartbeatDuration));
    }

    // ACK before normalizing or emitting any event. Slow application listeners cannot make the
    // TikTok push server decide this client is dead.
    if (batch.needAck) {
      try {
        socket.send(ackFrame(frame, batch.internalExt));
      } catch (error) {
        this.#emitError(error);
      }
    }

    for (const message of batch.messages) {
      const event = this.#enrich(decodeEvent(message.method, message.payload));
      event.msgId = message.msgId.toString();
      event.isHistory = message.isHistory;
      if (this.#closing || generation !== this.#generation) break;
      // Include the backing buffer for unknown events: a small view can retain a whole batch.
      const metadataBytes = event.method.length * 2 + (event.msgId?.length ?? 0) * 2 + 256;
      const bytes = event.type === 'unknown' ? event.payload.buffer.byteLength + metadataBytes
        : message.payload.byteLength * 64 + metadataBytes;
      this.#delivery.submit(event, generation, bytes);
    }
  }

  #enrich(event: LiveEvent): LiveEvent {
    if (event.type !== EVENT.gift) return event;
    const gift = this.#gifts.get(event.giftId);
    if (!gift) return event;
    event.giftName ||= gift.name;
    event.diamondCount ||= gift.diamondCount;
    event.giftIconUrl = gift.iconUrl || undefined;
    event.streakable = gift.combo;
    return event;
  }

  #startHeartbeat(socket: WebSocketLike, roomId: string, everyMs: number): void {
    this.#stopHeartbeat();
    this.#heartbeat = startHeartbeat(roomId, everyMs, (frame) => {
      if (this.#socket === socket) socket.send(frame);
    });
  }

  #stopHeartbeat(): void {
    if (this.#heartbeat) clearInterval(this.#heartbeat);
    this.#heartbeat = null;
  }

  #scheduleReconnect(minDelayMs = 0): void {
    if (this.#closing || this.#reconnectTimer) return;
    const policy = this.#options.reconnect;
    if (this.#attempt >= Math.max(0, Math.floor(policy.attempts))) {
      this.#emitError(new LiveApiError(
        'SOCKET_ERROR',
        `gave up after ${Math.max(0, Math.floor(policy.attempts))} reconnection attempts`,
      ));
      return;
    }
    this.#attempt += 1;
    const delayMs = Math.max(reconnectDelay(policy, this.#attempt), Math.max(0, minDelayMs));
    const generation = this.#generation;
    this.#notify(() => this.emit('reconnecting', { attempt: this.#attempt, delayMs }));
    if (this.#closing || generation !== this.#generation) return;
    const timer = setTimeout(() => {
      this.#reconnectTimer = null;
      if (this.#closing || generation !== this.#generation) return;
      this.#reconnectTimer = null;
      void this.#reconnect();
    }, delayMs);
    timer.unref?.();
    this.#reconnectTimer = timer;
  }

  async #reconnect(): Promise<void> {
    let generation = this.#generation;
    try {
      const connecting = this.connect();
      generation = this.#generation;
      const state = await connecting;
      if (state.status === 'offline' && !this.#closing) this.#scheduleReconnect();
    } catch (error) {
      if (this.#closing || generation !== this.#generation) return;
      this.#emitError(error);
      const retryAfterMs = error instanceof LiveApiError ? error.retryAfterMs ?? 0 : 0;
      if (!this.#closing && isRetryable(error)) this.#scheduleReconnect(retryAfterMs);
    }
  }

  #announceOffline(): void {
    this.#notify(() => this.emit('offline'));
  }

  deliveryMetrics(): DeliveryMetrics & { listenerErrors: number } {
    return { ...this.#delivery.metrics(), listenerErrors: this.#listenerErrors };
  }

  #dispatch(event: LiveEvent, generation: number): boolean {
    if (this.#closing || generation !== this.#generation) return false;
    try { this.emit('event', event); } catch (error) { this.#listenerErrors++; this.#emitError(error); }
    if (!this.#closing && generation === this.#generation) {
      try { this.#emitTypedEvent(event); } catch (error) { this.#listenerErrors++; this.#emitError(error); }
    }
    return true;
  }
  #notify(action: () => void): void {
    try { action(); } catch (error) { this.#listenerErrors++; this.#emitError(error); }
  }

  #checkGeneration(generation: number): void {
    if (this.#closing || generation !== this.#generation) throw new SocketError('connection cancelled');
  }

  #emitTypedEvent(event: LiveEvent): void {
    switch (event.type) {
      case EVENT.chat: this.emit('chat', event); break;
      case EVENT.gift: this.emit('gift', event); break;
      case EVENT.like: this.emit('like', event); break;
      case EVENT.member: this.emit('member', event); break;
      case EVENT.social: this.emit('social', event); break;
      case EVENT.roomUser: this.emit('roomUser', event); break;
      case EVENT.unknown: this.emit('unknown', event); break;
    }
  }

  #emitError(error: unknown): void {
    const normalized = asError(error);
    // Node's EventEmitter treats an unhandled `error` event as process-fatal. Keep transport
    // failures observable when a listener is installed without making a missing optional handler
    // take down a long-running SDK process.
    if (this.listenerCount('error') > 0) {
      try { this.emit('error', normalized); } catch { this.#listenerErrors++; }
    }
  }
}
