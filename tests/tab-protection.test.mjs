import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const script = readFileSync(new URL('../app/src/main/assets/tab-protection.js', import.meta.url), 'utf8');
const element = (tagName, fields = {}) => ({ tagName, ...fields });
const document = (elements = [], readyState = 'complete') => ({ readyState, createTreeWalker: () => {
    let index = 0;
    return { nextNode: () => elements[index++] || null };
} });
const safe = (doc, customElements) => vm.runInNewContext(script, { document: doc, customElements }, { timeout: 1000 }).reclaimable;
const input = (fields = {}) => element('INPUT', { type: 'text', value: '', defaultValue: '', ...fields });

test('ordinary read-only pages and paused media can be reclaimed', () => {
    assert.equal(safe(document([element('P'), input(), element('VIDEO', { paused: true, ended: false })])), true);
});
test('playing audio and video are protected even when muted', () => {
    for (const tag of ['AUDIO', 'VIDEO']) assert.equal(safe(document([element(tag, { paused: false, ended: false, muted: true })])), false);
});
test('typed, cleared and controlled form fields are protected without returning contents', () => {
    for (const fields of [{ value: 'draft' }, { value: '', defaultValue: 'deleted' }, { value: 'draft', defaultValue: 'draft' }, { type: 'password', value: 'secret' }]) {
        const result = vm.runInNewContext(script, { document: document([input(fields)]) });
        assert.equal(result.reclaimable, false);
        assert.deepEqual(Object.keys(result), ['reclaimable']);
    }
    assert.equal(safe(document([element('TEXTAREA', { value: 'draft', defaultValue: '' })])), false);
});
test('changed checkboxes, selects and selected uploads are protected', () => {
    assert.equal(safe(document([input({ type: 'checkbox', checked: true, defaultChecked: false })])), false);
    assert.equal(safe(document([element('SELECT', { options: [{ selected: true, defaultSelected: false }] })])), false);
    assert.equal(safe(document([input({ type: 'file', files: [{}] })])), false);
});
test('editors, form-associated custom controls and nested editable shadows are protected', () => {
    assert.equal(safe(document([element('DIV', { isContentEditable: true })])), false);
    const registry = { get: tag => tag === 'my-editor' ? { formAssociated: true } : tag === 'app-header' ? {} : undefined };
    assert.equal(safe(document([element('MY-EDITOR')]), registry), false);
    assert.equal(safe(document([element('DIV', { shadowRoot: document([input({ value: 'draft' })]) })])), false);
});
test('frame checks recurse and uncertainty keeps the page alive', () => {
    assert.equal(safe(document([element('IFRAME', { contentDocument: document([input({ value: 'draft' })]) })])), false);
    assert.equal(safe(document([element('IFRAME', { contentDocument: document([element('P')]) })])), true);
    assert.equal(safe(document([element('IFRAME', { contentDocument: null })])), false);
    assert.equal(safe(document([element('IFRAME', { contentDocument: document([], 'loading') })])), false);
    assert.equal(safe({ readyState: 'complete', createTreeWalker() { throw new Error('unavailable'); } }), false);
    assert.equal(safe(document([], 'loading')), false);
});
test('ordinary custom elements without their own form state do not pin pages', () => {
    const registry = { get: tag => tag === 'app-header' ? {} : undefined };
    assert.equal(safe(document([element('APP-HEADER'), element('YT-ICON')]), registry), true);
    assert.equal(safe(document([element('APP-HEADER')])), true);
});
test('reset empty forms and hidden metadata do not unnecessarily pin pages', () => {
    assert.equal(safe(document([input(), input({ type: 'hidden', value: 'csrf' }), input({ type: 'checkbox', checked: false, defaultChecked: false })])), true);
});
test('very large documents are retained after a bounded scan', () => {
    assert.equal(safe(document(Array.from({ length: 10001 }, () => element('P')))), false);
});
