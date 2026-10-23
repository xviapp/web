import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const runtime = readFileSync(new URL('../app/src/main/assets/userscript-runtime.js', import.meta.url), 'utf8');
const meta = readFileSync(new URL('../app/src/main/java/app/xvi/web/UserScriptMeta.kt', import.meta.url), 'utf8');
/** The wrapper's parameters, read from the one list the app keeps. */
const PARAMS = [...meta.match(/val API = listOf\(([^)]*)\)/)[1].matchAll(/"([^"]+)"/g)].map(m => m[1]);
const HOOK = '__xvihook';
const BRIDGE = '__xvibridge';

/** A script's settings as the app writes them; the patterns are what UserScriptMeta compiles. */
const settings = (fields = {}) => ({
    name: 'Test', namespace: '', version: '1', description: '', author: '', matches: [], includes: [], excludes: [], grants: [],
    runAt: 'document-start', noFrames: false, block: '// ==UserScript==', values: {},
    run: [['^https?://example\\.com(?::\\d+)?/.*$', 'i']], skip: [], ...fields,
});

const hook = JSON.stringify(HOOK);
/** A script's registration: its settings, and its code in the wrapper whose parameters are the API. */
const scriptPiece = (id, settings, code) => `(function () { const runtime = window[${hook}]; if (!runtime) return; runtime.define(${JSON.stringify(id)}, ` +
    `JSON.parse(${JSON.stringify(JSON.stringify(settings))}), function (${PARAMS.join(', ')}) {\n${code}\n}); })();`;
const valuesPiece = (id, values) => `(() => { const runtime = window[${hook}]; if (runtime) runtime.values(${JSON.stringify(id)}, ` +
    `JSON.parse(${JSON.stringify(JSON.stringify(values))})); })();`;
const END = `(() => { const runtime = window[${hook}]; try { delete window[${hook}]; } catch (_) {} if (runtime) runtime.start(); })();`;

/**
 * The registrations the app makes, in order: runtime, each script in its wrapper, what the scripts stored,
 * then the hook's removal, which starts the scripts. [valuesFirst] hands the values over before the scripts.
 */
function load(scripts, { href = 'https://example.com/a', readyState = 'loading', top = null, bridge = true, valuesFirst = false } = {}) {
    const posted = [], errors = [], timers = [], listeners = {}, head = { children: [], appendChild(node) { this.children.push(node); } };
    const document = { readyState, head, documentElement: head, createElement: tagName => ({ tagName, textContent: '' }), listeners };
    const context = vm.createContext({
        document, location: { href }, console: { log() {}, error: (...args) => errors.push(args) },
        setTimeout: fn => timers.push(fn), EventTarget: function EventTarget() {}, ran: [],
        URL, URLSearchParams, Blob, FormData, Response, TextDecoder, btoa, atob,
    });
    context.EventTarget.prototype.addEventListener = function (type, fn) { (this.listeners[type] ||= []).push(fn); };
    context.window = context;
    context.top = top || context;
    const channel = { postMessage: message => posted.push(JSON.parse(message)) };
    if (bridge) context[BRIDGE] = channel;
    const config = { hook: HOOK, bridge: BRIDGE, token: 'secret', handler: { name: 'Xvi', version: '0.4.6' }, params: PARAMS };
    vm.runInContext(`(() => { const config = JSON.parse(${JSON.stringify(JSON.stringify(config))}); (${runtime})(config); })();`, context);
    const pieces = Object.entries(scripts).map(([id, fields]) => { const { values, ...rest } = settings(fields.settings); return { id, rest, values, code: fields.code }; });
    const code = () => pieces.forEach(({ id, rest, code }) => vm.runInContext(scriptPiece(id, rest, code), context));
    const values = () => pieces.forEach(({ id, values }) => { if (Object.keys(values).length) vm.runInContext(valuesPiece(id, values), context); });
    if (valuesFirst) { values(); code(); } else { code(); values(); }
    vm.runInContext(END, context);
    const fire = type => (listeners[type] || []).splice(0).forEach(fn => fn());
    const tick = () => timers.splice(0).forEach(fn => fn());
    /** The app answering on the bridge. */
    const answer = message => channel.onmessage({ data: JSON.stringify(message) });
    return { context, posted, errors, head, fire, tick, answer };
}

