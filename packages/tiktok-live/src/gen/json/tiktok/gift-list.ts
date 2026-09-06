// Generated file. Do not edit manually.
// Source: schema/json/tiktok/gift-list.schema.json

export type TikTokGiftListResponse = {
  status_code: number;
  data?: {
    message?: string;
    gifts?: Array<{
        id: string | number;
        name?: string;
        describe?: string;
        diamond_count?: number;
        combo?: boolean;
        type?: number;
        icon?: {
          url_list?: Array<string>;
          [key: string]: unknown;
        };
        [key: string]: unknown;
      }>;
    [key: string]: unknown;
  };
  [key: string]: unknown;
};

export const TikTokGiftListResponseSchema = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ttl-live.dev/schema/tiktok/gift-list.schema.json",
  "title": "TikTok Gift List Response",
  "x-typescript-name": "TikTokGiftListResponse",
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
        "gifts": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "id": {
                "anyOf": [
                  {
                    "type": "string",
                    "pattern": "^[0-9]+$",
                    "x-expected": "numeric string"
                  },
                  {
                    "type": "integer",
                    "minimum": 0,
                    "maximum": 9007199254740991
                  }
                ],
                "x-expected": "numeric string or safe integer"
              },
              "name": {
                "type": "string"
              },
              "describe": {
                "type": "string"
              },
              "diamond_count": {
                "type": "integer",
                "minimum": 0,
                "maximum": 9007199254740991,
                "x-expected": "safe non-negative integer"
              },
              "combo": {
                "type": "boolean"
              },
              "type": {
                "type": "integer",
                "minimum": 0,
                "maximum": 9007199254740991,
                "x-expected": "safe non-negative integer"
              },
              "icon": {
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
              }
            },
            "required": [
              "id"
            ],
            "additionalProperties": true
          }
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
