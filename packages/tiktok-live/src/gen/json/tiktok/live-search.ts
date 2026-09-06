// Generated file. Do not edit manually.
// Source: schema/json/tiktok/live-search.schema.json

export type TikTokLiveSearchResponse = {
  status_code?: number;
  status_msg?: string;
  data?: Array<{
      live_info?: {
        raw_data?: string;
        [key: string]: unknown;
      };
      [key: string]: unknown;
    }>;
  [key: string]: unknown;
};

export const TikTokLiveSearchResponseSchema = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ttl-live.dev/schema/tiktok/live-search.schema.json",
  "title": "TikTok Live Search Response",
  "x-typescript-name": "TikTokLiveSearchResponse",
  "type": "object",
  "properties": {
    "status_code": {
      "type": "integer"
    },
    "status_msg": {
      "type": "string"
    },
    "data": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "live_info": {
            "type": "object",
            "properties": {
              "raw_data": {
                "type": "string"
              }
            },
            "additionalProperties": true
          }
        },
        "additionalProperties": true
      }
    }
  },
  "additionalProperties": true
} as const;
