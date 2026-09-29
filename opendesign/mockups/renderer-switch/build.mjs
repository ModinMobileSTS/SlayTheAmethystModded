import { readFile, readdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const directory = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(directory, '../../..');
const tools = path.join(root, 'agent-tmp/renderer-card-tools');
const require = createRequire(path.join(tools, 'package.json'));
const { build } = require('esbuild');
const result = await build({
  entryPoints: [path.join(directory, 'prototype.jsx')],
  bundle: true,
  write: false,
  minify: true,
  format: 'iife',
  target: ['es2020'],
  define: { 'process.env.NODE_ENV': '"production"' },
  nodePaths: [path.join(tools, 'node_modules')],
  loader: { '.png': 'dataurl' },
  legalComments: 'inline',
});
const css = await readFile(path.join(directory, 'prototype.css'), 'utf8');
const js = result.outputFiles[0].text.replaceAll('</script', '<\\/script');
await writeFile(path.join(directory, 'index.html'), `<!DOCTYPE html>
<html lang="zh-CN" data-theme-mode="light">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
  <meta name="color-scheme" content="light dark">
  <title>图形渲染 · 游戏页原型</title>
  <style>${css}</style>
</head>
<body>
  <div id="root"></div>
  <noscript>请启用 JavaScript 以查看交互原型。</noscript>
  <script>${js}</script>
</body>
</html>
`);
console.log('Built self-contained opendesign/mockups/renderer-switch/index.html');

async function scanFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) files.push(...await scanFiles(entryPath));
    else if (entry.isFile()) files.push(entryPath);
  }
  return files.sort();
}

const designRoot = path.join(root, 'opendesign');
const sections = [];
for (const [id, label] of [['mockups', 'Mockups'], ['design-systems', 'Design Systems']]) {
  const grouped = new Map();
  for (const file of await scanFiles(path.join(designRoot, id))) {
    if (id === 'mockups' && !file.endsWith('.html')) continue;
    const relative = path.relative(designRoot, file).split(path.sep).join('/');
    const slug = relative.split('/')[1];
    if (!grouped.has(slug)) grouped.set(slug, []);
    grouped.get(slug).push({ label: path.basename(file), path: relative });
  }
  sections.push({ id, label, groups: [...grouped].map(([slug, files]) => ({ slug, files })) });
}
await writeFile(path.join(designRoot, 'manifest.json'), JSON.stringify({ generated: new Date().toISOString(), sections }, null, 2) + '\n');
console.log('Rebuilt manifest from all mockup HTML and design-system files');
