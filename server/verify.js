/**
 * 取り付けたあとの動作確認。
 *
 *   node verify.js <JWT> [http://localhost:3000]
 *
 * 実サーバーに対して読み書きします。**テスト用の uid と deviceId しか触りません**が、
 * `dopachiru_entities` に `verify-*` の行と `dopachiru_usage` に
 * `verify-device` の行が残ります。気になれば最後に消してください:
 *
 *   DELETE FROM dopachiru_entities WHERE uid LIKE 'verify-%';
 *   DELETE FROM dopachiru_usage WHERE device_id LIKE 'verify-%';
 */

const token = process.argv[2];
const base = (process.argv[3] || 'http://localhost:3000') + '/dopachiru';

if (!token) {
  console.error('使い方: node verify.js <JWT> [ベースURL]');
  process.exit(2);
}

const H = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token };
const post = (path, body) =>
  fetch(base + path, { method: 'POST', headers: H, body: JSON.stringify(body) });

let failures = 0;
function check(label, ok, extra) {
  console.log((ok ? '  OK   ' : '  NG   ') + label + (ok ? '' : '  ← ' + JSON.stringify(extra)));
  if (!ok) failures++;
}

const stamp = Date.now();
const uid = 'verify-' + stamp;

(async () => {
  console.log('=== 認証 ===');
  const noAuth = await fetch(base + '/ping');
  check('トークン無しは 401', noAuth.status === 401, noAuth.status);

  const ping = await (await fetch(base + '/ping', { headers: H })).json();
  check('ping が rev を返す', ping.ok === true && typeof ping.rev === 'number', ping);

  console.log('\n=== 送って、別の端末から受け取れるか ===');
  const sent = await (await post('/sync', {
    deviceId: 'verify-device-a',
    since: ping.rev,
    changes: {
      rules: [{ uid, updatedAt: stamp, deleted: false, payload: { name: '確認用' } }],
    },
  })).json();
  check('自分が送ったものは返ってこない',
    !sent.changes.rules.some(r => r.uid === uid), sent.changes.rules);

  const got = await (await post('/sync', {
    deviceId: 'verify-device-b', since: ping.rev, changes: {},
  })).json();
  const mine = got.changes.rules.find(r => r.uid === uid);
  check('別の端末には届く', !!mine, got.changes.rules);
  check('中身がそのまま', mine && mine.payload.name === '確認用', mine);

  console.log('\n=== 衝突 ===');
  await post('/sync', {
    deviceId: 'verify-device-b', since: got.rev,
    changes: { rules: [{ uid, updatedAt: stamp - 1000, deleted: false, payload: { name: '古い' } }] },
  });
  const afterOld = await (await post('/sync', {
    deviceId: 'verify-device-c', since: ping.rev, changes: {},
  })).json();
  check('古い updatedAt は無視される',
    afterOld.changes.rules.find(r => r.uid === uid)?.payload.name === '確認用',
    afterOld.changes.rules.find(r => r.uid === uid));

  console.log('\n=== 使用実績が端末ごとに分かれるか ===');
  const today = new Date().toISOString().slice(0, 10);
  await post('/usage', { deviceId: 'verify-device-a', days: [{ date: today, totalMinutes: 30 }] });
  await post('/usage', { deviceId: 'verify-device-b', days: [{ date: today, totalMinutes: 20 }] });
  const usage = await (await fetch(`${base}/usage?from=${today}&to=${today}`, { headers: H })).json();
  const byDevice = (usage.days.find(d => d.date === today) || {}).byDevice || {};
  check('両方の端末が残る',
    byDevice['verify-device-a']?.totalMinutes === 30 &&
    byDevice['verify-device-b']?.totalMinutes === 20, byDevice);

  console.log('\n' + (failures === 0 ? '全部OK。取り付け成功です。' : failures + ' 件 NG'));
  process.exit(failures === 0 ? 0 : 1);
})().catch(err => {
  console.error('\n繋がりませんでした:', err.message);
  process.exit(1);
});
