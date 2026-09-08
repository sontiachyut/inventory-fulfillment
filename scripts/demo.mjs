import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const child = spawn('java', ['-jar', 'target/inventory-fulfillment-0.1.0-SNAPSHOT.jar',
  '--spring.profiles.active=local-demo', '--server.port=0'], { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] });
let output = '';
let port;
let launchError;
let exited = false;
const ended = new Promise(resolve => {
  child.once('exit', () => { exited = true; resolve(); });
  child.once('error', error => { launchError = error; exited = true; resolve(); });
});
for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => {
  output = (output + chunk.toString()).slice(-8000);
  port ??= output.match(/Tomcat started on port (\d+)/)?.[1];
});
const stop = () => child.kill('SIGTERM');
process.once('SIGINT', stop);
process.once('SIGTERM', stop);

async function request(method, path, body, expected = 200) {
  const response = await fetch('http://127.0.0.1:' + port + path, {
    method, headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(5000)
  });
  const data = await response.json();
  assert.equal(response.status, expected, JSON.stringify(data));
  return data;
}

try {
  for (let attempt = 0; !port && attempt < 300 && !exited; attempt++) await delay(100);
  if (launchError) throw launchError;
  if (!port || exited) throw new Error('Demo server did not start. Run ./mvnw verify first.\n' + output);
  await request('GET', '/actuator/health');

  await request('POST', '/api/v1/stock', { tenantId: 'demo', sku: 'headphones', quantity: 2 });
  const intent = { tenantId: 'demo', sku: 'headphones', quantity: 1, ttlSeconds: 60, idempotencyKey: 'request-1' };
  const first = await request('POST', '/api/v1/reservations', intent);
  const replay = await request('POST', '/api/v1/reservations', intent);
  assert.equal(first.reservationId, replay.reservationId);
  console.log('PASS: retrying a reservation does not allocate stock twice.');
  await request('POST', '/api/v1/reservations', { ...intent, quantity: 2, idempotencyKey: 'too-many' }, 409);
  console.log('PASS: insufficient stock is rejected.');
  const path = '/api/v1/reservations/demo/' + first.reservationId;
  await request('POST', path + '/confirm');
  await request('POST', path + '/confirm');
  await request('POST', path + '/cancel', undefined, 409);
  const stock = await request('GET', '/api/v1/stock/demo/headphones');
  assert.deepEqual([stock.available, stock.reserved, stock.sold], [1, 0, 1]);
  console.log('PASS: confirmation retry sells once; terminal cancellation is rejected.');
  const second = await request('POST', '/api/v1/reservations', { ...intent, idempotencyKey: 'request-2' });
  await request('POST', '/api/v1/reservations/demo/' + second.reservationId + '/cancel');
  assert.equal((await request('GET', '/api/v1/stock/demo/headphones')).available, 1);
  console.log('PASS: cancellation releases its reservation without changing sold stock.');

  console.log('Local, volatile reference demo complete. No distributed-scale claim.');
} finally {
  process.removeListener('SIGINT', stop);
  process.removeListener('SIGTERM', stop);
  if (!exited) {
    child.kill('SIGTERM');
    const timer = setTimeout(() => child.kill('SIGKILL'), 5000);
    await ended;
    clearTimeout(timer);
  }
}
