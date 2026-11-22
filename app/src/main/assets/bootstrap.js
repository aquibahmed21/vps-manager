// Entry script run by the embedded Node. argv: <repoDir> <nodeModulesDir> <logFile>
const path = require('path');
const fs = require('fs');
const util = require('util');
const Module = require('module');

const [, , repoDir, modulesDir, logFile, llmConf] = process.argv;
const out = fs.createWriteStream(logFile, { flags: 'a' });
for (const level of ['log', 'info', 'warn', 'error']) {
  console[level] = (...a) =>
    out.write(`[${new Date().toISOString()}] ${level}: ${a.map((x) => (typeof x === 'string' ? x : util.inspect(x))).join(' ')}\n`);
}
process.on('uncaughtException', (e) => console.error('uncaught', (e && e.stack) || e));
process.on('unhandledRejection', (e) => console.error('unhandled', (e && e.stack) || e));


// ---- LLM gateway: requests under /v1 are forwarded to the local llama-server, with guard rails so one client can't overwhelm the phone. ----
const http = require('http');
const origEmit = http.Server.prototype.emit;
const LIMITS = { queue: 4, queueWaitMs: 60000, deadlineMs: 95000, streamDeadlineMs: 300000, maxTokens: 1024, imageBytes: 8 * 1024 * 1024 };
function llmConfig() { try { return JSON.parse(fs.readFileSync(llmConf, 'utf8')); } catch (e) { return null; } }
const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization, x-api-key', 'Access-Control-Max-Age': '86400' };
function reply(res, code, msg, extra) {
  if (res.headersSent) { res.end(); return; }
  res.writeHead(code, Object.assign({ 'Content-Type': 'application/json' }, CORS, extra || {}));
  res.end(JSON.stringify({ error: { message: msg } }));
}
// One generation at a time (llama-server runs with a single slot); the rest wait in a short queue.
let busy = false;
const waiters = [];
function acquire(isGone) {
  return new Promise((resolve, reject) => {
    if (!busy) { busy = true; return resolve(); }
    if (waiters.length >= LIMITS.queue) return reject(new Error('full'));
    const w = { resolve, reject, timer: setTimeout(() => { waiters.splice(waiters.indexOf(w), 1); reject(new Error('timeout')); }, LIMITS.queueWaitMs) };
    w.cancel = () => { clearTimeout(w.timer); const k = waiters.indexOf(w); if (k >= 0) waiters.splice(k, 1); };
    waiters.push(w);
  });
}
function release() {
  const w = waiters.shift();
  if (w) { clearTimeout(w.timer); w.resolve(); } else busy = false;
}
function readBody(req, maxBytes) {
  return new Promise((resolve, reject) => {
    const chunks = []; let n = 0;
    req.on('data', (c) => { n += c.length; if (n > maxBytes) { reject(new Error('too_big')); req.pause(); } else chunks.push(c); });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}
function proxy(req, res, cfg, body, deadlineMs, done) {
  let finished = false;
  const finish = () => { if (!finished) { finished = true; clearTimeout(timer); done(); } };
  const headers = Object.assign({}, req.headers, { host: '127.0.0.1:' + cfg.port });
  delete headers['transfer-encoding'];
  if (body) headers['content-length'] = Buffer.byteLength(body);
  const up = http.request({ host: '127.0.0.1', port: cfg.port, path: req.url, method: req.method, headers }, (r) => {
    const h = {}; for (const k of Object.keys(r.headers)) if (!k.toLowerCase().startsWith('access-control-')) h[k] = r.headers[k]; // one CORS policy, ours
    res.writeHead(r.statusCode, Object.assign(h, CORS));
    r.pipe(res);
    r.on('end', finish);
  });
  // Hard deadline: abort the model (closing the connection cancels generation) instead of letting it run for ever.
  const timer = setTimeout(() => { up.destroy(); reply(res, 504, 'Generation took too long and was cancelled. Use a shorter prompt or a smaller max_tokens.'); finish(); }, deadlineMs);
  up.on('error', (e) => { reply(res, 502, 'LLM unavailable: ' + e.message); finish(); });
  res.on('close', () => { up.destroy(); finish(); }); // client went away: stop the work
  up.end(body);
}
// ---- traffic counters, read by the app every 30 s (see /__vps_metrics) ----
const M = { req: 0, ai: 0, manage: 0, other: 0, bytesIn: 0, bytesOut: 0, denied: 0, errors: 0 };
function isInternal(req) { // our own health/metric pings come from loopback without Cloudflare headers
  const a = req.socket && req.socket.remoteAddress || '';
  return (a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1') && !req.headers['cf-ray'] && !req.headers['cf-connecting-ip'];
}
function countRequest(req, res) {
  M.req++; const u = req.url || '';
  if (u.startsWith('/v1/')) M.ai++; else if (u.startsWith('/manage/')) M.manage++; else M.other++;
  M.bytesIn += Number(req.headers['content-length']) || 0;
  const len = (c, e) => (c && typeof c !== 'function') ? (typeof c === 'string' ? Buffer.byteLength(c, typeof e === 'string' ? e : 'utf8') : (c.length || 0)) : 0;
  const ow = res.write, oe = res.end;
  res.write = function (c, e) { M.bytesOut += len(c, e); return ow.apply(this, arguments); };
  res.end = function (c, e) { M.bytesOut += len(c, e); return oe.apply(this, arguments); };
  // 503 = model busy or not started, which is not a server failure
  res.on('finish', () => { if (res.statusCode >= 500 && res.statusCode !== 503) M.errors++; else if (res.statusCode === 401 || res.statusCode === 403 || res.statusCode === 429) M.denied++; });
}
http.Server.prototype.emit = function (ev, req, res) {
  if (ev === 'request' && req.url && req.url.split('?')[0] === '/__vps_metrics') {
    if (!isInternal(req)) { res.statusCode = 404; res.end(); return true; }
    res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(M)); return true;
  }
  if (ev === 'request' && req.url && !isInternal(req)) countRequest(req, res);
  if (ev === 'request' && req.url) {
    // Browser apps on other sites (GitHub Pages, localhost) may call any route, so every response allows cross-origin use.
    for (const k of Object.keys(CORS)) res.setHeader(k, CORS[k]);
    // The preflight a browser sends first must succeed without a key or a body.
    if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return true; }
  }
  // Remote management (VPS Monitor app): forwarded untouched to the app's loopback API, which checks the admin token itself.
  if (ev === 'request' && req.url && req.url.startsWith('/manage/')) {
    const up = http.request({ host: '127.0.0.1', port: Number(process.env.MANAGE_PORT) || 3001, path: req.url, method: req.method,
      headers: Object.assign({}, req.headers, { host: '127.0.0.1' }) }, (r) => {
      const h = {}; for (const k of Object.keys(r.headers)) if (!k.toLowerCase().startsWith('access-control-')) h[k] = r.headers[k];
      res.writeHead(r.statusCode, Object.assign(h, CORS)); r.pipe(res);
    });
    up.setTimeout(600000, () => up.destroy());
    up.on('error', (e) => reply(res, 502, 'Management service unavailable: ' + e.message));
    res.on('close', () => up.destroy());
    req.pipe(up);
    return true;
  }
  if (ev !== 'request' || !req.url || !req.url.startsWith('/v1/')) return origEmit.apply(this, arguments);
  const cfg = llmConfig();
  const auth = req.headers['authorization'] || '';
  const given = auth.startsWith('Bearer ') ? auth.slice(7) : req.headers['x-api-key'];
  if (!cfg) { reply(res, 503, 'LLM is not configured.'); return true; }
  // No login system yet: the key is only enforced when the app's "Require API key" switch is on.
  if (cfg.requireKey && (!cfg.key || given !== cfg.key)) { reply(res, 401, 'Invalid or missing API key'); return true; }
  if (!cfg.ready) { reply(res, 503, 'LLM is not running. Start it in the VPS Host app.', { 'Retry-After': '10' }); return true; }

  // Reads (GET /v1/models etc.) pass straight through. Every POST can make the model work, so it is gated.
  if (req.method === 'GET' || req.method === 'HEAD') { proxy(req, res, cfg, null, 30000, () => {}); return true; }

  // Generation request: cap size, clamp output, then wait for the single model slot.
  const textMax = Math.max(16384, (cfg.ctx || 4096) * 8); // ~2x what could ever fit, so honest prompts pass
  readBody(req, LIMITS.imageBytes).then(async (raw) => {
    // Images make bodies large; text-only requests must stay small.
    if (raw.length > textMax && raw.indexOf('image_url') < 0) throw new Error('too_big');
    let json;
    try { json = JSON.parse(raw.toString('utf8')); } catch (e) { return reply(res, 400, 'Body must be valid JSON.'); }
    const cap = Math.min(LIMITS.maxTokens, Math.floor((cfg.ctx || 4096) / 2));
    for (const k of ['max_tokens', 'max_completion_tokens', 'n_predict']) if (json[k] != null) json[k] = Math.min(Math.max(1, Number(json[k]) || cap), cap);
    if (json.max_tokens == null && json.max_completion_tokens == null && json.n_predict == null) json.max_tokens = cap;
    delete json.ignore_eos; // would defeat the model's natural stop
    const body = Buffer.from(JSON.stringify(json));
    let gone = false; res.on('close', () => { gone = true; });
    try { await acquire(); } catch (e) {
      return reply(res, 503, e.message === 'full' ? 'The model is busy and its queue is full. Try again shortly.' : 'Waited too long for the model. Try again shortly.', { 'Retry-After': '10' });
    }
    if (gone) { release(); return; } // client left while queued
    proxy(req, res, cfg, body, json.stream ? LIMITS.streamDeadlineMs : LIMITS.deadlineMs, release);
  }).catch((e) => {
    if (e.message === 'too_big') reply(res, 413, 'Request is too large (text above the model context of ' + (cfg.ctx || 4096) + ' tokens).');
    else reply(res, 400, 'Bad request: ' + e.message);
  });
  return true;
};

process.env.NODE_PATH = modulesDir;
Module._initPaths();
process.chdir(repoDir);
const pkg = JSON.parse(fs.readFileSync(path.join(repoDir, 'package.json'), 'utf8'));
console.log('bootstrap: starting', pkg.name, 'main=', pkg.main || 'server.js');
require(path.resolve(repoDir, pkg.main || 'server.js'));
