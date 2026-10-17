import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const script = readFileSync(new URL('../app/src/main/assets/ai/page-images.js', import.meta.url), 'utf8');
const image = (src, extra = {}) => ({ src, naturalWidth: 400, naturalHeight: 300, ...extra });
const call = (images, mode, key = '', canvas = null) => vm.runInNewContext(`(${script})(${JSON.stringify(mode)}, ${JSON.stringify(key)})`, {
    URL,
    document: {
        images,
        createElement() {
            if (!canvas) throw new Error('Cross-origin pixels are unavailable');
            return canvas;
        },
    },
});
const list = images => JSON.parse(call(images, 'list'));

test('page image selection uses rendered sources and excludes icons, duplicates and private URLs', () => {
    const result = list([
        image('https://example.org/small.png', { currentSrc: 'https://example.org/large.png', alt: '图表' }),
        image('https://example.org/large.png'),
        image('https://example.org/icon.png', { naturalWidth: 32, naturalHeight: 32 }),
        image('blob:https://example.org/photo'), image('file:///photo'),
        image('data:image/svg+xml;base64,YWJj'), image('data:image/png;base64,YWJj'),
    ]);
    assert.deepEqual(result.map(i => i.url), ['https://example.org/large.png', '']);
    assert.equal(result[0].name, '图表');
    assert.equal(result[0].key, 'https://example.org/large.png');
    assert.match(result[1].key, /^inline:/);
});

test('article pictures come first in reading order, the rest by size', () => {
    const inArticle = { closest: () => ({}) };
    const result = list([
        image('https://example.org/logo.png', { naturalWidth: 100, naturalHeight: 100 }),
        image('https://example.org/ad.png', { naturalWidth: 600, naturalHeight: 600 }),
        image('https://example.org/one.png', { ...inArticle, naturalWidth: 100, naturalHeight: 100 }),
        image('https://example.org/two.png', { ...inArticle }),
    ]);
    assert.deepEqual(result.map(i => i.url.split('/').pop()), ['one.png', 'two.png', 'ad.png', 'logo.png']);
});

test('lazy pictures that have not loaded are offered by their laid-out size', () => {
    const result = list([image('https://example.org/lazy.png', {
        naturalWidth: 0, naturalHeight: 0, getBoundingClientRect: () => ({ width: 300, height: 200 }),
    })]);
    assert.equal(result.length, 1);
});

test('page picture candidates are bounded and huge inline images are excluded', () => {
    const result = list([image('data:image/png;base64,' + 'a'.repeat(4000000)),
        ...Array.from({ length: 60 }, (_, i) => image(`https://example.org/${i}.png`))]);
    assert.equal(result.length, 30);
});

test('inline data is read back by key, and tainted pictures give nothing', () => {
    const data = 'data:image/png;base64,' + 'b'.repeat(100);
    const images = [image('https://example.org/a.png'), image(data)];
    const [, inline] = list(images);
    assert.equal(call(images, 'data', inline.key), data);
    assert.equal(call(images, 'preview', 'https://example.org/a.png'), '');
    assert.equal(call(images, 'data', 'https://example.org/missing.png'), '');
    const canvas = { getContext: () => ({ fillRect() {}, drawImage() {} }), toDataURL: () => 'data:image/jpeg;base64,eA==' };
    assert.equal(call(images, 'preview', 'https://example.org/a.png', canvas), 'data:image/jpeg;base64,eA==');
});

test('lazy pictures behind placeholders are offered by their real address', () => {
    const attributes = values => ({ getAttribute: name => values[name] ?? null });
    const placeholder = 'data:image/gif;base64,R0lGODlhAQABAAAAACw=';
    const images = [
        image(placeholder, { naturalWidth: 1, naturalHeight: 1, ...attributes({ 'data-src': 'https://mmbiz.example/a.jpg' }),
            getBoundingClientRect: () => ({ width: 360, height: 240 }) }),
        image('data:image/svg+xml,%3Csvg%3E', { naturalWidth: 0, naturalHeight: 0,
            ...attributes({ 'data-actualsrc': 'https://pic.example/b.jpg' }), getBoundingClientRect: () => ({ width: 0, height: 0 }) }),
        image(placeholder, { naturalWidth: 1, naturalHeight: 1, ...attributes({ 'data-src': 'https://example.org/icon.png' }),
            getBoundingClientRect: () => ({ width: 24, height: 24 }) }),
    ];
    assert.deepEqual(list(images).map(i => i.url), ['https://mmbiz.example/a.jpg', 'https://pic.example/b.jpg']);
    // Its pixels are not loaded, so the app downloads it instead of reading the placeholder.
    const canvas = { getContext: () => ({ fillRect() {}, drawImage() {} }), toDataURL: () => 'data:image/jpeg;base64,eA==' };
    assert.equal(call(images, 'preview', 'https://mmbiz.example/a.jpg', canvas), '');
});
