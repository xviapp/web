import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const script = readFileSync(new URL('../app/src/main/assets/page-context-menu.js', import.meta.url), 'utf8');
const touchScript = readFileSync(new URL('../app/src/main/assets/page-touch-target.js', import.meta.url), 'utf8');
class MenuEvent extends Event {
    constructor(type, options) {
        super(type, options);
        const { bubbles, cancelable, composed, ...fields } = options;
        Object.assign(this, fields);
    }
}
const element = () => Object.assign(new EventTarget(), { tagName: 'A', isConnected: true });
const page = target => ({
    document: { elementFromPoint: () => target },
    devicePixelRatio: 2,
    PointerEvent: MenuEvent,
    MouseEvent: MenuEvent
});
const offer = (window, x = 120, y = 240) => vm.runInNewContext(
    script.replaceAll('__X__', String(x)).replaceAll('__Y__', String(y)), { window }
);

test('website cancellation suppresses the browser menu and dispatches only once', () => {
    const link = element();
    let count = 0;
    link.addEventListener('contextmenu', event => {
        count++;
        event.preventDefault();
    });
    assert.equal(offer(page(link)), false);
    assert.equal(count, 1);
});

test('uncancelled links and images keep their browser menu', () => {
    for (const tagName of ['A', 'IMG']) {
        const target = Object.assign(element(), { tagName });
        assert.equal(offer(page(target)), true);
    }
});

test('event reaches the pressed point with zoom and viewport offsets', () => {
    const link = element();
    const window = page(link);
    window.visualViewport = { scale: 2, offsetLeft: 10, offsetTop: 20 };
    window.document.elementFromPoint = (x, y) => {
        assert.equal(x, 40);
        assert.equal(y, 80);
        return link;
    };
    let received;
    link.addEventListener('contextmenu', event => { received = event; });
    assert.equal(offer(window), true);
    assert.equal(received.clientX, 40);
    assert.equal(received.clientY, 80);
    assert.equal(received.bubbles, true);
    assert.equal(received.cancelable, true);
    assert.equal(received.composed, true);
    assert.equal(received.pointerType, 'touch');
    assert.equal(received.view, window);
});

test('same-origin frame menus can cancel the browser menu', () => {
    const link = element();
    const child = page(link);
    child.document.elementFromPoint = (x, y) => {
        assert.equal(x, 19);
        assert.equal(y, 39);
        return link;
    };
    const frame = {
        tagName: 'IFRAME', contentWindow: child,
        offsetWidth: 100, offsetHeight: 100, clientLeft: 1, clientTop: 1,
        getBoundingClientRect: () => ({ left: 20, top: 40, width: 200, height: 200 })
    };
    link.addEventListener('contextmenu', event => {
        assert.equal(event.view, child);
        event.preventDefault();
    });
    assert.equal(offer(page(frame)), false);
});

test('missing elements and inaccessible frames retain the browser fallback', () => {
    assert.equal(offer(page(null)), true);
    const frame = { tagName: 'IFRAME', get contentWindow() { throw new Error('Cross origin'); } };
    assert.equal(offer(page(frame)), true);
});

function trackedPage(target) {
    const window = Object.assign(new EventTarget(), page(target));
    const timers = [];
    const context = { window, setTimeout: callback => timers.push(callback) };
    vm.runInNewContext(touchScript, context);
    const touch = (type, target, points = [{ clientX: 60, clientY: 120 }]) => {
        const event = new MenuEvent(type, { touches: points });
        event.composedPath = () => [target, window];
        window.dispatchEvent(event);
    };
    return { window, touch, flush: () => timers.splice(0).forEach(callback => callback()), context };
}

test('a menu opened before Android long press cannot replace the original event target', () => {
    const row = element();
    const overlay = element();
    const { window, touch } = trackedPage(overlay);
    let rowEvents = 0, overlayEvents = 0;
    row.addEventListener('contextmenu', event => { rowEvents++; event.preventDefault(); });
    overlay.addEventListener('contextmenu', () => overlayEvents++);
    touch('touchstart', row);
    assert.equal(offer(window), false);
    assert.equal(rowEvents, 1);
    assert.equal(overlayEvents, 0);
});

test('completed and cancelled touches do not retain a stale menu target', () => {
    const row = element();
    const { window, touch, flush } = trackedPage(element());
    row.addEventListener('contextmenu', event => event.preventDefault());
    touch('touchstart', row);
    touch('touchend', row);
    assert.equal(offer(window), false, 'a queued long press still reaches its original target');
    flush();
    assert.equal(offer(window), true, 'ended touch is released');
    touch('touchstart', row);
    touch('touchcancel', row);
    assert.equal(offer(window), true, 'cancelled touch is released immediately');
});

test('a previous release timer cannot erase a new touch', () => {
    const { window, touch, flush, context } = trackedPage(element());
    const first = element(), second = element();
    touch('touchstart', first);
    touch('touchend', first);
    touch('touchstart', second);
    flush();
    assert.equal(window.__xviContextMenuPress().target, second);
    vm.runInNewContext(touchScript, context);
    assert.equal(window.__xviContextMenuPress().target, second, 'page-ready fallback does not reset an installed observer');
});

test('a remembered point must match and multi-touch cancels it', () => {
    const row = element();
    row.addEventListener('contextmenu', event => event.preventDefault());
    const { window, touch } = trackedPage(element());
    touch('touchstart', row);
    assert.equal(offer(window, 240, 480), true);
    touch('touchstart', row, [{ clientX: 60, clientY: 120 }, { clientX: 70, clientY: 120 }]);
    assert.equal(offer(window), true);
});
