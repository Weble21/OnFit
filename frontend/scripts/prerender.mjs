// Runs after `vite build`: puts the prerendered landing page into dist/index.html.
// React replaces this markup with identical live markup once main.jsx runs.
import { readFile, rm, writeFile } from 'node:fs/promises';

const ssrDir = new URL('../dist-ssr/', import.meta.url);
const indexFile = new URL('../dist/index.html', import.meta.url);
const marker = '<div id="app"></div>';

const { render } = await import(new URL('prerender.js', ssrDir));
const html = await readFile(indexFile, 'utf8');
if (!html.includes(marker)) throw new Error('dist/index.html has no empty ' + marker);
// The wrapper lets index.html hide this landing markup when the URL opens another screen.
await writeFile(indexFile, html.replace(marker, '<div id="app"><div class="prerendered">' + render() + '</div></div>'));
await rm(ssrDir, { recursive: true, force: true });
console.log('Prerendered the landing page into dist/index.html');
