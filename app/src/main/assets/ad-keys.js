(revision, pageUrl = '', stop = false) => {
    try {
        let state = window.__xviAdKeys;
        // The last pass is over: nothing reads what the observer would collect until the next one starts it again.
        if (stop) {
            if (state && state.observer && state.watching) { state.observer.disconnect(); state.watching = false; }
            return '{}';
        }
        if (!state || state.revision !== revision || state.pageUrl !== pageUrl) {
            if (state && state.observer) state.observer.disconnect();
            // Under the same filter, a new address (a single-page site moving on) keeps the old rules on
            // screen until the new ones are in, so hidden ads do not flash back meanwhile.
            const keepOld = state && state.revision === revision;
            if (state && state.previous) state.previous.remove();
            if (state && state.sheet && !keepOld) state.sheet.remove();
            const previous = keepOld ? state.sheet || null : null;
            state = { revision, pageUrl, seen: new Set(), rules: new Set(), roots: new Set(), attributes: new Set(), walk: null, sent: false,
                sheet: null, previous, watching: false, observed: false };
            state.observer = new MutationObserver(records => {
                for (const record of records) {
                    if (record.type === 'attributes') state.attributes.add(record.target);
                    else for (const node of record.addedNodes) if (node.nodeType === 1) state.roots.add(node);
                }
                for (const node of state.roots) if (!node.isConnected) state.roots.delete(node);
                for (const node of state.attributes) if (!node.isConnected) state.attributes.delete(node);
                // A page that keeps changing after our last pass must not retain detached subtrees.
                if (state.roots.size + state.attributes.size > 512) {
                    state.roots.clear();
                    state.attributes.clear();
                    state.walk = null;
                    if (document.documentElement) state.roots.add(document.documentElement);
                }
            });
            window.__xviAdKeys = state;
        }
        if (!state.watching) {
            // The first time, and after a pause in which changes went unseen, the whole page is walked;
            // keys already sent are not sent again.
            state.walk = null;
            if (document.documentElement) state.roots.add(document.documentElement);
            state.observer.observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['id', 'class'] });
            state.watching = true;
        }
        // Keep a batch until native has applied it; cancellation or a window transfer can retry it.
        if (state.batch) return JSON.stringify(state.batch);
        const keys = [];
        const collect = element => {
            if (!element.isConnected) return;
            const add = key => { if (!state.seen.has(key)) { state.seen.add(key); keys.push(key); } };
            if (element.id) add('#' + element.id);
            if (element.classList) for (const name of element.classList) add('.' + name);
        };
        // Keep each renderer task and native message bounded, continuing from the last node.
        for (let count = 0; count < 2000 && keys.length < 4000; count++) {
            if (state.attributes.size) {
                const element = state.attributes.values().next().value;
                state.attributes.delete(element);
                collect(element);
                continue;
            }
            if (!state.walk) {
                const root = state.roots.values().next().value;
                if (!root) break;
                state.roots.delete(root);
                if (!root.isConnected) continue;
                state.walk = document.createTreeWalker(root, 1);
                collect(root);
            } else {
                const element = state.walk.nextNode();
                if (element) collect(element);
                else state.walk = null;
            }
        }
        state.batch = { keys, first: !state.sent, more: !!state.walk || state.roots.size > 0 || state.attributes.size > 0 };
        return JSON.stringify(state.batch);
    } catch (_) { return '{}'; }
}
