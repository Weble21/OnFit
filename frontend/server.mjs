<<<<<<< HEAD
﻿import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
=======
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { brotliCompressSync, gzipSync } from 'node:zlib';
>>>>>>> c926473 (feat.backend)
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
<<<<<<< HEAD
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': types[path.extname(file)] + '; charset=utf-8', 'Cache-Control': 'no-cache' });
    res.end(body);
  } catch { res.writeHead(404); res.end('Not found'); }
}).listen(port, '127.0.0.1', () => console.log('Onfit: http://localhost:' + port));

=======
    let body = await readFile(file);
    const headers = { 'Content-Type': types[path.extname(file)] + '; charset=utf-8', 'Cache-Control': 'no-cache', 'Vary': 'Accept-Encoding' };
    const accepted = req.headers['accept-encoding'] || '';
    if (/\bbr\b/.test(accepted)) { body = brotliCompressSync(body); headers['Content-Encoding'] = 'br'; }
    else if (/\bgzip\b/.test(accepted)) { body = gzipSync(body); headers['Content-Encoding'] = 'gzip'; }
    res.writeHead(200, headers);
    res.end(body);
  } catch { res.writeHead(404); res.end('Not found'); }
}).listen(port, '127.0.0.1', () => console.log('Onfit: http://localhost:' + port));
>>>>>>> c926473 (feat.backend)
