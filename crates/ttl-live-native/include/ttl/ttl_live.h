#ifndef TTL_LIVE_H
#define TTL_LIVE_H
#include "ttl_event.h"
#include "ttl_error.h"
TTL_BEGIN
typedef struct ttl_live_client ttl_live_client_t;
typedef enum ttl_connection_state {
    TTL_CONNECTION_DISCONNECTED = 0, TTL_CONNECTION_CONNECTING = 1,
    TTL_CONNECTION_CONNECTED = 2, TTL_CONNECTION_RECONNECTING = 3,
    TTL_CONNECTION_OFFLINE = 4, TTL_CONNECTION_ERROR = 5
} ttl_connection_state_t;
typedef struct ttl_live_options {
    uint32_t struct_size;
    const char *unique_id;
    size_t unique_id_len;
    const char *bundle;
    size_t bundle_len;
    bool reconnect;
    uint32_t max_reconnect_attempts; /* retries after initial attempt; <=1000 */
    /* Optional ABI extension: explicit guest/account session. No automatic
     * account login, bundle download, subprocess, or signing server. With no
     * cookies, the worker uses shared anonymous guest bootstrap. */
    const char *cookie_header;
    size_t cookie_header_len;
} ttl_live_options_t;
typedef void (*ttl_event_callback_t)(void *userdata, const ttl_event_t *event);
typedef void (*ttl_batch_callback_t)(void *userdata, const ttl_batch_t *batch);
typedef void (*ttl_state_callback_t)(void *userdata, ttl_connection_state_t state, const char *detail, size_t detail_len);
typedef void (*ttl_error_callback_t)(void *userdata, const ttl_error_info_t *error);
/* create copies all input strings. It prepares a warm in-process signer from
 * trusted explicit JS source when explicit cookies are present; otherwise it
 * prepares the signer after guest identity acquisition. NULL on failure: inspect ttl_last_error_code.
 * connect is asynchronous and resolves uniqueId with existing discovery.
 * Each client has an independent worker/runtime/signer; no process connection
 * state. Callbacks for one client are serialized on its worker thread, batch
 * callback first, then events in batch order. No event filters by default.
 * Exceptions/longjmp/unwinding across callbacks are forbidden.
 * Callback registration is synchronized. Clearing a callback from another
 * thread waits for in-flight callbacks; afterward its old userdata is unused.
 * disconnect disables delivery, cancels discovery/signing/open/retry/drain,
 * and joins the worker. It is safe repeatedly. From a callback, disconnect is
 * a nonblocking stop request; another thread must later join/destroy.
 * destroy is synchronous and prevents all subsequent callbacks. Never destroy
 * on the callback thread: it is refused (INVALID_STATE), leaving the handle
 * alive. Do not destroy concurrently with ANY other API using that handle.
 * A stopped client may be connected again; registrations are retained.
 * NULL callbacks clear registrations. Client functions return explicit codes;
 * asynchronous failures go through error/state callbacks with redacted detail.
 */
TTL_API ttl_live_client_t *ttl_live_client_create(const ttl_live_options_t *options);
TTL_API ttl_result_t ttl_live_client_connect(ttl_live_client_t *client);
TTL_API ttl_result_t ttl_live_client_disconnect(ttl_live_client_t *client);
TTL_API void ttl_live_client_destroy(ttl_live_client_t *client);
TTL_API ttl_result_t ttl_live_client_set_event_callback(ttl_live_client_t *client, ttl_event_callback_t callback, void *userdata);
TTL_API ttl_result_t ttl_live_client_set_batch_callback(ttl_live_client_t *client, ttl_batch_callback_t callback, void *userdata);
TTL_API ttl_result_t ttl_live_client_set_state_callback(ttl_live_client_t *client, ttl_state_callback_t callback, void *userdata);
TTL_API ttl_result_t ttl_live_client_set_error_callback(ttl_live_client_t *client, ttl_error_callback_t callback, void *userdata);
/* Optional asynchronous callback mode; configure while disconnected.
 * capacity=0 preserves synchronous callbacks on the LIVE worker. 1..256 bounds
 * queued deliveries (batches/state/error notifications) on one dispatcher.
 * All callbacks for a client remain ordered on one thread. Overflow stops the
 * connection, releases queued events and reports a terminal error on the
 * dispatcher; no silent event-drop policy. Disconnect discards pending delivery
 * and waits for in-progress callbacks outside callback-initiated disconnect.
 * Call disconnect before reconnecting a stopped asynchronous client.
 */
TTL_API ttl_result_t ttl_live_client_set_async_delivery(ttl_live_client_t *client, size_t capacity);
typedef struct ttl_delivery_metrics {
    size_t queue_depth;
    uint64_t events_received;
    uint64_t events_dispatched;
    uint64_t events_discarded;
    uint64_t queue_rejections;
} ttl_delivery_metrics_t;
TTL_API bool ttl_live_client_delivery_metrics(const ttl_live_client_t *client, ttl_delivery_metrics_t *out);
/* Owned label-free Prometheus counters/histograms. Release with ttl_string_free. */
TTL_API char *ttl_live_client_metrics_prometheus(const ttl_live_client_t *client);
TTL_END
#endif
