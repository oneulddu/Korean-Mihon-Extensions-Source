import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

// Execute the actual injected script, rather than a second implementation of its URL rules.
const source = readFileSync(new URL('../src/main/kotlin/eu/kanade/tachiyomi/extension/ko/ntk/NTKBase.kt', import.meta.url), 'utf8');
const script = source.match(/private val imageBridgeScript = """([\s\S]*?)"""\.trimIndent\(\)/)[1];

function capture(nodes, expectedCount = nodes.length) {
  let tick;
  let payload;
  const context = {
    URL,
    document: {
      querySelector: () => ({
        getAttribute: () => String(expectedCount),
        querySelectorAll: () => nodes,
      }),
    },
    window: {
      location: { href: 'https://sbxh9.com/manhwa/1/2', pathname: '/manhwa/1/2' },
      setInterval: fn => { tick = fn; return 1; },
      clearInterval: () => {},
      TrojanTunnel: { exfiltrateApi: value => { payload = JSON.parse(value); } },
    },
  };
  vm.runInNewContext(script, context);
  tick();
  return payload;
}

const image = (currentSrc, src, lazy, alt = '') => ({
  currentSrc,
  getAttribute: name => ({ src, 'data-src': lazy, alt })[name],
});

test('data/blob placeholders fall back to valid data-src in the production bridge', () => {
  const payload = capture([
    image('data:image/png;base64,AA', 'blob:https://sbxh9.com/placeholder', '/pages/1.webp'),
    image('blob:https://sbxh9.com/other', 'javascript:void(0)', '//cdn.example/2.webp'),
  ]);
  assert.deepEqual(payload.images, [
    { src: 'https://sbxh9.com/pages/1.webp', page: 1 },
    { src: 'https://cdn.example/2.webp', page: 2 },
  ]);
});

test('bridge does not report complete images while placeholders remain', () => {
  assert.equal(capture([
    image('https://cdn.example/1.webp', '', ''),
    image('data:image/png;base64,AA', '', 'javascript:bad()'),
  ]), undefined);
});
