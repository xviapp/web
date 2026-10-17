import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const asset = name => readFileSync(new URL('../app/src/main/assets/ai/' + name, import.meta.url), 'utf8');
// The browser globals the bundles expect; a bare context has none of them.
const context = vm.createContext({ atob, btoa, TextEncoder, TextDecoder, URL, console });
for (const name of ['markdown-it.min.js', 'katex.min.js', 'ai-render.js']) vm.runInContext(asset(name), context, { filename: name });
const render = (text, env = {}) => context.AiMarkdown.render(text, env);

test('common Markdown renders: headings, emphasis, lists, quotes, rules, links and tables', () => {
    const html = render('# 标题\n\n**粗体** *斜体* ~~删除~~\n\n1. 一\n2. 二\n\n- a\n\n> 引用\n\n---\n\n[链接](https://example.org)\n\n| a | b |\n|---|---|\n| 1 | 2 |');
    for (const tag of ['<h1>', '<strong>', '<em>', '<s>', '<ol>', '<ul>', '<blockquote>', '<hr>', '<table>', '<td>2</td>'])
        assert.ok(html.includes(tag), tag);
    assert.ok(html.includes('href="https://example.org"'));
});

test('single newlines become line breaks and bare addresses become links', () => {
    const html = render('第一行\n第二行 https://example.org/a');
    assert.ok(html.includes('<br>'));
    assert.ok(html.includes('<a href="https://example.org/a">'));
});

test('raw HTML and script links from the answer never become markup', () => {
    const html = render('<img src=x onerror=alert(1)><script>alert(2)</script>\n\n[x](javascript:alert(3))');
    assert.ok(!html.includes('<img'));
    assert.ok(!html.includes('<script'));
    assert.ok(!html.includes('href="javascript:'));
});

test('images show as links and never load', () => {
    const html = render('![示意图](https://example.org/a.png)');
    assert.ok(!html.includes('<img'));
    assert.ok(html.includes('<a class="image" href="https://example.org/a.png">示意图</a>'));
});

test('task lists show check marks', () => {
    const html = render('- [ ] 待办\n- [x] 完成');
    assert.ok(html.includes('☐'));
    assert.ok(html.includes('☑'));
    assert.ok(!html.includes('[ ]'));
});

test('code blocks keep their text for the copy button and are not read as math', () => {
    const env = { id: 3 };
    const html = render('```js\nconst price = "$5 and $6";\n```', env);
    assert.deepEqual([...env.codes], ['const price = "$5 and $6";\n']);
    assert.ok(html.includes('action/code/3/0'));
    assert.ok(!html.includes('katex'));
    assert.ok(!render('`$x$`').includes('katex'));
});

test('all four formula delimiters render with KaTeX', () => {
    for (const text of ['质能 $E=mc^2$ 方程', '质能 \\(E=mc^2\\) 方程', '$$\\int_0^1 x\\,dx$$', '\\[\n\\frac{a}{b}\n\\]', '$$\n\\sum_{i=1}^n i\n$$'])
        assert.ok(render(text).includes('class="katex'), text);
    assert.ok(render('$$\\frac{a}{b}$$').includes('katex-display'));
    assert.ok(!render('行内 $x$').includes('katex-display'));
});

test('prices, spaced dollars and unfinished formulas stay text', () => {
    for (const text of ['原价 $5 现价 $3', '$ x $', '$$\n\\frac{a}{b}'])
        assert.ok(!render(text).includes('class="katex'), text);
});

test('formulas inside lists and next to paragraphs render', () => {
    assert.ok(render('- 公式：$a^2+b^2=c^2$').includes('class="katex'));
    assert.ok(render('前文\n$$\nx^2\n$$\n后文').includes('katex-display'));
});

test('bad TeX shows as an error mark instead of breaking the answer', () => {
    const html = render('$\\frac{a}$ 之后的文字');
    assert.ok(html.includes('之后的文字'));
    assert.ok(html.includes('katex-error') || html.includes('<code>'));
});

test('TeX cannot produce links or load anything', () => {
    const html = render('$\\href{javascript:alert(1)}{x}$ $\\url{https://evil.example}$ $\\includegraphics{https://evil.example/a.png}$');
    assert.ok(!html.includes('href="javascript:'));
    assert.ok(!html.includes('href="https://evil.example'));
    assert.ok(!html.includes('<img'));
});
