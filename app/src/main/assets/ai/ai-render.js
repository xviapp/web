'use strict';
/*
 * AI answers rendered as Markdown with formulas. Raw HTML in answers is never rendered (markdown-it
 * `html: false`), KaTeX runs untrusted (no \href, \url or \includegraphics), and buttons and links
 * are plain navigations that the app intercepts: this page has no JavaScript bridge.
 */
(function (global) {
    const ACTION = 'https://appassets.androidplatform.net/action/';
    const labels = { thinking: '', copyCode: '', reasoning: '' };

    /** `$…$`, `$$…$$`, `\(…\)` inline and `$$…$$`, `\[…\]` blocks, the delimiters models write. */
    function math(md, katex) {
        const tex = (source, display) => {
            try {
                return katex.renderToString(source, { displayMode: display, throwOnError: false, output: 'html', strict: 'ignore', trust: false });
            } catch (_) {
                return '<code>' + md.utils.escapeHtml(source) + '</code>';
            }
        };

        // Before `escape`, which would otherwise take `\(` as an escaped bracket.
        md.inline.ruler.before('escape', 'math_inline', (state, silent) => {
            const src = state.src, start = state.pos;
            let open, close;
            if (src.startsWith('\\(', start)) { open = '\\('; close = '\\)'; }
            else if (src.startsWith('$$', start)) { open = close = '$$'; }
            else if (src[start] === '$') { open = close = '$'; }
            else return false;
            const from = start + open.length;
            let end = src.indexOf(close, from);
            if (open === '$') {
                // A single dollar must hug its formula, so prices such as "$5 and $6" stay text.
                if (/\s/.test(src[from] || ' ')) return false;
                while (end !== -1 && (/[\s\\]/.test(src[end - 1]) || /\d/.test(src[end + 1] || ''))) end = src.indexOf('$', end + 1);
            }
            if (end === -1 || end === from) return false;
            if (!silent) {
                const token = state.push('math_inline', 'math', 0);
                token.content = src.slice(from, end);
            }
            state.pos = end + close.length;
            return true;
        });

        md.block.ruler.before('fence', 'math_block', (state, startLine, endLine, silent) => {
            if (state.sCount[startLine] - state.blkIndent >= 4) return false;
            const line = state.src.slice(state.bMarks[startLine] + state.tShift[startLine], state.eMarks[startLine]);
            const close = line.startsWith('$$') ? '$$' : line.startsWith('\\[') ? '\\]' : null;
            if (!close) return false;
            const first = line.slice(2).trimEnd();
            let content = null, last = startLine;
            if (first.length >= close.length && first.endsWith(close)) {
                content = first.slice(0, -close.length);
            } else {
                const parts = [first];
                for (let next = startLine + 1; next < endLine; next++) {
                    const from = state.bMarks[next] + state.tShift[next], to = state.eMarks[next];
                    if (from < to && state.sCount[next] < state.blkIndent) break;
                    const text = state.src.slice(from, to).trimEnd();
                    if (text.endsWith(close)) { parts.push(text.slice(0, -close.length)); content = parts.join('\n'); last = next; break; }
                    parts.push(text);
                }
            }
            // Still streaming: the closing delimiter has not arrived, so the lines stay text for now.
            if (content === null || !content.trim()) return false;
            if (silent) return true;
            state.line = last + 1;
            const token = state.push('math_block', 'math', 0);
            token.block = true;
            token.content = content;
            token.map = [startLine, state.line];
            return true;
        }, { alt: ['paragraph', 'reference', 'blockquote', 'list'] });

        md.renderer.rules.math_inline = (tokens, index) => tex(tokens[index].content, false);
        md.renderer.rules.math_block = (tokens, index) => '<div class="math">' + tex(tokens[index].content, true) + '</div>\n';
    }

    /** `- [ ]` and `- [x]` list items become check marks. */
    function tasks(md) {
        md.core.ruler.after('inline', 'task_list', state => {
            const tokens = state.tokens;
            for (let i = 2; i < tokens.length; i++) {
                if (tokens[i].type !== 'inline' || tokens[i - 1].type !== 'paragraph_open' || tokens[i - 2].type !== 'list_item_open') continue;
                const first = tokens[i].children[0];
                const match = first && first.type === 'text' && /^\[([ xX])\]\s+/.exec(first.content);
                if (!match) continue;
                first.content = first.content.slice(match[0].length);
                const box = new state.Token('html_inline', '', 0);
                box.content = match[1] === ' ' ? '<span class="task">☐</span> ' : '<span class="task done">☑</span> ';
                tokens[i].children.unshift(box);
                tokens[i - 2].attrJoin('class', 'task-item');
            }
        });
    }

    function create(markdownit, katex) {
        const md = markdownit({ html: false, linkify: true, breaks: true, typographer: false });
        math(md, katex);
        tasks(md);
        const escape = md.utils.escapeHtml;
        // Images never load; they show as a link to the picture instead.
        md.renderer.rules.image = (tokens, index, options, env, self) => {
            const token = tokens[index];
            const alt = self.renderInlineAsText(token.children || [], options, env) || token.attrGet('src');
            return '<a class="image" href="' + escape(token.attrGet('src') || '') + '">' + escape(alt) + '</a>';
        };
        const fence = md.renderer.rules.fence;
        md.renderer.rules.fence = (tokens, index, options, env, self) => {
            const codes = env.codes || (env.codes = []);
            codes.push(tokens[index].content);
            const button = env.id === undefined ? '' :
                '<a class="code-copy" href="' + ACTION + 'code/' + env.id + '/' + (codes.length - 1) + '">' + escape(labels.copyCode) + '</a>';
            return '<div class="code">' + button + fence(tokens, index, options, env, self) + '</div>';
        };
        return { render: (text, env = {}) => md.render(text, env) };
    }

    const markdown = create(global.markdownit, global.katex);
    global.AiMarkdown = markdown;
    if (typeof document === 'undefined') return;

    /** Rendered messages by id; code blocks are kept so a copy button can hand their text back. */
    const codes = new Map();
    const log = () => document.getElementById('log');
    const message = id => document.querySelector('[data-id="' + id + '"]');
    const height = () => Math.ceil(log().getBoundingClientRect().height + 16);

    /** Keeps the newest text in view while it streams, unless the reader scrolled up. */
    function follow(change) {
        const root = document.scrollingElement;
        const atEnd = root.scrollTop + root.clientHeight >= root.scrollHeight - 48;
        change();
        if (atEnd) root.scrollTop = root.scrollHeight;
        return height();
    }

    global.AiTranscript = {
        init(options) {
            Object.assign(labels, options.labels);
            const style = document.documentElement.style;
            Object.entries(options.colors).forEach(([name, value]) => style.setProperty('--' + name, value));
            return height();
        },
        add(id, role, text, images = []) {
            return follow(() => {
                const item = document.createElement('div');
                item.className = 'message ' + role;
                item.dataset.id = id;
                const body = document.createElement('div');
                body.className = 'body';
                if (role === 'user') {
                    body.textContent = text;
                    for (const image of images) {
                        // Only inline thumbnails load. A page picture's address stays a link, so
                        // opening a conversation never makes requests to its website.
                        if (/^data:image\/(png|jpeg|webp|gif);base64,[A-Za-z0-9+/=\r\n]+$/.test(image.preview || '')) {
                            const picture = document.createElement('img');
                            picture.src = image.preview;
                            picture.alt = image.name;
                            picture.className = 'attachment';
                            body.append(picture);
                        }
                        if (/^https?:\/\//i.test(image.link || '')) {
                            const link = document.createElement('a');
                            link.href = image.link;
                            link.className = 'attachment-link';
                            link.textContent = image.name;
                            body.append(link);
                        }
                    }
                }
                else { body.textContent = labels.thinking; item.classList.add('waiting'); }
                const actions = document.createElement('div');
                actions.className = 'actions';
                item.append(body, actions);
                log().append(item);
            });
        },
        update(id, text) {
            const item = message(id);
            if (!item) return height();
            return follow(() => {
                const env = { id };
                item.classList.remove('waiting', 'error');
                item.querySelector('.body').innerHTML = markdown.render(text, env);
                codes.set(id, env.codes || []);
            });
        },
        /** The model's thinking, folded above the answer; plain text, never Markdown. */
        reasoning(id, text) {
            const item = message(id);
            if (!item) return height();
            return follow(() => {
                let section = item.querySelector('.reasoning');
                if (!section) {
                    section = document.createElement('details');
                    section.className = 'reasoning';
                    const summary = document.createElement('summary');
                    summary.textContent = labels.reasoning;
                    const body = document.createElement('div');
                    body.className = 'reasoning-text';
                    section.append(summary, body);
                    item.prepend(section);
                }
                section.querySelector('.reasoning-text').textContent = text;
            });
        },
        fail(id, text) {
            const item = message(id);
            if (!item) return height();
            return follow(() => {
                item.classList.remove('waiting');
                item.classList.add('error');
                item.querySelector('.body').textContent = text;
            });
        },
        /** [note] on the left, then one link per [action, label]. */
        actions(id, note, actions) {
            const item = message(id);
            if (!item) return height();
            return follow(() => {
                const bar = item.querySelector('.actions');
                bar.replaceChildren();
                const span = document.createElement('span');
                span.className = 'note';
                span.textContent = note || '';
                bar.append(span);
                actions.forEach(([action, label]) => {
                    const link = document.createElement('a');
                    link.href = ACTION + action + '/' + id;
                    link.textContent = label;
                    bar.append(link);
                });
            });
        },
        remove(id) {
            message(id)?.remove();
            codes.delete(id);
            return height();
        },
        clear() {
            log().replaceChildren();
            codes.clear();
            return height();
        },
        code(id, index) {
            return (codes.get(id) || [])[index] ?? null;
        },
    };
})(globalThis);