test('scripts run only where their patterns say', () => {
    const { context } = load({
        a: { code: 'ran.push("a")' },
        b: { code: 'ran.push("b")', settings: { run: [['^https://other\\.org/', 'i']] } },
        c: { code: 'ran.push("c")', settings: { skip: [['^https://example\\.com/a$', 'i']] } },
    });
    assert.deepEqual([...context.ran], ['a']);
    const elsewhere = load({ a: { code: 'ran.push("a")' } }, { href: 'about:blank' });
    assert.deepEqual([...elsewhere.context.ran], []);
});

test('the fragment does not count', () => {
    const { context } = load({ a: { code: 'ran.push("a")', settings: { run: [['^https://example\\.com/a$', 'i']] } } }, { href: 'https://example.com/a#x' });
    assert.deepEqual([...context.ran], ['a']);
});

test('run-at waits for the document as asked', () => {
    const page = load({
        start: { code: 'ran.push("start")' },
        end: { code: 'ran.push("end")', settings: { runAt: 'document-end' } },
        idle: { code: 'ran.push("idle")', settings: { runAt: 'document-idle' } },
    });
    assert.deepEqual([...page.context.ran], ['start']);
    page.fire('DOMContentLoaded');
    assert.deepEqual([...page.context.ran], ['start', 'end']);
    page.tick();
    assert.deepEqual([...page.context.ran], ['start', 'end', 'idle']);
    const parsed = load({ end: { code: 'ran.push("end")', settings: { runAt: 'document-end' } } }, { readyState: 'interactive' });
    assert.deepEqual([...parsed.context.ran], ['end']);
});

test('noframes keeps a script out of frames', () => {
    const { context } = load({ a: { code: 'ran.push("a")', settings: { noFrames: true } }, b: { code: 'ran.push("b")' } }, { top: {} });
    assert.deepEqual([...context.ran], ['b']);
});

test('stored values read back, and changes go to the app with the token', () => {
    const { context, posted } = load({ a: {
        settings: { values: { count: '2', __proto__x: '"y"' } },
        code: `ran.push(GM_getValue('count'), GM_getValue('missing', 'fallback'));
            GM_setValue('count', 3); GM_setValue('count', 3); GM_setValue('__proto__', { a: 1 });
            ran.push(GM_getValue('count'), GM_getValue('__proto__').a, GM_listValues().sort().join());
            GM_deleteValue('count'); GM_deleteValue('never'); GM_setValue('gone', undefined);
            ran.push(GM_getValue('count', 'deleted'));`,
    } });
    assert.deepEqual([...context.ran], [2, 'fallback', 3, 1, '__proto__,__proto__x,count', 'deleted']);
    assert.deepEqual(posted, [
        { k: 'secret', s: 'a', op: 'set', key: 'count', value: '3' },
        { k: 'secret', s: 'a', op: 'set', key: '__proto__', value: '{"a":1}' },
        { k: 'secret', s: 'a', op: 'delete', key: 'count' },
    ]);
});

test('values reach their script whichever registration comes first', () => {
    for (const valuesFirst of [false, true]) {
        const { context } = load({ a: { settings: { values: { n: '1' } }, code: 'ran.push(GM_getValue("n"))' }, b: { code: 'ran.push(GM_getValue("n", "none"))' } },
            { valuesFirst });
        assert.deepEqual([...context.ran], [1, 'none']);
    }
});

