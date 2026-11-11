/*
 * Runs user scripts in a page. Registered as the first document-start script, before each script's
 * own registration, those of what the scripts stored, and a last one that removes the hook again and
 * starts the scripts, so page scripts never see it: every document-start script runs before any of the
 * page's. Scripts and values may arrive in any order before that; scripts run in the order they came.
 *
 * WebView has no isolated world, so scripts share the page's: unsafeWindow is window. What the page
 * must not reach (the bridge, the token, stored values) stays in this closure, and the built-ins used
 * later are taken now, before the page can replace them. The patterns come compiled from the app.
 *
 * Whatever needs the app (stored values, requests to other sites, tabs, the clipboard, downloads and
 * menu commands) goes through the bridge as JSON; answers come back on the same bridge.
 */
(function (config) {
    'use strict';
    // Page scripts may later replace any built-in, including Function.prototype.call, so methods used
    // after document start are taken now and called through Reflect.apply.
    const apply = Reflect.apply;
    const uncurry = fn => (self, ...args) => apply(fn, self, args);
    const own = uncurry(Object.prototype.hasOwnProperty);
    try { if (own(window, config.hook)) return; } catch (_) { return; }
    const create = Object.create, defineOwn = Object.defineProperty, Str = String, Num = Number;
    const mapGet = uncurry(Map.prototype.get), mapSet = uncurry(Map.prototype.set), mapDelete = uncurry(Map.prototype.delete), mapEach = uncurry(Map.prototype.forEach);
    const promiseThen = uncurry(Promise.prototype.then), settled = Promise.resolve, rejected = Promise.reject;
    const subarray = uncurry(Uint8Array.prototype.subarray), charCode = uncurry(String.prototype.charCodeAt), upper = uncurry(String.prototype.toUpperCase);
    const matches = uncurry(RegExp.prototype.test);
    const parse = JSON.parse, stringify = JSON.stringify, keys = Object.keys, freeze = Object.freeze, assign = Object.assign;
    const listen = EventTarget.prototype.addEventListener;
    const Pattern = RegExp, Resolved = Promise, later = setTimeout, Observer = window.MutationObserver, Address = URL;
    const Bytes = Uint8Array, Buffer = ArrayBuffer, isView = ArrayBuffer.isView;
    const BlobType = window.Blob, Form = window.FormData, Params = window.URLSearchParams, Body = window.Response;
    const Parser = window.DOMParser, Decoder = window.TextDecoder;
    const encode64 = window.btoa && window.btoa.bind(window), decode64 = window.atob && window.atob.bind(window), fromChar = String.fromCharCode;
    const out = window.console || { log() {}, error() {} };
    const log = out.log, report = out.error;
    const complain = (s, error) => { try { apply(report, out, ['[' + s.name + ']', error]); } catch (_) {} };
    let bridge = null, post = null;
    try {
        bridge = config.bridge && window[config.bridge];
        if (bridge && typeof bridge.postMessage === 'function') post = bridge.postMessage.bind(bridge);
        if (config.bridge) delete window[config.bridge];
    } catch (_) {}

    let address = '';
    try { address = Str(location.href).split('#')[0]; } catch (_) {}
    let top = true;
    try { top = window.top === window; } catch (_) { top = false; }
    const test = list => list.some(rule => { try { return new Pattern(rule[0], rule[1]).test(address); } catch (_) { return false; } });
    const runs = s => /^https?:/i.test(address) && test(s.run) && !test(s.skip) && (top || !s.noFrames);
    /**
     * A copy without prototypes, so no setter or toJSON the page put on Object.prototype sees the token or
     * what is sent. Each script signs with its own token, so one leaked token speaks for one script only.
     */
    const bare = value => {
        if (!value || typeof value !== 'object') return value;
        const copy = create(null), names = keys(value);
        for (let i = 0; i < names.length; i++) copy[names[i]] = bare(value[names[i]]);
        return copy;
    };
    const tokens = config.tokens || {};
    const send = message => {
        if (!post) return;
        try {
            const out = bare(message);
            out.k = own(tokens, out.s) ? tokens[out.s] : '';
            post(stringify(out));
        } catch (_) {}
    };
    /** A plain value handed to a promise: an own `then` keeps the lookup off a getter the page planted. */
    const unthenable = value => {
        if (value && typeof value === 'object') try { if (!own(value, 'then')) defineOwn(value, 'then', { value: undefined, configurable: true }); } catch (_) {}
        return value;
    };
    const resolved = value => apply(settled, Resolved, [unthenable(value)]);
    const resolve = url => { try { return new Address(Str(url), address).href; } catch (_) { return ''; } };
    const web = url => matches(/^https?:/i, url);
    let seq = 0;

    // Answers from the app, by kind.
    const requests = new Map(), menus = new Map(), notes = new Map(), changes = new Map();
    if (bridge) bridge.onmessage = event => {
        let m;
        try { m = parse(event.data); } catch (_) { return; }
        const handler = m.t === 'xhr' ? mapGet(requests, m.id) : m.t === 'menu' ? mapGet(menus, m.s + '\n' + m.id)
            : m.t === 'notify' ? mapGet(notes, m.id) : m.t === 'value' ? mapGet(changes, m.s) : null;
        if (handler) handler(m);
    };

    const toBase64 = bytes => {
        let text = '';
        for (let i = 0; i < bytes.length; i += 0x8000) text += apply(fromChar, null, subarray(bytes, i, i + 0x8000));
        return encode64(text);
    };
    const fromBase64 = text => {
        const raw = decode64(text), bytes = new Bytes(raw.length);
        for (let i = 0; i < raw.length; i++) bytes[i] = charCode(raw, i);
        return bytes;
    };

    const whenParsed = run => {
        if (document.readyState === 'loading') apply(listen, document, ['DOMContentLoaded', run, { once: true }]);
        else run();
    };

    const addStyle = css => {
        const style = document.createElement('style');
        style.textContent = Str(css);
        const root = document.head || document.documentElement;
        if (root) root.appendChild(style);
        else if (Observer) {
            // Nothing to attach to yet at document start; the first element will do.
            const watcher = new Observer(() => {
                const parent = document.head || document.documentElement;
                if (parent) { watcher.disconnect(); parent.appendChild(style); }
            });
            watcher.observe(document, { childList: true, subtree: true });
        }
        return style;
    };

    /** A request body as the app takes it: text, or bytes in base64, with the type it implies. */
    const body = data => {
        if (data === undefined || data === null) return resolved(null);
        if (typeof data === 'string') return resolved({ text: data });
        if (Params && data instanceof Params) return resolved({ text: Str(data), type: 'application/x-www-form-urlencoded;charset=UTF-8' });
        if (data instanceof Buffer) return resolved({ base64: toBase64(new Bytes(data)) });
        if (isView(data)) return resolved({ base64: toBase64(new Bytes(data.buffer, data.byteOffset, data.byteLength)) });
        if (BlobType && data instanceof BlobType) return promiseThen(data.arrayBuffer(), b => unthenable({ base64: toBase64(new Bytes(b)), type: data.type || null }));
        if (Form && Body && data instanceof Form) {
            const encoded = new Body(data);
            return promiseThen(encoded.arrayBuffer(), b => unthenable({ base64: toBase64(new Bytes(b)), type: encoded.headers.get('content-type') }));
        }
        return resolved({ text: Str(data) });
    };

    /** The response object scripts get, shaped by the response type they asked for. */
    const response = (m, details) => {
        const type = details.responseType || '';
        const mime = details.overrideMimeType || m.mime || '';
        let text = m.text === undefined || m.text === null ? '' : m.text, value = text;
        if (m.base64 !== undefined && m.base64 !== null) {
            const bytes = fromBase64(m.base64);
            value = type === 'blob' && BlobType ? new BlobType([bytes], { type: mime }) : bytes.buffer;
            text = '';
        } else if (type === 'json') {
            try { value = parse(text); } catch (_) { value = null; }
        } else if (type === 'document') {
            try { value = new Parser().parseFromString(text, /xml/i.test(mime) ? 'text/xml' : 'text/html'); } catch (_) { value = null; }
        }
        const result = { status: m.status, statusText: m.statusText || '', readyState: 4, finalUrl: m.finalUrl || '', responseHeaders: m.headers || '',
            response: value, responseText: text, context: details.context };
        if (type === 'document') result.responseXML = value;
        return result;
    };

    const failure = (details, error) => ({ status: 0, statusText: '', readyState: 4, finalUrl: '', responseHeaders: '', response: null, responseText: '',
        error, context: details.context });

    /** GM_xmlhttpRequest: the app makes the request, after checking the script may reach the site. */
    const request = (id, s, details) => {
        details = details || {};
        const key = ++seq;
        let finished = false;
        const call = (name, result) => {
            const fn = details[name];
            if (typeof fn === 'function') try { apply(fn, details, [result]); } catch (error) { complain(s, error); }
        };
        const finish = (event, result) => {
            if (finished) return;
            finished = true;
            mapDelete(requests, key);
            if (event === 'load') { call('onreadystatechange', result); call('onload', result); }
            else call('on' + event, result);
            call('onloadend', result);
        };
        const control = {
            abort() {
                if (finished) return;
                send({ s: id, op: 'abort', id: key });
                finish('abort', failure(details, 'aborted'));
            },
        };
        const url = resolve(details.url);
        if (!post || !web(url)) {
            later(() => finish('error', failure(details, post ? 'unsupported address' : 'unavailable')), 0);
            return control;
        }
        mapSet(requests, key, m => m.event === 'load' ? finish('load', response(m, details)) : finish(m.event, failure(details, m.error || m.event)));
        promiseThen(body(details.data), data => {
            if (finished) return;
            const headers = create(null);
            keys(details.headers || {}).forEach(name => { headers[name] = Str(details.headers[name]); });
            send({ s: id, op: 'xhr', id: key, method: upper(Str(details.method || 'GET')), url, headers, data,
                timeout: Num(details.timeout) || 0, anonymous: !!details.anonymous,
                binary: details.responseType === 'arraybuffer' || details.responseType === 'blob' });
        }, () => finish('error', failure(details, 'unreadable body')));
        return control;
    };

    /** What the script gets, by name; the wrapper's parameters pick from it in the app's order. */
    const api = (id, s) => {
        // No prototype, so a key such as "__proto__" is just a key.
        const values = Object.create(null);
        keys(s.values || {}).forEach(key => { values[key] = s.values[key]; });
        const decode = text => { try { return parse(text); } catch (_) { return undefined; } };
        const read = (key, fallback) => {
            key = Str(key);
            if (!own(values, key)) return fallback;
            try { return parse(values[key]); } catch (_) { return fallback; }
        };
        const watchers = new Map();
        const changed = (key, before, after, remote) => mapEach(watchers, watcher => {
            if (watcher.key === key) try { watcher.fn(key, before, after, remote); } catch (error) { complain(s, error); }
        });
        const remove = key => {
            key = Str(key);
            if (!own(values, key)) return;
            const before = decode(values[key]);
            delete values[key];
            send({ s: id, op: 'delete', key });
            changed(key, before, undefined, false);
        };
        const write = (key, value) => {
            key = Str(key);
            let text;
            try { text = stringify(value); } catch (_) { return; }
            if (text === undefined) return remove(key);
            if (values[key] === text) return;
            const before = own(values, key) ? decode(values[key]) : undefined;
            values[key] = text;
            send({ s: id, op: 'set', key, value: text });
            changed(key, before, decode(text), false);
        };
        // The same script changed a value in another page.
        mapSet(changes, id, m => {
            const key = Str(m.key), had = own(values, key);
            if (m.value === null || m.value === undefined) {
                if (!had) return;
                const before = decode(values[key]);
                delete values[key];
                changed(key, before, undefined, true);
            } else if (values[key] !== m.value) {
                const before = had ? decode(values[key]) : undefined;
                values[key] = m.value;
                changed(key, before, decode(m.value), true);
            }
        });
        let listening = false;
        const addValueChangeListener = (key, fn) => {
            const handle = ++seq;
            if (typeof fn === 'function') mapSet(watchers, handle, { key: Str(key), fn });
            if (!listening) { listening = true; send({ s: id, op: 'listen' }); }
            return handle;
        };
        const removeValueChangeListener = handle => { mapDelete(watchers, handle); };
        const list = () => keys(values);

        const registerMenuCommand = (name, fn, options) => {
            const handle = Str(options && typeof options === 'object' && options.id !== undefined ? options.id : ++seq);
            if (typeof fn === 'function') mapSet(menus, id + '\n' + handle, () => { try { fn(); } catch (error) { complain(s, error); } });
            send({ s: id, op: 'menu', id: handle, name: Str(name) });
            return handle;
        };
        const unregisterMenuCommand = handle => {
            if (mapDelete(menus, id + '\n' + handle)) send({ s: id, op: 'unmenu', id: Str(handle) });
        };
        const openInTab = (url, options) => {
            const target = resolve(url);
            if (!web(target)) return null;
            // As in Tampermonkey, a new tab stays in the background unless asked to be active.
            const background = typeof options === 'boolean' ? options : !(options && options.active);
            send({ s: id, op: 'open', url: target, background });
            return { closed: false, close() {}, onclose: null };
        };
        const setClipboard = data => send({ s: id, op: 'clip', text: Str(data) });
        const notification = function (details, second, third, fourth) {
            if (typeof details !== 'object' || details === null) details = { text: details, title: typeof second === 'string' ? second : '', onclick: fourth };
            else if (typeof second === 'function') details = assign({}, details, { ondone: second });
            const handle = ++seq;
            const click = typeof details.onclick === 'function', done = typeof details.ondone === 'function';
            if (click || done) mapSet(notes, handle, m => {
                try {
                    if (m.event === 'click' && click) details.onclick();
                    if (m.event === 'done') { mapDelete(notes, handle); if (done) details.ondone(); }
                } catch (error) { complain(s, error); }
            });
            send({ s: id, op: 'notify', id: handle, title: Str(details.title || s.name), text: Str(details.text || ''), clickable: click });
        };
        const download = (details, name) => {
            if (typeof details !== 'object' || details === null) details = { url: details, name };
            const url = resolve(details.url);
            if (!web(url)) {
                if (typeof details.onerror === 'function') later(() => { try { details.onerror({ error: 'not_supported' }); } catch (error) { complain(s, error); } }, 0);
            } else send({ s: id, op: 'download', url, name: details.name ? Str(details.name) : null });
            return { abort() {} };
        };
        const resources = s.resources || {};
        const resource = name => own(resources, Str(name)) ? resources[Str(name)] : null;
        const getResourceText = name => {
            const found = resource(name);
            if (!found) return null;
            try { return new Decoder('utf-8').decode(fromBase64(found.data)); } catch (_) { return null; }
        };
        const getResourceURL = name => { const found = resource(name); return found ? 'data:' + found.mime + ';base64,' + found.data : null; };

        const info = freeze({
            script: freeze({
                name: s.name, namespace: s.namespace, version: s.version, description: s.description, author: s.author,
                matches: s.matches, includes: s.includes, excludes: s.excludes, grant: s.grants, 'run-at': s.runAt, noframes: s.noFrames,
                resources: keys(resources).map(name => ({ name, mimetype: resources[name].mime })),
            }),
            scriptMetaStr: s.block,
            scriptHandler: config.handler.name,
            version: config.handler.version,
        });
        const xmlhttpRequest = details => request(id, s, details);
        const promised = fn => function () { try { return resolved(apply(fn, null, arguments)); } catch (error) { return apply(rejected, Resolved, [error]); } };
        const GM = freeze({
            info,
            getValue: promised(read), setValue: promised(write), deleteValue: promised(remove), listValues: promised(list),
            addStyle: promised(addStyle), addValueChangeListener: promised(addValueChangeListener), removeValueChangeListener: promised(removeValueChangeListener),
            registerMenuCommand: promised(registerMenuCommand), unregisterMenuCommand: promised(unregisterMenuCommand),
            openInTab: promised(openInTab), setClipboard: promised(setClipboard), notification: promised(notification), download: promised(download),
            getResourceUrl: promised(getResourceURL), getResourceURL: promised(getResourceURL), getResourceText: promised(getResourceText),
            xmlHttpRequest: details => {
                let control;
                const answer = new Resolved((done, fail) => {
                    const settle = (name, then) => function (result) { if (details && typeof details[name] === 'function') apply(details[name], this, [result]); then(unthenable(result)); };
                    control = request(id, s, assign({}, details, {
                        onload: settle('onload', done), onerror: settle('onerror', fail), ontimeout: settle('ontimeout', fail), onabort: settle('onabort', fail),
                    }));
                });
                answer.abort = () => control.abort();
                return answer;
            },
        });
        return {
            GM_info: info, GM, GM_getValue: read, GM_setValue: write, GM_deleteValue: remove, GM_listValues: list, GM_addStyle: addStyle,
            GM_log: function () { apply(log, out, arguments); }, unsafeWindow: window,
            GM_addValueChangeListener: addValueChangeListener, GM_removeValueChangeListener: removeValueChangeListener,
            GM_xmlhttpRequest: xmlhttpRequest, GM_registerMenuCommand: registerMenuCommand, GM_unregisterMenuCommand: unregisterMenuCommand,
            GM_openInTab: openInTab, GM_setClipboard: setClipboard, GM_notification: notification, GM_download: download,
            GM_getResourceText: getResourceText, GM_getResourceURL: getResourceURL,
        };
    };

    /** Scripts handed over so far, in order, and what each stored; nothing runs until [start]. */
    const defined = [], stored = Object.create(null);
    let started = false;
    const define = (id, s, fn) => { if (!started && s && typeof s === 'object' && typeof fn === 'function') defined.push({ id: Str(id), s, fn }); };
    const values = (id, v) => { if (!started && v && typeof v === 'object') stored[Str(id)] = v; };
    const start = () => {
        if (started) return;
        started = true;
        for (const { id, s, fn } of defined) {
            if (!runs(s)) continue;
            s.values = own(stored, id) ? stored[id] : {};
            const all = api(id, s);
            const args = config.params.map(name => all[name]);
            const run = () => { try { apply(fn, window, args); } catch (error) { complain(s, error); } };
            if (s.runAt === 'document-start') run();
            else if (s.runAt === 'document-end') whenParsed(run);
            else whenParsed(() => later(run, 0));
        }
    };

    Object.defineProperty(window, config.hook, { value: freeze({ define, values, start }), configurable: true });
})
