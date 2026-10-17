// Android consumes link/image long presses before WebView dispatches contextmenu. Give the
// website that event once, and return whether its default browser menu is still wanted.
(() => {
    try {
        let view = window;
        const viewport = view.visualViewport;
        const scale = view.devicePixelRatio * (viewport ? viewport.scale : 1);
        let x = __X__ / scale + (viewport ? viewport.offsetLeft : 0);
        let y = __Y__ / scale + (viewport ? viewport.offsetTop : 0);
        let target;
        for (let depth = 0; depth < 16; depth++) {
            const press = view.__xviContextMenuPress && view.__xviContextMenuPress();
            const original = press && press.target && press.target.isConnected
                && Math.abs(press.x - x) < 2 && Math.abs(press.y - y) < 2;
            target = original ? press.target : view.document.elementFromPoint(x, y);
            if (!target) return true;
            // Follow same-origin frames; inaccessible frames retain the browser menu fallback.
            if (target.tagName !== 'IFRAME' && target.tagName !== 'FRAME') break;
            try {
                const child = target.contentWindow;
                if (!child || !child.document) break;
                const rect = target.getBoundingClientRect();
                x = (x - rect.left) * target.offsetWidth / rect.width - target.clientLeft;
                y = (y - rect.top) * target.offsetHeight / rect.height - target.clientTop;
                view = child;
            } catch (_) { return true; }
        }
        if (!target) return true;
        const Event = view.PointerEvent || view.MouseEvent;
        return target.dispatchEvent(new Event('contextmenu', {
            bubbles: true, cancelable: true, composed: true, view,
            clientX: x, clientY: y, button: 2, buttons: 0,
            pointerType: 'touch', pointerId: 1, isPrimary: true
        }));
    } catch (_) { return true; }
})()
