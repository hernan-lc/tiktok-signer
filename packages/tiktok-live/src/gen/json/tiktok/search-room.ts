// Generated file. Do not edit manually.
// Source: schema/json/tiktok/search-room.schema.json

export type TikTokSearchRoom = {
  status?: number;
  id_str?: string;
  title?: string;
  user_count?: number;
  owner?: {
    display_id?: string;
    nickname?: string;
    [key: string]: unknown;
  };
  [key: string]: unknown;
};

export const TikTokSearchRoomSchema = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ttl-live.dev/schema/tiktok/search-room.schema.json",
  "title": "TikTok Live Search Room",
  "x-typescript-name": "TikTokSearchRoom",
  "type": "object",
  "properties": {
    "status": {
      "type": "integer"
    },
    "id_str": {
      "type": "string",
      "pattern": "^[0-9]+$",
      "x-expected": "numeric string"
    },
    "title": {
      "type": "string"
    },
    "user_count": {
      "type": "integer",
      "minimum": 0,
      "maximum": 9007199254740991,
      "x-expected": "safe non-negative integer"
    },
    "owner": {
      "type": "object",
      "properties": {
        "display_id": {
          "type": "string"
        },
        "nickname": {
          "type": "string"
        }
      },
      "additionalProperties": true
    }
  },
  "additionalProperties": true
} as const;
