import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const gradlew = path.join(root, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
const apkDir = path.join(root, 'app/build/outputs/apk/release');

try {
  const result = spawnSync(gradlew, [':app:assembleRelease', '-PwebAbis=arm64-v8a,armeabi-v7a,x86,x86_64'], {
    cwd: root,
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);

  const metadata = JSON.parse(readFileSync(path.join(apkDir, 'output-metadata.json'), 'utf8'));
  const [output] = metadata.elements;
  if (metadata.applicationId !== 'app.xvi.web' || metadata.elements.length !== 1 ||
      !/^\d+\.\d+\.\d+$/.test(output.versionName)) {
    throw new Error('Unexpected release APK metadata');
  }
  const outputDir = path.join(root, 'app/build/outputs/releases', output.versionName);
  mkdirSync(outputDir, { recursive: true });
  const name = `app.xvi.web-${output.versionName}-universal.apk`;
  const apk = readFileSync(path.join(apkDir, output.outputFile));
  writeFileSync(path.join(outputDir, name), apk);
  writeFileSync(path.join(outputDir, 'SHA256SUMS'), `${createHash('sha256').update(apk).digest('hex')}  ${name}\n`);
  process.stdout.write(`\n${name}: ${apk.length} bytes\nRelease files: ${outputDir}\n`);
} catch (error) {
  process.stderr.write(`${error.message}\n`);
  process.exit(1);
}
