import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const windows = process.platform === 'win32';
const gradlew = path.join(root, windows ? 'gradlew.bat' : 'gradlew');
const apkDir = path.join(root, 'app/build/outputs/apk/release');
const signingFile = path.join(root, '.signing/release.properties');

/** A .properties file as Java reads it, except for values continued on the next line. */
function properties(file) {
  const unescape = (text) => text.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (_, c) =>
    c.length === 5 ? String.fromCharCode(parseInt(c.slice(1), 16)) : ({ t: '\t', n: '\n', r: '\r', f: '\f' })[c] ?? c);
  const values = {};
  for (const line of readFileSync(file, 'utf8').split(/\r\n|\r|\n/)) {
    const match = /^[ \t\f]*((?:\\.|[^\\=: \t\f])+)[ \t\f]*[=: \t\f]?[ \t\f]*(.*)$/.exec(line);
    if (match && !/^[#!]/.test(match[1])) values[unescape(match[1])] = unescape(match[2]);
  }
  return values;
}

/** zipalign and apksigner from the newest Build Tools in the SDK Gradle uses. */
function buildTools() {
  const local = path.join(root, 'local.properties');
  const sdk = (existsSync(local) && properties(local)['sdk.dir']) || process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdk) throw new Error('Android SDK not found: set sdk.dir in local.properties or ANDROID_HOME');
  const dir = path.join(sdk, 'build-tools');
  const tool = (version, name) => path.join(dir, version, windows ? `${name}${name === 'apksigner' ? '.bat' : '.exe'}` : name);
  const version = (existsSync(dir) ? readdirSync(dir) : [])
    .filter((v) => existsSync(tool(v, 'zipalign')) && existsSync(tool(v, 'apksigner')))
    .sort((a, b) => a.localeCompare(b, 'en', { numeric: true }))
    .pop();
  if (!version) throw new Error(`No Build Tools with zipalign and apksigner in ${dir}`);
  return { zipalign: tool(version, 'zipalign'), apksigner: tool(version, 'apksigner') };
}

/** Runs a build tool and returns its output, which is shown only when it fails. */
function run(command, args, env = {}) {
  const quote = (arg) => (windows ? `"${arg}"` : arg);
  const result = spawnSync(quote(command), args.map(quote), { cwd: root, env: { ...process.env, ...env }, encoding: 'utf8', shell: windows });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${path.basename(command)} failed:\n${result.stdout}${result.stderr}`);
  return result.stdout;
}

/**
 * Packs [built] again with Zopfli, which fits the same files in about 4% less than Gradle's packager,
 * and signs it again with the release key the way Gradle does: APK Signature Scheme v2 only.
 */
function recompress(built, target) {
  const { zipalign, apksigner } = buildTools();
  const signing = properties(signingFile);
  const unsigned = `${target}.unsigned`;
  const signed = `${target}.signed`;
  try {
    run(zipalign, ['-f', '-z', '4', built, unsigned]);
    run(apksigner, ['sign', '--ks', path.resolve(path.dirname(signingFile), signing.storeFile), '--ks-type', 'PKCS12',
      '--ks-key-alias', signing.keyAlias, '--ks-pass', 'env:XVI_STORE_PASSWORD', '--key-pass', 'env:XVI_KEY_PASSWORD',
      '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'false', '--v4-signing-enabled', 'false',
      '--out', signed, unsigned], { XVI_STORE_PASSWORD: signing.storePassword, XVI_KEY_PASSWORD: signing.keyPassword });
    // Signed with any other key, the APK could not update the copies already installed.
    const certificate = (apk) => /certificate SHA-256 digest: (\w+)/.exec(run(apksigner, ['verify', '--print-certs', apk]))?.[1];
    const [recompressed, original] = [signed, built].map(certificate);
    if (!recompressed || recompressed !== original) throw new Error('Recompressed APK is not signed like the Gradle build');
    renameSync(signed, target);
  } finally {
    rmSync(unsigned, { force: true });
    rmSync(signed, { force: true });
  }
}

try {
  const result = spawnSync(gradlew, [':app:assembleRelease'], {
    cwd: root,
    stdio: 'inherit',
    shell: windows,
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
  const built = path.join(apkDir, output.outputFile);
  recompress(built, path.join(outputDir, name));
  const apk = readFileSync(path.join(outputDir, name));
  writeFileSync(path.join(outputDir, 'SHA256SUMS'), `${createHash('sha256').update(apk).digest('hex')}  ${name}\n`);
  process.stdout.write(`\n${name}: ${apk.length} bytes (${statSync(built).size} as Gradle packed it)\nRelease files: ${outputDir}\n`);
} catch (error) {
  process.stderr.write(`${error.message}\n`);
  process.exit(1);
}
