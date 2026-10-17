// Generated file. Do not edit manually.
// Source: schema/json/public/chat-event.schema.json

export type ChatEvent = {
  method: string;
  msgId?: string;
  isHistory?: boolean;
} & {
  type: "chat";
  user: {
    userId: string;
    nickname: string;
    uniqueId: string;
    secUid: string;
    avatarUrl?: string;
  };
  comment: string;
  emotes?: Array<{
      id: string;
      shortcode?: string;
      imageUrl?: string;
      index?: number;
      emoteType?: number;
      packageId?: string;
    }>;
};

export const ChatEventSchema = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ttl-live.dev/schema/public/chat-event.schema.json",
  "title": "ttl-live Chat Event",
  "x-typescript-name": "ChatEvent",
  "allOf": [
    {
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "$id": "https://ttl-live.dev/schema/public/base-event.schema.json",
      "title": "ttl-live Base Event",
      "x-typescript-name": "BaseEvent",
      "type": "object",
      "properties": {
        "method": {
          "type": "string"
        },
        "msgId": {
          "type": "string",
          "pattern": "^[0-9]+$"
        },
        "isHistory": {
          "type": "boolean"
        }
      },
      "required": [
        "method"
      ],
      "additionalProperties": true,
      "x-typescript-no-index": true
    },
    {
      "type": "object",
      "properties": {
        "type": {
          "const": "chat"
        },
        "user": {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "$id": "https://ttl-live.dev/schema/public/event-user.schema.json",
          "title": "ttl-live Event User",
          "x-typescript-name": "EventUser",
          "type": "object",
          "properties": {
            "userId": {
              "type": "string",
              "pattern": "^[0-9]+$"
            },
            "nickname": {
              "type": "string"
            },
            "uniqueId": {
              "type": "string"
            },
            "secUid": {
              "type": "string"
            },
            "avatarUrl": {
              "type": "string",
              "format": "uri"
            }
          },
          "required": [
            "userId",
            "nickname",
            "uniqueId",
            "secUid"
          ],
          "additionalProperties": false
        },
        "comment": {
          "type": "string"
        },
        "emotes": {
          "type": "array",
          "items": {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "$id": "https://ttl-live.dev/schema/public/live-emote.schema.json",
            "title": "ttl-live Live Emote",
            "x-typescript-name": "LiveEmote",
            "description": "A graphical emote carried by a LIVE comment or emote message. Optional fields are omitted when the wire did not carry them; they are never filled with a guessed default.",
            "type": "object",
            "properties": {
              "id": {
                "type": "string",
                "description": "The original emote identifier, verbatim."
              },
              "shortcode": {
                "type": "string",
                "description": "The textual shortcode (for example \"[laughcry]\"), only when reliably known."
              },
              "imageUrl": {
                "type": "string",
                "format": "uri",
                "description": "Artwork URL from the protobuf ImageModel."
              },
              "index": {
                "type": "integer",
                "description": "Raw EmoteWithIndex.index. Its unit is not confirmed, so it is preserved unmodified and never used to slice text."
              },
              "emoteType": {
                "type": "integer",
                "description": "Raw EmoteModel.emote_type enum value when non-zero."
              },
              "packageId": {
                "type": "string",
                "description": "Original package identifier for creator-specific or custom emotes."
              }
            },
            "required": [
              "id"
            ],
            "additionalProperties": false
          },
          "description": "Emotes embedded in the comment. Omitted (never required) so text-only chat stays compatible."
        }
      },
      "required": [
        "type",
        "user",
        "comment"
      ],
      "additionalProperties": true,
      "x-typescript-no-index": true
    }
  ]
} as const;
