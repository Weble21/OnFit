// Production server for the built app (`npm run build` first). During development use `npm run dev` (Vite).
import http from 'node:http';
import { existsSync } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { brotliCompressSync, gzipSync } from 'node:zlib';
import path from 'node:path';
const dist = fileURLToPath(new URL('./dist', import.meta.url));
const port = Number(process.env.PORT || 5173);
const host = process.env.HOST || '127.0.0.1';
const backend = new URL(process.env.BACKEND_URL || 'http://127.0.0.1:8080');
const types = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2',
};
if (!existsSync(path.join(dist, 'index.html'))) {
  console.error('dist/ is missing. Run "npm run build" first, or use "npm run dev" for development.');
  process.exit(1);
}
// Brotli at its default quality costs tens of milliseconds per file, so reuse each encoding until the file changes.
const cache = new Map();
async function encoded(file, encoding) {
  const { mtimeMs } = await stat(file);
  const key = file + ':' + encoding;
  const hit = cache.get(key);
  if (hit?.mtimeMs === mtimeMs) return hit.body;
  const raw = await readFile(file);
  const body = encoding === 'br' ? brotliCompressSync(raw) : encoding === 'gzip' ? gzipSync(raw) : raw;
  cache.set(key, { mtimeMs, body });
  return body;
}
http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname.startsWith('/api/')) {
      const target = new URL(url.pathname + url.search, backend);
      const upstream = http.request(target, { method: req.method, headers: { ...req.headers, host: target.host } }, response => {
        res.writeHead(response.statusCode, response.headers);
        response.pipe(res);
      });
      upstream.on('error', () => { if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' }); res.end('{"error":"Backend unavailable"}'); });
      req.pipe(upstream);
      return;
    }
    const relative = url.pathname === '/' ? 'index.html' : decodeURIComponent(url.pathname).replace(/^\/+/, '');
    const file = path.resolve(dist, relative);
    // Only files inside dist/ with a known type are served.
    const type = types[path.extname(file)];
    if (!file.startsWith(dist + path.sep) || !type || !(await stat(file).catch(() => null))?.isFile()) {
      res.writeHead(404); res.end('Not found'); return;
    }
    // Vite puts a content hash in every file name under assets/, so those never change in place.
    const cacheControl = relative.startsWith('assets/') ? 'public, max-age=31536000, immutable' : 'no-cache';
    const headers = { 'Content-Type': type, 'Cache-Control': cacheControl, 'Vary': 'Accept-Encoding' };
    const accepted = req.headers['accept-encoding'] || '';
    const compressible = type.startsWith('text/') || type === 'image/svg+xml' || type === 'application/json';
    const encoding = !compressible ? null : /\bbr\b/.test(accepted) ? 'br' : /\bgzip\b/.test(accepted) ? 'gzip' : null;
    if (encoding) headers['Content-Encoding'] = encoding;
    const body = await encoded(file, encoding);
    res.writeHead(200, headers);
    res.end(body);
  } catch { if (!res.headersSent) res.writeHead(404); res.end('Not found'); }
}).listen(port, host, () => console.log('Onfit: http://localhost:' + port));
