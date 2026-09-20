import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root = fileURLToPath(new URL('.', import.meta.url));
const port = Number(process.env.PORT || 5173);
const types = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript' };
http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    const relative = url.pathname === '/' ? 'index.html' : decodeURIComponent(url.pathname).replace(/^\/+/, '');
    if (!['index.html', 'src/app.js', 'src/data.js', 'src/upload.js', 'src/styles.css'].includes(relative)) {
      res.writeHead(404); res.end('Not found'); return;
    }
    const file = path.resolve(root, relative);
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': types[path.extname(file)] + '; charset=utf-8', 'Cache-Control': 'no-cache' });
    res.end(body);
  } catch { res.writeHead(404); res.end('Not found'); }
}).listen(port, '127.0.0.1', () => console.log('Onfit: http://localhost:' + port));

