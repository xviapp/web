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

/** Answers as models write them, with the blocks that make cutting a stream risky: lists, code and formulas across blank lines. */
const STREAMED = [
    '# 标题\n\n第一段，含 $x^2$ 和 **粗体**。\n\n第二段\n第二段续行\n\n---\n\n结尾\n',
    '- 一\n- 二\n\n段落\n\n1. 甲\n\n2. 乙\n\n   缩进的续段\n\n3. 丙\n\n2024年的事\n\n2024. 不是年份\n',
    '前文\n\n```js\nconst a = 1;\n\nconst b = 2;\n```\n\n~~~\n~~~~\n\n仍在代码里\n~~~~\n\n之后\n',
    '公式：\n\n$$\nx^2\n\ny^2\n$$\n\n\\[\na\n\n\\]\n\n$$\\frac{1}{2}$$\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n表格之后\n',
    '> 引用\n\n> 另一段引用\n\n- 列表\n  - 嵌套\n\n    更深的续段\n\n正文\n\n```\n未结束的代码\n\n还在里面',
    '- 列表里的代码\n\n  ```\n  code\n\n  more\n  ```\n\n末尾 [链接][x]\n\n[x]: https://example.org\n',
];

test('an answer streamed in settled pieces renders as it does whole', () => {
    for (const answer of STREAMED) {
        let draft = { text: '', html: '' };
        for (let end = 1; end <= answer.length; end++) {
            const text = answer.slice(0, end);
            const cut = context.AiMarkdown.settled(text, draft.text.length);
            assert.ok(cut >= draft.text.length && cut <= text.length);
            if (cut > draft.text.length) draft = { text: text.slice(0, cut), html: draft.html + render(text.slice(draft.text.length, cut)) };
            // A reference defined after its use is the one difference left, and the final rendering redoes the whole answer.
            if (answer.includes('[x]:') && end === answer.length) continue;
            assert.equal(draft.html + render(text.slice(cut)), render(text), JSON.stringify(text));
        }
    }
});

test('code and formula blocks still open hold back the settled part', () => {
    const settled = context.AiMarkdown.settled;
    assert.equal(settled('a\n\nb\n'), 3);
    assert.equal(settled('a\n\nb'), 0, 'the last line may still grow');
    assert.equal(settled('```\na\n\nb\n'), 0);
    assert.equal(settled('$$\na\n\nb\n'), 0);
    assert.equal(settled('- a\n\n- b\n'), 0, 'a list item may continue the list above');
    assert.equal(settled('- a\n\n  b\n'), 0, 'an indented line continues the item above');
});

test('TeX cannot produce links or load anything', () => {
    const html = render('$\\href{javascript:alert(1)}{x}$ $\\url{https://evil.example}$ $\\includegraphics{https://evil.example/a.png}$');
    assert.ok(!html.includes('href="javascript:'));
    assert.ok(!html.includes('href="https://evil.example'));
    assert.ok(!html.includes('<img'));
});
