import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
for (const [script, args] of [
  ['node_modules/typescript/bin/tsc', ['--noEmit', '--noUnusedLocals', '--noUnusedParameters']],
  ['node_modules/vite/bin/vite.js', ['build']],
]) {
  const result = spawnSync(process.execPath, [script, ...args], {
    cwd: root, stdio: 'inherit', env: { ...process.env, VITE_CAMERA_EMBEDDED: 'true' },
  });
  if (result.status !== 0) process.exit(result.status ?? 1);
}
const assets = fileURLToPath(new URL('../../../Camera/app/src/main/assets/', import.meta.url));
mkdirSync(assets, { recursive: true });
writeFileSync(`${assets}/index.html.gzip`, gzipSync(readFileSync(`${root}/dist/index.html`), { level: 9 }));
console.log('Embedded camera UI updated.');
