// Run against an already installed isolated test build. Each instrumentation call starts a process;
// force-stop in between ensures this exercises persisted workers, not only Activity recreation.
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const sdk = process.env.ANDROID_HOME || readFileSync(path.join(root, 'local.properties'), 'utf8')
  .match(/^sdk\.dir=(.+)$/m)?.[1]?.trim();
if (!sdk) throw new Error('Set ANDROID_HOME or sdk.dir in local.properties');
const adb = path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
const serial = process.argv[2] || process.env.ANDROID_SERIAL;
if (!serial) throw new Error('Usage: node scripts/test-service-worker-restart.mjs <device> [test-package]');
const target = process.argv[3] || 'app.xvi.web.verify';
if (!/^app\.xvi\.web\.verify[\w.]*$/.test(target)) throw new Error('Use an isolated .verify build');
function run(args) {
  const result = spawnSync(adb, ['-s', serial, ...args], { encoding: 'utf8', timeout: 90000 });
  if (result.error) throw result.error;
  const output = result.stdout + result.stderr;
  process.stdout.write(output);
  if (result.status !== 0) throw new Error(`adb exited with ${result.status}`);
  return output;
}
for (const phase of ['seed', 'probe']) {
  run(['shell', 'am', 'force-stop', target]);
  const output = run(['shell', 'am', 'instrument', '-w', '-e', 'workerPhase', phase, '-e', 'class',
    'app.xvi.web.ServiceWorkerRestartTest', `${target}.test/androidx.test.runner.AndroidJUnitRunner`]);
  if (!/OK \(1 test\)/.test(output) || /FAILURES!!!|INSTRUMENTATION_FAILED/.test(output)) {
    throw new Error(`Worker restart ${phase} phase failed`);
  }
}
