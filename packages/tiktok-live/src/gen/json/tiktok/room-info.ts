// Generated file. Do not edit manually.
// Source: schema/json/tiktok/room-info.schema.json

export type TikTokRoomInfoResponse = {
  status_code: number;
  data?: {
    message?: string;
    id_str?: string;
    title?: string;
    status?: number;
    user_count?: number;
    cover?: {
      url_list?: Array<string>;
      [key: string]: unknown;
    };
    share_url?: string;
    owner?: {
      id_str?: string;
      display_id?: string;
      nickname?: string;
      sec_uid?: string;
      avatar_thumb?: {
        url_list?: Array<string>;
        [key: string]: unknown;
      };
      follow_info?: {
        follower_count?: number;
        [key: string]: unknown;
      };
      [key: string]: unknown;
    };
    stats?: {
      total_user?: number;
      like_count?: number;
      comment_count?: number;
      share_count?: number;
      follow_count?: number;
      [key: string]: unknown;
    };
    [key: string]: unknown;
  };
  [key: string]: unknown;
};

export const TikTokRoomInfoResponseSchema = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ttl-live.dev/schema/tiktok/room-info.schema.json",
  "title": "TikTok Room Info Response",
  "x-typescript-name": "TikTokRoomInfoResponse",
  "type": "object",
  "properties": {
    "status_code": {
      "type": "integer"
    },
    "data": {
      "type": "object",
      "properties": {
        "message": {
          "type": "string"
        },
        "id_str": {
          "type": "string",
          "pattern": "^[0-9]+$",
          "x-expected": "numeric string"
        },
        "title": {
          "type": "string"
        },
        "status": {
          "type": "integer"
        },
        "user_count": {
          "type": "integer",
          "minimum": 0,
          "maximum": 9007199254740991,
          "x-expected": "safe non-negative integer"
        },
        "cover": {
          "type": "object",
          "properties": {
            "url_list": {
              "type": "array",
              "items": {
                "type": "string",
                "format": "uri"
              }
            }
          },
          "additionalProperties": true
        },
        "share_url": {
          "type": "string"
        },
        "owner": {
          "type": "object",
          "properties": {
            "id_str": {
              "type": "string",
              "pattern": "^[0-9]+$",
              "x-expected": "numeric string"
            },
            "display_id": {
              "type": "string"
            },
            "nickname": {
              "type": "string"
            },
            "sec_uid": {
              "type": "string"
            },
            "avatar_thumb": {
              "type": "object",
              "properties": {
                "url_list": {
                  "type": "array",
                  "items": {
                    "type": "string",
                    "format": "uri"
                  }
                }
              },
              "additionalProperties": true
            },
            "follow_info": {
              "type": "object",
              "properties": {
                "follower_count": {
                  "type": "integer",
                  "minimum": 0,
                  "maximum": 9007199254740991,
                  "x-expected": "safe non-negative integer"
                }
              },
              "additionalProperties": true
            }
          },
          "additionalProperties": true
        },
        "stats": {
          "type": "object",
          "properties": {
            "total_user": {
              "type": "integer",
              "minimum": 0,
              "maximum": 9007199254740991,
              "x-expected": "safe non-negative integer"
            },
            "like_count": {
              "type": "integer",
              "minimum": 0,
              "maximum": 9007199254740991,
              "x-expected": "safe non-negative integer"
            },
            "comment_count": {
              "type": "integer",
              "minimum": 0,
              "maximum": 9007199254740991,
              "x-expected": "safe non-negative integer"
            },
            "share_count": {
              "type": "integer",
              "minimum": 0,
              "maximum": 9007199254740991,
              "x-expected": "safe non-negative integer"
            },
            "follow_count": {
              "type": "integer",
              "minimum": 0,
              "maximum": 9007199254740991,
              "x-expected": "safe non-negative integer"
            }
          },
          "additionalProperties": true
        }
      },
      "additionalProperties": true
    }
  },
  "required": [
    "status_code"
  ],
  "additionalProperties": true
} as const;
