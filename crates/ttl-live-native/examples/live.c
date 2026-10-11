#include <ttl/ttl.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void print_text(FILE *output, const char *value, size_t length) {
    if (value == NULL) return;
    for (size_t index = 0; index < length; index++) {
        unsigned char character = (unsigned char)value[index];
        if (character == '\n' || character == '\r' || character == '\t') {
            fputc(' ', output);
        } else if (character < 0x20 || character == 0x7f) {
            fputc('?', output);
        } else {
            fputc(character, output);
        }
    }
}

static void print_user(const ttl_user_t *user) {
    if (user->unique_id != NULL && user->unique_id_len > 0) {
        fputc('@', stdout);
        print_text(stdout, user->unique_id, user->unique_id_len);
    } else if (user->nickname != NULL && user->nickname_len > 0) {
        print_text(stdout, user->nickname, user->nickname_len);
    } else {
        fputs("Someone", stdout);
    }
}

static void print_user_activity(const ttl_event_t *event, const char *label, const char *detail) {
    ttl_user_t user;
    printf("[%s] ", label);
    if (ttl_event_get_user(event, &user)) {
        print_user(&user);
    } else {
        fputs("Someone", stdout);
    }
    printf(" %s.\n", detail);
}

static void print_other_event(const ttl_event_t *event) {
    const char *method = ttl_event_method(event);
    printf("[event] %.*s received (%zu fields, %zu bytes)",
        (int)ttl_event_method_len(event), method,
        ttl_object_field_count(ttl_event_root(event)), ttl_event_raw_size(event));
    if ((ttl_event_flags(event) & TTL_EVENT_FLAG_DECODE_WARNING) != 0) {
        fputs(" [decode warning]", stdout);
    }
    if (ttl_event_is_truncated(event)) {
        fputs(" [truncated]", stdout);
    }
    fputc('\n', stdout);
}

static void on_event(void *userdata, const ttl_event_t *event) {
    (void)userdata;
    switch (ttl_event_normalized_type(event)) {
        case TTL_NORMALIZED_CHAT: {
            ttl_chat_event_t chat;
            if (ttl_event_chat(event, &chat)) {
                fputs("[chat] ", stdout);
                print_user(&chat.user);
                fputs(": ", stdout);
                print_text(stdout, chat.comment, chat.comment_len);
                fputc('\n', stdout);
                break;
            }
            print_other_event(event);
            break;
        }
        case TTL_NORMALIZED_GIFT: {
            ttl_gift_event_t gift;
            if (ttl_event_gift(event, &gift)) {
                fputs("[gift] ", stdout);
                print_user(&gift.user);
                fputs(" sent ", stdout);
                print_text(stdout, gift.gift_name, gift.gift_name_len);
                if (gift.repeat_count > 1) printf(" x%" PRIu64, gift.repeat_count);
                if (gift.diamond_count > 0) printf(" (%" PRIu64 " diamonds)", gift.diamond_count);
                fputc('\n', stdout);
                break;
            }
            print_other_event(event);
            break;
        }
        case TTL_NORMALIZED_LIKE:
            print_user_activity(event, "like", "liked the live");
            break;
        case TTL_NORMALIZED_MEMBER:
            print_user_activity(event, "member", "joined or changed membership");
            break;
        case TTL_NORMALIZED_SOCIAL:
            print_user_activity(event, "social", "performed a social action");
            break;
        case TTL_NORMALIZED_ROOM_USER:
            fputs("[viewers] Audience update received.\n", stdout);
            break;
        default:
            print_other_event(event);
            break;
    }
    fflush(stdout);
}

static const char *state_label(ttl_connection_state_t state) {
    switch (state) {
        case TTL_CONNECTION_DISCONNECTED: return "disconnected";
        case TTL_CONNECTION_CONNECTING: return "connecting";
        case TTL_CONNECTION_CONNECTED: return "connected";
        case TTL_CONNECTION_RECONNECTING: return "reconnecting";
        case TTL_CONNECTION_OFFLINE: return "offline";
        case TTL_CONNECTION_ERROR: return "error";
        default: return "unknown";
    }
}

static void on_state(void *userdata, ttl_connection_state_t state, const char *detail, size_t detail_len) {
    (void)userdata;
    printf("[state] %s", state_label(state));
    if (detail != NULL && detail_len > 0) {
        fputs(": ", stdout);
        print_text(stdout, detail, detail_len);
    }
    fputc('\n', stdout);
    fflush(stdout);
}

static void on_error(void *userdata,const ttl_error_info_t *error) {
    (void)userdata;
    fprintf(stderr, "[error] ");
    print_text(stderr, error->message, error->message_len);
    fprintf(stderr, " (code %d%s", (int)error->code, error->retryable ? ", retryable" : "");
    if (error->http_status != 0) fprintf(stderr, ", HTTP %d", error->http_status);
    fputs(")\n", stderr);
}

int main(int argc,char **argv) {
    if (argc < 3 || argc > 4) {
        fprintf(stderr, "usage: %s @creator trusted-bundle.js [cookie-header]\n", argv[0]);
        fprintf(stderr, "recommended: ./crates/ttl-live-native/examples/live @creator\n");
        return 2;
    }
    FILE *input = fopen(argv[2], "rb");
    if (input == NULL) { perror("bundle"); return 2; }
    if (fseek(input, 0, SEEK_END) != 0) { fclose(input); return 2; }
    long size = ftell(input);
    if (size <= 0 || size > 8 * 1024 * 1024) { fclose(input); return 2; }
    rewind(input);
    char *bundle = (char *)malloc((size_t)size);
    if (bundle == NULL) { fclose(input); return 2; }
    if (fread(bundle, 1, (size_t)size, input) != (size_t)size) { free(bundle); fclose(input); return 2; }
    fclose(input);
    ttl_live_options_t options;
    memset(&options, 0, sizeof(options));
    options.struct_size = (uint32_t)sizeof(options);
    options.unique_id = argv[1];
    options.unique_id_len = strlen(argv[1]);
    options.bundle = bundle;
    options.bundle_len = (size_t)size;
    options.reconnect = true;
    options.max_reconnect_attempts = 5;
    if (argc > 3) {
        options.cookie_header = argv[3];
        options.cookie_header_len = strlen(argv[3]);
    }
    ttl_live_client_t *client = ttl_live_client_create(&options);
    free(bundle);
    if (client == NULL) { fprintf(stderr, "create failed: %s\n", ttl_last_error()); return 1; }
    ttl_live_client_set_event_callback(client, on_event, NULL);
    ttl_live_client_set_state_callback(client, on_state, NULL);
    ttl_live_client_set_error_callback(client, on_error, NULL);
    printf("Listening to %s. Press Enter to stop.\n", argv[1]);
    fflush(stdout);
    if (ttl_live_client_connect(client) != TTL_OK) {
        fprintf(stderr, "connect failed: %s\n", ttl_last_error());
        ttl_live_client_destroy(client);
        return 1;
    }
    (void)getchar();
    ttl_live_client_destroy(client);
    return 0;
}
