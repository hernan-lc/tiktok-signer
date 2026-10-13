import { performance } from 'node:perf_hooks';

export interface DeliveryOptions {
  /** Zero (default) preserves synchronous EventEmitter delivery. */
  capacity: number;
  maxBytes?: number;
  batchSize?: number;
  timeBudgetMs?: number;
}
export interface DeliveryMetrics {
  queueDepth: number;
  queuedBytes: number;
  received: number;
  dispatched: number;
  discarded: number;
  rejections: number;
}
interface Entry<T> { value: T; generation: number; bytes: number }

/** One bounded FIFO and one owned immediate. Overflow terminates delivery through the owner. */
export class EventDelivery<T> {
  readonly #capacity: number;
  readonly #maxBytes: number;
  readonly #batchSize: number;
  readonly #timeBudgetMs: number;
  readonly #consume: (value: T, generation: number) => boolean;
  readonly #overflow: (error: Error) => void;
  readonly #queue: Array<Entry<T> | undefined>;
  #head = 0;
  #depth = 0;
  #bytes = 0;
  #timer: NodeJS.Immediate | null = null;
  #received = 0;
  #dispatched = 0;
  #discarded = 0;
  #rejections = 0;

  constructor(options: DeliveryOptions | undefined,
    consume: (value: T, generation: number) => boolean, overflow: (error: Error) => void) {
    this.#capacity = options?.capacity ?? 0;
    this.#maxBytes = options?.maxBytes ?? 64 * 1024 * 1024;
    this.#batchSize = options?.batchSize ?? 64;
    this.#timeBudgetMs = options?.timeBudgetMs ?? 8;
    if (!Number.isSafeInteger(this.#capacity) || this.#capacity < 0 || this.#capacity > 65536
      || !Number.isSafeInteger(this.#maxBytes) || this.#maxBytes <= 0
      || !Number.isSafeInteger(this.#batchSize) || this.#batchSize <= 0
      || !Number.isFinite(this.#timeBudgetMs) || this.#timeBudgetMs <= 0) {
      throw new RangeError('invalid event delivery capacity or scheduling budget');
    }
    this.#queue = new Array(this.#capacity);
    this.#consume = consume;
    this.#overflow = overflow;
  }
  submit(value: T, generation: number, bytes: number): void {
    this.#received++;
    if (this.#capacity === 0) {
      if (this.#consume(value, generation)) this.#dispatched++; else this.#discarded++;
      return;
    }
    if (this.#depth === this.#capacity || bytes > this.#maxBytes - this.#bytes
      || !Number.isSafeInteger(bytes) || bytes < 0) {
      this.#rejections++;
      this.#discarded++;
      this.clear();
      this.#overflow(new Error('event delivery queue overflow; LIVE connection stopped'));
      return;
    }
    this.#queue[(this.#head + this.#depth) % this.#capacity] = { value, generation, bytes };
    this.#depth++;
    this.#bytes += bytes;
    this.#schedule();
  }
  #schedule(): void {
    if (this.#timer || this.#depth === 0) return;
    this.#timer = setImmediate(() => {
      this.#timer = null;
      const start = performance.now();
      let count = 0;
      while (this.#depth > 0 && count++ < this.#batchSize) {
        const entry = this.#queue[this.#head]!;
        this.#queue[this.#head] = undefined;
        this.#head = (this.#head + 1) % this.#capacity;
        this.#depth--;
        this.#bytes -= entry.bytes;
        if (this.#consume(entry.value, entry.generation)) this.#dispatched++; else this.#discarded++;
        if (performance.now() - start >= this.#timeBudgetMs) break;
      }
      this.#schedule();
    });
  }
  clear(): void {
    if (this.#timer) clearImmediate(this.#timer);
    this.#timer = null;
    this.#discarded += this.#depth;
    this.#queue.fill(undefined);
    this.#depth = 0;
    this.#head = 0;
    this.#bytes = 0;
  }
  metrics(): DeliveryMetrics {
    return { queueDepth: this.#depth, queuedBytes: this.#bytes, received: this.#received,
      dispatched: this.#dispatched, discarded: this.#discarded, rejections: this.#rejections };
  }
}
