// Generated file. Do not edit manually.
// Source: schema/json/tiktok/room-lookup.schema.json

export type TikTokRoomLookupResponse = {
  data?: {
    user?: {
      uniqueId?: string;
      roomId?: string;
      nickname?: string;
      status?: number;
      [key: string]: unknown;
    };
    liveRoom?: {
      status?: number;
      title?: string;
      [key: string]: unknown;
    };
    [key: string]: unknown;
  };
  [key: string]: unknown;
};

export const TikTokRoomLookupResponseSchema = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ttl-live.dev/schema/tiktok/room-lookup.schema.json",
  "title": "TikTok Room Lookup Response",
  "x-typescript-name": "TikTokRoomLookupResponse",
  "type": "object",
  "properties": {
    "data": {
      "type": "object",
      "properties": {
        "user": {
          "type": "object",
          "properties": {
            "uniqueId": {
              "type": "string"
            },
            "roomId": {
              "type": "string",
              "pattern": "^[0-9]*$",
              "x-expected": "numeric string"
            },
            "nickname": {
              "type": "string"
            },
            "status": {
              "type": "integer"
            }
          },
          "additionalProperties": true
        },
        "liveRoom": {
          "type": "object",
          "properties": {
            "status": {
              "type": "integer"
            },
            "title": {
              "type": "string"
            }
          },
          "additionalProperties": true
        }
      },
      "additionalProperties": true
    }
  },
  "additionalProperties": true
} as const;
