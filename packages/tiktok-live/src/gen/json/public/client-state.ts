// Generated file. Do not edit manually.
// Source: schema/json/public/client-state.schema.json

export type ClientState = {
  uniqueId: string;
  roomId: string;
  connected: boolean;
  roomInfo: {
    roomId: string;
    title: string;
    status: number;
    viewers: number;
    likes: number;
    comments: number;
    shares: number;
    follows: number;
    coverUrl: string;
    shareUrl: string;
    owner: {
      userId: string;
      uniqueId: string;
      nickname: string;
      secUid: string;
      avatarUrl: string;
      followerCount: number;
    };
  } | null;
  giftCount: number;
};

export const ClientStateSchema = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ttl-live.dev/schema/public/client-state.schema.json",
  "title": "ttl-live Client State",
  "x-typescript-name": "ClientState",
  "type": "object",
  "properties": {
    "uniqueId": {
      "type": "string"
    },
    "roomId": {
      "type": "string",
      "pattern": "^[0-9]*$"
    },
    "connected": {
      "type": "boolean"
    },
    "roomInfo": {
      "anyOf": [
        {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "$id": "https://ttl-live.dev/schema/public/room-info.schema.json",
          "title": "ttl-live Room Info",
          "x-typescript-name": "RoomInfo",
          "type": "object",
          "properties": {
            "roomId": {
              "type": "string",
              "pattern": "^[0-9]+$"
            },
            "title": {
              "type": "string"
            },
            "status": {
              "type": "integer"
            },
            "viewers": {
              "type": "integer",
              "minimum": 0,
              "maximum": 9007199254740991
            },
            "likes": {
              "type": "integer",
              "minimum": 0,
              "maximum": 9007199254740991
            },
            "comments": {
              "type": "integer",
              "minimum": 0,
              "maximum": 9007199254740991
            },
            "shares": {
              "type": "integer",
              "minimum": 0,
              "maximum": 9007199254740991
            },
            "follows": {
              "type": "integer",
              "minimum": 0,
              "maximum": 9007199254740991
            },
            "coverUrl": {
              "type": "string",
              "format": "uri"
            },
            "shareUrl": {
              "type": "string",
              "format": "uri"
            },
            "owner": {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "$id": "https://ttl-live.dev/schema/public/room-owner.schema.json",
              "title": "ttl-live Room Owner",
              "x-typescript-name": "RoomOwner",
              "type": "object",
              "properties": {
                "userId": {
                  "type": "string",
                  "pattern": "^[0-9]*$"
                },
                "uniqueId": {
                  "type": "string"
                },
                "nickname": {
                  "type": "string"
                },
                "secUid": {
                  "type": "string"
                },
                "avatarUrl": {
                  "type": "string",
                  "format": "uri"
                },
                "followerCount": {
                  "type": "integer",
                  "minimum": 0,
                  "maximum": 9007199254740991
                }
              },
              "required": [
                "userId",
                "uniqueId",
                "nickname",
                "secUid",
                "avatarUrl",
                "followerCount"
              ],
              "additionalProperties": false
            }
          },
          "required": [
            "roomId",
            "title",
            "status",
            "viewers",
            "likes",
            "comments",
            "shares",
            "follows",
            "coverUrl",
            "shareUrl",
            "owner"
          ],
          "additionalProperties": false
        },
        {
          "type": "null"
        }
      ]
    },
    "giftCount": {
      "type": "integer",
      "minimum": 0,
      "maximum": 9007199254740991
    }
  },
  "required": [
    "uniqueId",
    "roomId",
    "connected",
    "roomInfo",
    "giftCount"
  ],
  "additionalProperties": false
} as const;
