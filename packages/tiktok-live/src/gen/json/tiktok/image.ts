// Generated file. Do not edit manually.
// Source: schema/json/tiktok/image.schema.json

export type TikTokImage = {
  url_list?: Array<string>;
  [key: string]: unknown;
};

export const TikTokImageSchema = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ttl-live.dev/schema/tiktok/image.schema.json",
  "title": "TikTok Image",
  "x-typescript-name": "TikTokImage",
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
} as const;