test('scripts wait for the last registration, and the hook is gone by then', () => {
    const context = vm.createContext({ document: { readyState: 'loading' }, location: { href: 'https://example.com/a' }, console, ran: [],
        setTimeout, EventTarget: function EventTarget() {}, URL, URLSearchParams, Blob, FormData, Response, TextDecoder, btoa, atob });
    context.window = context;
    context.top = context;
    const config = { hook: HOOK, token: 'secret', handler: { name: 'Xvi', version: '1' }, params: PARAMS };
    vm.runInContext(`(${runtime})(${JSON.stringify(config)})`, context);
    const { values, ...rest } = settings();
    vm.runInContext(scriptPiece('a', rest, `ran.push(typeof window[${hook}])`), context);
    assert.deepEqual([...context.ran], []);
    vm.runInContext(END, context);
    vm.runInContext(scriptPiece('late', rest, 'ran.push("late")'), context);
    assert.deepEqual([...context.ran], ['undefined']);
});

test('the promise API matches the plain one', async () => {
    const { context } = load({ a: { settings: { values: { v: '"x"' } }, code: 'ran.push(GM.getValue("v"), GM.setValue("w", 1).then(() => GM.listValues()))' } });
    assert.equal(await context.ran[0], 'x');
    assert.deepEqual([...await context.ran[1]], ['v', 'w']);
});

test('the bridge and the hook are gone before page scripts run', () => {
    const { context } = load({ a: { code: 'ran.push(typeof GM_setValue, unsafeWindow === window)' } });
    assert.equal(context[BRIDGE], undefined);
    assert.equal(context[HOOK], undefined);
    assert.deepEqual([...context.ran], ['function', true]);
});

test('a page that replaces JSON.stringify later never sees the token', () => {
    const page = load({ a: { code: 'window.later = () => GM_setValue("k", 1)', settings: { runAt: 'document-start' } } });
    const seen = [];
    vm.runInContext('JSON.stringify = function (v) { seen.push(v); return "{}"; }', Object.assign(page.context, { seen }));
    page.context.later();
    assert.deepEqual(seen, []);
    assert.equal(page.posted[0].k, 'secret');
});

test('a failing script leaves the others running', () => {
    const { context, errors } = load({ a: { code: 'throw new Error("broken")' }, b: { code: 'ran.push("b")' } });
    assert.deepEqual([...context.ran], ['b']);
    assert.equal(errors[0][0], '[Test]');
});

test('without the bridge values still work within the page', () => {
    const { context, posted } = load({ a: { code: 'GM_setValue("k", 1); ran.push(GM_getValue("k"))' } }, { bridge: false });
    assert.deepEqual([...context.ran], [1]);
    assert.deepEqual(posted, []);
});

test('GM_addStyle and GM_info', () => {
    const { context, head } = load({ a: { code: 'ran.push(GM_addStyle("p{}").tagName, GM_info.script.name, GM_info.scriptHandler, GM.info.version)' } });
    assert.deepEqual([...context.ran], ['style', 'Test', 'Xvi', '0.4.6']);
    assert.equal(head.children[0].textContent, 'p{}');
});

test('the runtime only installs once per document', () => {
    const page = load({ a: { code: 'ran.push("a")' } });
    vm.runInContext(`Object.defineProperty(window, ${JSON.stringify(HOOK)}, { value: 1, configurable: true });`, page.context);
    vm.runInContext(`(${runtime})({ hook: ${JSON.stringify(HOOK)}, scripts: {} })`, page.context);
    assert.equal(page.context[HOOK], 1);
});

/** Request bodies are prepared asynchronously, so requests reach the app a moment later. */
const settled = () => new Promise(done => setTimeout(done, 20));

test('every name the wrapper declares is provided', () => {
    const { context } = load({ a: { code: `ran.push(${JSON.stringify(PARAMS)}.filter(n => eval('typeof ' + n) === 'undefined'))` } });
    assert.deepEqual([...context.ran[0]], []);
});

