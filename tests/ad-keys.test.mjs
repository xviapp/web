import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../app/src/main/assets/ad-keys.js', import.meta.url), 'utf8');
const element = (name, children = []) => ({ nodeType: 1, isConnected: true, id: '', classList: [name], children });
function page(children) {
    let observer;
    let scans = 0;
    const styles = new Map();
    const document = {
        documentElement: element('root', children),
        getElementById: id => styles.get(id),
        createTreeWalker(root) {
            scans++;
            const all = [];
            const visit = node => { for (const child of node.children) { all.push(child); visit(child); } };
            visit(root);
            let index = 0;
            return { nextNode: () => all[index++] ?? null };
        },
    };
    const context = vm.createContext({ document, window: {}, MutationObserver: class {
        constructor(callback) { this.callback = callback; observer = this; }
        observe() {}
        disconnect() { this.disconnected = true; }
    } });
    const read = (revision = 1, pageUrl = '') => JSON.parse(vm.runInContext(`(${source})(${revision}, ${JSON.stringify(pageUrl)})`, context));
    const ack = () => vm.runInContext('window.__xviAdKeys.batch = null; window.__xviAdKeys.sent = true', context);
    const call = revision => { const result = read(revision); ack(); return result; };
    return { read, ack, call, styles, context, scans: () => scans, observer: () => observer };
}

test('bounded passes reach keys beyond the first 4000 and do not rescan unchanged DOM', () => {
    const view = page(Array.from({ length: 6500 }, (_, index) => element('key' + index)));
    const keys = [];
    let pass;
    let first = true;
    do {
        pass = view.call();
        assert.equal(pass.first, first);
        first = false;
        assert.ok(pass.keys.length <= 4000);
        keys.push(...pass.keys);
    } while (pass.more);
    assert.ok(keys.includes('.key6499'));
    assert.equal(new Set(keys).size, keys.length);
    assert.deepEqual(view.call(), { keys: [], first: false, more: false });
    assert.equal(view.scans(), 1);
});

test('mutations send only new keys and discard disconnected nodes', () => {
    const view = page([element('existing')]);
    view.call();
    const added = element('existing', [element('late-ad')]);
    const changed = element('changed-class');
    const removed = { ...element('removed'), isConnected: false };
    view.observer().callback([
        { type: 'childList', addedNodes: [added, removed] },
        { type: 'attributes', target: changed },
    ]);
    assert.deepEqual(view.call().keys.sort(), ['.changed-class', '.late-ad']);
    assert.deepEqual(view.call().keys, []);
});

test('filter revisions disconnect old observation and start a fresh stylesheet and key set', () => {
    const view = page([element('ad')]);
    view.call();
    const old = view.observer();
    let removed = false;
    view.context.window.__xviAdKeys.sheet = { remove() { removed = true; } };
    const result = view.call(2);
    assert.equal(old.disconnected, true);
    assert.equal(removed, true);
    assert.equal(result.first, true);
    assert.ok(result.keys.includes('.ad'));
});

test('a canceled native pass is retried until its keys and base rules have been applied', () => {
    const view = page(Array.from({ length: 2200 }, (_, index) => element('key' + index)));
    const first = view.read();
    assert.deepEqual(view.read(), first);
    assert.equal(first.first, true);
    view.ack();
    const next = view.read();
    assert.equal(next.first, false);
    assert.ok(next.keys.includes('.key2199'));
});

test('same-document address changes reset URL-dependent rules and keys', () => {
    const view = page([element('ad')]);
    view.read(1, 'https://example.com/one');
    view.ack();
    const old = view.observer();
    const changed = view.read(1, 'https://example.com/two');
    assert.equal(old.disconnected, true);
    assert.equal(changed.first, true);
    assert.ok(changed.keys.includes('.ad'));
});

test('an element of the page named like the sheet is never taken for it', () => {
    const view = page([element('ad')]);
    let removed = false;
    view.styles.set('__xvi_hide', { remove() { removed = true; } });
    view.call();
    view.call(2);
    assert.equal(removed, false);
});

test('a new address under the same filter keeps the old sheet until its own rules are in', () => {
    const view = page([element('ad')]);
    view.read(1, 'https://example.com/one');
    view.ack();
    const sheet = { remove() { this.removed = true; } };
    view.context.window.__xviAdKeys.sheet = sheet;
    view.read(1, 'https://example.com/two');
    assert.equal(sheet.removed, undefined);
    assert.equal(view.context.window.__xviAdKeys.previous, sheet);
    view.read(2, 'https://example.com/two');
    assert.equal(sheet.removed, true);
});

test('stopping disconnects the observer, and the next pass walks the page again for what it missed', () => {
    const view = page([element('ad')]);
    view.call();
    const observer = view.observer();
    vm.runInContext(`(${source})(1, '', true)`, view.context);
    assert.equal(observer.disconnected, true);
    assert.equal(view.context.window.__xviAdKeys.watching, false);
    view.context.document.documentElement.children.push(element('missed'));
    assert.deepEqual(view.call().keys, ['.missed']);
    assert.equal(view.context.window.__xviAdKeys.watching, true);
});
