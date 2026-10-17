// Internal, read-only eviction probe. No form contents or native APIs are exposed.
(() => {
    const visited = new Set();
    let inspected = 0;
    const inspect = root => {
        if (visited.has(root)) return false;
        visited.add(root);
        if (visited.size > 32) return true;
        // Avoid allocating a NodeList containing the entire DOM under memory pressure.
        const walker = (root.ownerDocument || root).createTreeWalker(root, 1);
        for (let element; (element = walker.nextNode());) {
            if (++inspected > 10000) return true;
            const tag = element.tagName.toLowerCase();
            if ((tag === 'audio' || tag === 'video') && !element.paused && !element.ended) return true;
            if (element.isContentEditable || tag === 'object' || tag === 'embed') return true;
            if (tag === 'input') {
                const type = element.type.toLowerCase();
                if (type === 'checkbox' || type === 'radio') {
                    if (element.checked !== element.defaultChecked) return true;
                } else if (type === 'file') {
                    if (element.files && element.files.length) return true;
                } else if (!['hidden', 'button', 'submit', 'reset', 'image'].includes(type)) {
                    // Controlled forms may update defaultValue while the user is typing.
                    if (element.value !== '' || element.value !== element.defaultValue) return true;
                }
            }
            if (tag === 'textarea' && (element.value !== '' || element.value !== element.defaultValue)) return true;
            if (tag === 'select' && Array.from(element.options).some(option => option.selected !== option.defaultSelected)) return true;
            if (tag === 'iframe' || tag === 'frame') {
                // Cross-origin and still-loading frames cannot be inspected safely.
                const child = element.contentDocument;
                if (!child || child.readyState !== 'complete' || inspect(child)) return true;
            }
            if (element.shadowRoot) {
                if (inspect(element.shadowRoot)) return true;
            } else if (tag.includes('-')) {
                // Most custom elements are plain layout; only form-associated ones hold their own input state.
                const definition = typeof customElements === 'undefined' ? undefined : customElements.get(tag);
                if (definition && definition.formAssociated) return true;
            }
        }
        return false;
    };
    try {
        return { reclaimable: document.readyState === 'complete' && !inspect(document) };
    } catch (_) {
        return { reclaimable: false };
    }
})()
