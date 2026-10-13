// Runs Gradle with `--device <serial>` turned into ANDROID_SERIAL, which is how the install tasks
// pick a device; Gradle itself rejects the option. Every other argument goes to Gradle unchanged.
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const env = { ...process.env };
const args = [];
const input = process.argv.slice(2);
for (let i = 0; i < input.length; i++) {
  const arg = input[i];
  if (arg === '--device' || arg === '-s') {
    const serial = input[++i];
    if (!serial) {
      console.error(`${arg} needs a device serial, such as 192.168.31.254:37465 (see adb devices)`);
      process.exit(2);
    }
    env.ANDROID_SERIAL = serial;
  } else if (arg.startsWith('--device=')) env.ANDROID_SERIAL = arg.slice('--device='.length);
  else args.push(arg);
}

const gradlew = path.join(root, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
const result = spawnSync(gradlew, args, { cwd: root, env, stdio: 'inherit', shell: process.platform === 'win32' });
if (result.error) {
  console.error(result.error.message);
  process.exit(1);
}
process.exit(result.status ?? 1);
