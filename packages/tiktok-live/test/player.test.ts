import assert from 'node:assert/strict';
import test from 'node:test';

import { socketConfig, socketQuery } from '../dist/player.js';

test('socket query serialization stays byte-compatible with the player shape', () => {
  const config = socketConfig({
    roomId: '7300000000000000001',
    deviceId: '7999000000000000001',
  });
  assert.equal(
    socketQuery(config, { version_code: '180800', device_platform: 'web' }),
    'version_code=180800&device_platform=web&app_name=tiktok_web&sup_ws_ds_opt=1&' +
      'update_version_code=2.0.0&compress=gzip&webcast_language=en&aid=1988&live_id=12&' +
      'version_code=270000&app_language=en&ws_direct=1&client_enter=1&' +
      'room_id=7300000000000000001&identity=audience&last_rtt=-1&heartbeat_duration=10000&' +
      'resp_content_type=protobuf&did_rule=0&device_id=7999000000000000001',
  );
});

test('typed route parameters remain serializable without unknown spreads', () => {
  const config = {
    ...socketConfig({ roomId: '7300000000000000001' }),
    routeParamsMap: {
      wssPushRoomId: '7300000000000000001',
      routeAttempt: 1n,
      unused: undefined,
    },
  };
  assert.match(
    socketQuery(config, { version_code: '180800', device_platform: 'web' }),
    /wss_push_room_id=7300000000000000001/,
  );
});