test('GM_xmlhttpRequest goes to the app and hands back what it answers', async () => {
    const page = load({ a: { code: `GM_xmlhttpRequest({ url: '/api?q=1', method: 'post', headers: { 'X-A': 1 }, data: 'x=1', responseType: 'json', context: 7,
        onload: r => ran.push(['load', r.status, r.response.ok, r.finalUrl, r.context]), onloadend: () => ran.push('end') });` } });
    await settled();
    const sent = page.posted.find(m => m.op === 'xhr');
    assert.deepEqual({ ...sent, id: 0 }, { k: 'secret', s: 'a', op: 'xhr', id: 0, method: 'POST', url: 'https://example.com/api?q=1', headers: { 'X-A': '1' },
        data: { text: 'x=1' }, timeout: 0, anonymous: false, binary: false });
    page.answer({ t: 'xhr', id: sent.id, event: 'load', status: 200, statusText: 'OK', finalUrl: 'https://example.com/api?q=1', headers: '', mime: 'application/json', text: '{"ok":true}' });
    assert.deepEqual(JSON.parse(JSON.stringify([...page.context.ran])), [['load', 200, true, 'https://example.com/api?q=1', 7], 'end']);
});

test('binary responses, failures and aborts', async () => {
    const page = load({ a: { code: `
        GM_xmlhttpRequest({ url: 'https://other.org/b', responseType: 'arraybuffer', onload: r => ran.push(new Uint8Array(r.response).join()) });
        GM_xmlhttpRequest({ url: 'https://other.org/c', onerror: r => ran.push('error:' + r.error) });
        GM_xmlhttpRequest({ url: 'https://other.org/d', ontimeout: () => ran.push('timeout') });
        window.slow = GM_xmlhttpRequest({ url: 'https://other.org/e', onabort: () => ran.push('abort') });
        GM_xmlhttpRequest({ url: 'javascript:alert(1)', onerror: r => ran.push('bad:' + r.error) });` } });
    await settled();
    const [b, c, d, e] = page.posted.filter(m => m.op === 'xhr');
    assert.equal(b.binary, true);
    page.answer({ t: 'xhr', id: b.id, event: 'load', status: 200, headers: '', base64: Buffer.from([1, 2, 3]).toString('base64') });
    page.answer({ t: 'xhr', id: c.id, event: 'error', error: 'refused' });
    page.answer({ t: 'xhr', id: d.id, event: 'timeout' });
    page.context.slow.abort();
    page.answer({ t: 'xhr', id: e.id, event: 'load', status: 200, text: 'late' });
    page.tick();
    assert.deepEqual([...page.context.ran], ['1,2,3', 'error:refused', 'timeout', 'abort', 'bad:unsupported address']);
    assert.deepEqual(page.posted.filter(m => m.op === 'abort').map(m => m.id), [e.id]);
});

test('request bodies of other kinds arrive as text or base64', async () => {
    const page = load({ a: { code: `
        GM_xmlhttpRequest({ url: '/1', method: 'POST', data: new URLSearchParams({ a: 'b c' }) });
        GM_xmlhttpRequest({ url: '/2', method: 'POST', data: new Uint8Array([104, 105]) });
        GM_xmlhttpRequest({ url: '/3', method: 'POST', data: new Blob(['hey'], { type: 'text/plain' }) });` } });
    await settled();
    const [a, b, c] = page.posted.filter(m => m.op === 'xhr').sort((x, y) => x.url.localeCompare(y.url));
    assert.deepEqual(a.data, { text: 'a=b+c', type: 'application/x-www-form-urlencoded;charset=UTF-8' });
    assert.deepEqual(b.data, { base64: Buffer.from('hi').toString('base64') });
    assert.deepEqual(c.data, { base64: Buffer.from('hey').toString('base64'), type: 'text/plain' });
});

test('GM.xmlHttpRequest settles its promise', async () => {
    const page = load({ a: { code: `window.ok = GM.xmlHttpRequest({ url: '/x' }); window.bad = GM.xmlHttpRequest({ url: '/y' });` } });
    await settled();
    const [x, y] = page.posted.filter(m => m.op === 'xhr');
    page.answer({ t: 'xhr', id: x.id, event: 'load', status: 204, headers: '', text: '' });
    page.answer({ t: 'xhr', id: y.id, event: 'error', error: 'network' });
    assert.equal((await page.context.ok).status, 204);
    await assert.rejects(page.context.bad, r => r.error === 'network');
});

