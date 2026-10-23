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
    const own = Object.prototype.hasOwnProperty;
    try { if (own.call(window, config.hook)) return; } catch (_) { return; }
    const parse = JSON.parse, stringify = JSON.stringify, keys = Object.keys, freeze = Object.freeze, assign = Object.assign;
    const listen = EventTarget.prototype.addEventListener;
    const Pattern = RegExp, Resolved = Promise, later = setTimeout, Observer = window.MutationObserver, Address = URL;
    const Bytes = Uint8Array, Buffer = ArrayBuffer, isView = ArrayBuffer.isView;
    const BlobType = window.Blob, Form = window.FormData, Params = window.URLSearchParams, Body = window.Response;
    const Parser = window.DOMParser, Decoder = window.TextDecoder;
    const encode64 = window.btoa && window.btoa.bind(window), decode64 = window.atob && window.atob.bind(window), fromChar = String.fromCharCode;
    const out = window.console || { log() {}, error() {} };
    const log = out.log, report = out.error;
    const complain = (s, error) => { try { report.call(out, '[' + s.name + ']', error); } catch (_) {} };
    let bridge = null, post = null;
    try {
        bridge = config.bridge && window[config.bridge];
        if (bridge && typeof bridge.postMessage === 'function') post = bridge.postMessage.bind(bridge);
        if (config.bridge) delete window[config.bridge];
    } catch (_) {}

    let address = '';
    try { address = String(location.href).split('#')[0]; } catch (_) {}
    let top = true;
    try { top = window.top === window; } catch (_) { top = false; }
    const test = list => list.some(rule => { try { return new Pattern(rule[0], rule[1]).test(address); } catch (_) { return false; } });
    const runs = s => /^https?:/i.test(address) && test(s.run) && !test(s.skip) && (top || !s.noFrames);
    const send = message => { if (post) try { post(stringify(assign({ k: config.token }, message))); } catch (_) {} };
    const resolve = url => { try { return new Address(String(url), address).href; } catch (_) { return ''; } };
    const web = url => /^https?:/i.test(url);
    let seq = 0;

    // Answers from the app, by kind.
    const requests = new Map(), menus = new Map(), notes = new Map(), changes = new Map();
    if (bridge) bridge.onmessage = event => {
        let m;
        try { m = parse(event.data); } catch (_) { return; }
        const handler = m.t === 'xhr' ? requests.get(m.id) : m.t === 'menu' ? menus.get(m.s + '\n' + m.id)
            : m.t === 'notify' ? notes.get(m.id) : m.t === 'value' ? changes.get(m.s) : null;
        if (handler) handler(m);
    };

    const toBase64 = bytes => {
        let text = '';
        for (let i = 0; i < bytes.length; i += 0x8000) text += fromChar.apply(null, bytes.subarray(i, i + 0x8000));
        return encode64(text);
    };
    const fromBase64 = text => {
        const raw = decode64(text), bytes = new Bytes(raw.length);
        for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
        return bytes;
    };

    const whenParsed = run => {
        if (document.readyState === 'loading') listen.call(document, 'DOMContentLoaded', run, { once: true });
        else run();
    };

    const addStyle = css => {
        const style = document.createElement('style');
        style.textContent = String(css);
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
        if (data === undefined || data === null) return Resolved.resolve(null);
        if (typeof data === 'string') return Resolved.resolve({ text: data });
        if (Params && data instanceof Params) return Resolved.resolve({ text: String(data), type: 'application/x-www-form-urlencoded;charset=UTF-8' });
        if (data instanceof Buffer) return Resolved.resolve({ base64: toBase64(new Bytes(data)) });
        if (isView(data)) return Resolved.resolve({ base64: toBase64(new Bytes(data.buffer, data.byteOffset, data.byteLength)) });
        if (BlobType && data instanceof BlobType) return data.arrayBuffer().then(b => ({ base64: toBase64(new Bytes(b)), type: data.type || null }));
        if (Form && Body && data instanceof Form) {
            const encoded = new Body(data);
            return encoded.arrayBuffer().then(b => ({ base64: toBase64(new Bytes(b)), type: encoded.headers.get('content-type') }));
        }
        return Resolved.resolve({ text: String(data) });
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
            if (typeof fn === 'function') try { fn.call(details, result); } catch (error) { complain(s, error); }
        };
        const finish = (event, result) => {
            if (finished) return;
            finished = true;
            requests.delete(key);
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
        requests.set(key, m => m.event === 'load' ? finish('load', response(m, details)) : finish(m.event, failure(details, m.error || m.event)));
        body(details.data).then(data => {
            if (finished) return;
            const headers = {};
            keys(details.headers || {}).forEach(name => { headers[name] = String(details.headers[name]); });
            send({ s: id, op: 'xhr', id: key, method: String(details.method || 'GET').toUpperCase(), url, headers, data,
                timeout: Number(details.timeout) || 0, anonymous: !!details.anonymous,
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
            key = String(key);
            if (!own.call(values, key)) return fallback;
            try { return parse(values[key]); } catch (_) { return fallback; }
        };
        const watchers = new Map();
        const changed = (key, before, after, remote) => watchers.forEach(watcher => {
            if (watcher.key === key) try { watcher.fn(key, before, after, remote); } catch (error) { complain(s, error); }
        });
        const remove = key => {
            key = String(key);
            if (!own.call(values, key)) return;
            const before = decode(values[key]);
            delete values[key];
            send({ s: id, op: 'delete', key });
            changed(key, before, undefined, false);
        };
        const write = (key, value) => {
            key = String(key);
            let text;
            try { text = stringify(value); } catch (_) { return; }
            if (text === undefined) return remove(key);
            if (values[key] === text) return;
            const before = own.call(values, key) ? decode(values[key]) : undefined;
            values[key] = text;
            send({ s: id, op: 'set', key, value: text });
            changed(key, before, decode(text), false);
        };
        // The same script changed a value in another page.
        changes.set(id, m => {
            const key = String(m.key), had = own.call(values, key);
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
            if (typeof fn === 'function') watchers.set(handle, { key: String(key), fn });
            if (!listening) { listening = true; send({ s: id, op: 'listen' }); }
            return handle;
        };
        const removeValueChangeListener = handle => { watchers.delete(handle); };
        const list = () => keys(values);

        const registerMenuCommand = (name, fn, options) => {
            const handle = String(options && typeof options === 'object' && options.id !== undefined ? options.id : ++seq);
            if (typeof fn === 'function') menus.set(id + '\n' + handle, () => { try { fn(); } catch (error) { complain(s, error); } });
            send({ s: id, op: 'menu', id: handle, name: String(name) });
            return handle;
        };
        const unregisterMenuCommand = handle => {
            if (menus.delete(id + '\n' + handle)) send({ s: id, op: 'unmenu', id: String(handle) });
        };
        const openInTab = (url, options) => {
            const target = resolve(url);
            if (!web(target)) return null;
            // As in Tampermonkey, a new tab stays in the background unless asked to be active.
            const background = typeof options === 'boolean' ? options : !(options && options.active);
            send({ s: id, op: 'open', url: target, background });
            return { closed: false, close() {}, onclose: null };
        };
        const setClipboard = data => send({ s: id, op: 'clip', text: String(data) });
        const notification = function (details, second, third, fourth) {
            if (typeof details !== 'object' || details === null) details = { text: details, title: typeof second === 'string' ? second : '', onclick: fourth };
            else if (typeof second === 'function') details = assign({}, details, { ondone: second });
            const handle = ++seq;
            const click = typeof details.onclick === 'function', done = typeof details.ondone === 'function';
            if (click || done) notes.set(handle, m => {
                try {
                    if (m.event === 'click' && click) details.onclick();
                    if (m.event === 'done') { notes.delete(handle); if (done) details.ondone(); }
                } catch (error) { complain(s, error); }
            });
            send({ s: id, op: 'notify', id: handle, title: String(details.title || s.name), text: String(details.text || ''), clickable: click });
        };
        const download = (details, name) => {
            if (typeof details !== 'object' || details === null) details = { url: details, name };
            const url = resolve(details.url);
            if (!web(url)) {
                if (typeof details.onerror === 'function') later(() => { try { details.onerror({ error: 'not_supported' }); } catch (error) { complain(s, error); } }, 0);
            } else send({ s: id, op: 'download', url, name: details.name ? String(details.name) : null });
            return { abort() {} };
        };
        const resources = s.resources || {};
        const resource = name => own.call(resources, String(name)) ? resources[String(name)] : null;
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
        const promised = fn => function () { try { return Resolved.resolve(fn.apply(null, arguments)); } catch (error) { return Resolved.reject(error); } };
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
                    const settle = (name, then) => function (result) { if (details && typeof details[name] === 'function') details[name].call(this, result); then(result); };
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
            GM_log: function () { log.apply(out, arguments); }, unsafeWindow: window,
            GM_addValueChangeListener: addValueChangeListener, GM_removeValueChangeListener: removeValueChangeListener,
            GM_xmlhttpRequest: xmlhttpRequest, GM_registerMenuCommand: registerMenuCommand, GM_unregisterMenuCommand: unregisterMenuCommand,
            GM_openInTab: openInTab, GM_setClipboard: setClipboard, GM_notification: notification, GM_download: download,
            GM_getResourceText: getResourceText, GM_getResourceURL: getResourceURL,
        };
    };

    /** Scripts handed over so far, in order, and what each stored; nothing runs until [start]. */
    const defined = [], stored = Object.create(null);
    let started = false;
    const define = (id, s, fn) => { if (!started && s && typeof s === 'object' && typeof fn === 'function') defined.push({ id: String(id), s, fn }); };
    const values = (id, v) => { if (!started && v && typeof v === 'object') stored[String(id)] = v; };
    const start = () => {
        if (started) return;
        started = true;
        for (const { id, s, fn } of defined) {
            if (!runs(s)) continue;
            s.values = own.call(stored, id) ? stored[id] : {};
            const all = api(id, s);
            const args = config.params.map(name => all[name]);
            const run = () => { try { fn.apply(window, args); } catch (error) { complain(s, error); } };
            if (s.runAt === 'document-start') run();
            else if (s.runAt === 'document-end') whenParsed(run);
            else whenParsed(() => later(run, 0));
        }
    };

    Object.defineProperty(window, config.hook, { value: freeze({ define, values, start }), configurable: true });
})
