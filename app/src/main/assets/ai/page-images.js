// Called as (mode, key): 'list' names the page's pictures cheaply; 'preview' and 'data' read one of
// them, by the key 'list' gave it, from pixels the page has already loaded.
(mode, key) => {
    const inline = /^data:image\/(png|jpeg|webp|gif);base64,/i;
    const shown = image => image.currentSrc || image.src || '';
    // Lazy loaders show a placeholder until the picture scrolls into view and keep the real address
    // in an attribute; offer that address, which the app downloads when its pixels are not loaded.
    const lazy = image => {
        if (!image.getAttribute) return '';
        for (const name of ['data-src', 'data-original', 'data-actualsrc', 'data-lazy-src', 'data-lazy', 'data-echo', 'data-url', 'data-srcset']) {
            let value = (image.getAttribute(name) || '').trim();
            if (name === 'data-srcset') value = value.split(',').pop().trim().split(/\s+/)[0] || '';
            if (!value || /^data:/i.test(value)) continue;
            try { return new URL(value, document.baseURI || undefined).href; } catch (_) { /* Not an address. */ }
        }
        return '';
    };
    const source = image => {
        const current = shown(image);
        const real = lazy(image);
        const placeholder = !current || /^data:/i.test(current) || !image.naturalWidth || image.naturalWidth < 80 || image.naturalHeight < 80;
        return real && real !== current && placeholder ? real : current;
    };
    const loaded = image => !!image.naturalWidth && source(image) === shown(image);
    // Inline pictures can be megabytes long, so their key is their length and a sample of the data.
    const keyOf = url => /^https?:\/\//i.test(url) ? url : 'inline:' + url.length + ':' + url.slice(22, 70) + url.slice(-32);
    const size = image => {
        if (loaded(image)) return [image.naturalWidth, image.naturalHeight];
        // A picture that has not loaded yet still has its laid-out or declared size.
        const box = image.getBoundingClientRect ? image.getBoundingClientRect() : { width: 0, height: 0 };
        if (box.width && box.height) return [Math.round(box.width), Math.round(box.height)];
        const attribute = name => parseInt(image.getAttribute ? image.getAttribute(name) || '' : '', 10) || 0;
        return [attribute('width') || attribute('data-w'), attribute('height')];
    };
    const find = wanted => Array.from(document.images).find(image => {
        const url = source(image);
        return url && keyOf(url) === wanted;
    });
    const draw = (image, side, type, quality) => {
        if (!image || !loaded(image)) return '';
        try {
            // Cross-origin pictures refuse canvas reads; the app then downloads them itself.
            const scale = Math.min(1, side / Math.max(image.naturalWidth, image.naturalHeight));
            const canvas = document.createElement('canvas');
            canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
            canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
            const context = canvas.getContext('2d');
            context.fillStyle = '#fff';
            context.fillRect(0, 0, canvas.width, canvas.height);
            context.drawImage(image, 0, 0, canvas.width, canvas.height);
            return canvas.toDataURL(type, quality);
        } catch (_) { return ''; }
    };
    if (mode === 'preview') return draw(find(key), 320, 'image/jpeg', 0.75);
    if (mode === 'data') {
        const image = find(key);
        if (!image) return '';
        const url = source(image);
        return inline.test(url) ? url : draw(image, 2560, 'image/jpeg', 0.92);
    }
    const seen = new Set();
    const found = [];
    for (const image of document.images) {
        const url = source(image);
        if (!url || url.length > 4000000) continue;
        if (!/^https?:\/\//i.test(url) && !inline.test(url)) continue;
        const [width, height] = size(image);
        // A lazy picture of unknown size, hidden in a carousel for example, is still offered.
        const unknown = !width && !height && url !== shown(image);
        if (!unknown && (width < 80 || (height && height < 80))) continue;
        const id = keyOf(url);
        if (seen.has(id)) continue;
        seen.add(id);
        const main = !!(image.closest && image.closest('article, main, [role="main"]'));
        found.push({ key: id, url: inline.test(url) ? '' : url, name: (image.alt || image.title || '').trim().slice(0, 120),
            area: width * height, main, order: found.length });
    }
    // The article's pictures come first in reading order; the rest, often logos and ads, by size.
    found.sort((a, b) => (b.main - a.main) || (a.main ? a.order - b.order : b.area - a.area || a.order - b.order));
    return JSON.stringify(found.slice(0, 30).map(({ key, url, name }) => ({ key, url, name })));
}