test('menu commands are registered with the app and run when it says so', () => {
    const page = load({ a: { code: `const one = GM_registerMenuCommand('设置', () => ran.push('settings'));
        const two = GM_registerMenuCommand('其他', () => ran.push('other')); GM_unregisterMenuCommand(two); window.one = one;` } });
    assert.deepEqual(page.posted.filter(m => m.op === 'menu' || m.op === 'unmenu').map(m => [m.op, m.name ?? null]),
        [['menu', '设置'], ['menu', '其他'], ['unmenu', null]]);
    page.answer({ t: 'menu', s: 'a', id: page.context.one });
    page.answer({ t: 'menu', s: 'a', id: 'unknown' });
    page.answer({ t: 'menu', s: 'b', id: page.context.one });
    assert.deepEqual([...page.context.ran], ['settings']);
});

test('tabs, clipboard, downloads and notifications', () => {
    const page = load({ a: { code: `GM_openInTab('/next'); GM_openInTab('https://b.org/', false); GM_openInTab('https://c.org/', { active: true });
        GM_openInTab('javascript:void 0'); GM_setClipboard(42); GM_download('/f.zip', 'f.zip');
        GM_download({ url: 'data:text/plain,x', onerror: e => ran.push(e.error) });
        GM_notification({ text: '完成', onclick: () => ran.push('click'), ondone: () => ran.push('done') }); GM_notification('纯文本');` } });
    page.tick();
    const ops = page.posted.filter(m => m.op !== 'set');
    assert.deepEqual(ops.filter(m => m.op === 'open').map(m => [m.url, m.background]),
        [['https://example.com/next', true], ['https://b.org/', false], ['https://c.org/', false]]);
    assert.deepEqual(ops.find(m => m.op === 'clip').text, '42');
    assert.deepEqual(ops.filter(m => m.op === 'download').map(m => [m.url, m.name]), [['https://example.com/f.zip', 'f.zip']]);
    const notes = ops.filter(m => m.op === 'notify');
    assert.deepEqual(notes.map(m => [m.title, m.text, m.clickable]), [['Test', '完成', true], ['Test', '纯文本', false]]);
    page.answer({ t: 'notify', id: notes[0].id, event: 'click' });
    page.answer({ t: 'notify', id: notes[0].id, event: 'done' });
    assert.deepEqual([...page.context.ran], ['not_supported', 'click', 'done']);
});

test('value change listeners hear local and remote changes', () => {
    const page = load({ a: { settings: { values: { n: '1' } }, code: `
        GM_addValueChangeListener('n', (key, before, after, remote) => ran.push([key, before, after, remote]));
        GM_setValue('n', 2); GM_setValue('other', 1); window.read = () => GM_getValue('n');` } });
    assert.equal(page.posted.filter(m => m.op === 'listen').length, 1);
    page.answer({ t: 'value', s: 'a', key: 'n', value: '5' });
    page.answer({ t: 'value', s: 'a', key: 'n', value: '5' });
    page.answer({ t: 'value', s: 'a', key: 'n', value: null });
    assert.deepEqual(JSON.parse(JSON.stringify([...page.context.ran])), [['n', 1, 2, false], ['n', 2, 5, true], ['n', 5, null, true]]);
    assert.equal(page.context.read(), undefined);
});

test('resources read back as text and as data addresses', () => {
    const { context } = load({ a: { settings: { resources: { css: { mime: 'text/css', data: Buffer.from('p{color:红}').toString('base64') } } },
        code: `ran.push(GM_getResourceText('css'), GM_getResourceURL('css'), GM_getResourceText('missing'), GM_info.script.resources[0].mimetype)` } });
    assert.deepEqual([...context.ran], ['p{color:红}', 'data:text/css;base64,' + Buffer.from('p{color:红}').toString('base64'), null, 'text/css']);
});
