// A page may open a menu over the finger before Android reports its long press. Remember the
// original DOM target so that contextmenu still reaches the row's own event handlers.
(() => {
    if (window.__xviContextMenuPress) return;
    let press = null;
    Object.defineProperty(window, '__xviContextMenuPress', { value: () => press });
    const options = { capture: true, passive: true };
    const start = event => {
        if (event.touches && event.touches.length !== 1) { press = null; return; }
        const point = event.touches ? event.touches[0] : event;
        const path = event.composedPath ? event.composedPath() : [];
        const target = path[0] || event.target;
        press = { target, x: point.clientX, y: point.clientY };
    };
    const end = () => {
        const ended = press;
        // An already queued Android callback may run just after the finger lifts.
        setTimeout(() => { if (press === ended) press = null; }, 1000);
    };
    const cancel = () => { press = null; };
    window.addEventListener('pointerdown', start, options);
    window.addEventListener('touchstart', start, options);
    window.addEventListener('pointerup', end, options);
    window.addEventListener('touchend', end, options);
    window.addEventListener('pointercancel', cancel, options);
    window.addEventListener('touchcancel', cancel, options);
})();
