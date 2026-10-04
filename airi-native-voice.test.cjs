const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/airi-native-voice.js', 'utf8');
function fixture() {
  let calls = 0, stopped = 0, finish;
  const chat = {sending: false, ingest: async (text, options) => {
    calls++; assert.equal(text, '你好'); assert.equal(options.model, 'test-model');
    await new Promise(resolve => { finish = resolve; });
  }};
  const mind = {activeProvider: 'test-provider', activeModel: 'test-model',
    getChatProviderInstance: async () => ({name: 'provider'})};
  const microphone = {enabled: true, stopStream: () => { stopped++; }};
  const speaking = {nowSpeaking: false};
  const map = new Map([['chat', chat], ['consciousness', mind], ['settings-audio-devices', microphone],
    ['character-speaking', speaking], ['hearing-store', {activeTranscriptionProvider: ''}]]);
  class Source extends EventTarget { start() {} }
  const window = {AudioBufferSourceNode: Source, speechSynthesis: {speaking: false}};
  const context = {window, document: {querySelector: () => ({__vue_app__: {config: {globalProperties: {$pinia: {_s: map}}}}}),
    querySelectorAll: () => []}, Promise, Map, WeakSet};
  vm.runInNewContext(script, context);
  return {api: window.__nomiAiriVoice, chat, mind, microphone, speaking, Source,
    calls: () => calls, stopped: () => stopped, finish: () => finish(), context};
}
const tick = () => new Promise(resolve => setImmediate(resolve));
(async () => {
  const f = fixture();
  assert.equal(f.api.prepare().state, 'ready');
  assert.equal(f.microphone.enabled, false); assert.equal(f.stopped(), 1);
  assert.equal(f.api.diagnose().webSpeech, false); // Native input works without pretending Web Speech is present.
  assert.equal(f.api.send('你好', 'turn-1').state, 'accepted');
  assert.equal(f.api.send('你好', 'turn-1').state, 'accepted');
  await tick(); assert.equal(f.calls(), 1); assert.equal(f.api.poll('', 'turn-1').state, 'pending');
  f.finish(); await tick(); assert.equal(f.api.poll('', 'turn-1').state, 'done');
  f.speaking.nowSpeaking = true;
  assert.equal(f.api.poll('', 'turn-1').state, 'busy');
  assert.equal(f.api.prepare().state, 'busy');
  assert.equal(f.api.activity().state, 'busy');
  assert.equal(f.api.send('你好', 'turn-2').state, 'error'); assert.equal(f.calls(), 1);
  f.speaking.nowSpeaking = false;
  const node = new f.Source(); node.start();
  assert.equal(f.api.activity().state, 'busy');
  node.dispatchEvent(new Event('ended')); assert.equal(f.api.activity().state, 'ready');
  vm.runInNewContext(script, f.context); // Re-injection must not wrap audio a second time or lose job IDs.
  assert.equal(f.api.send('你好', 'turn-1').state, 'accepted'); assert.equal(f.calls(), 1);
  assert.equal(f.api.poll('', 'missing').state, 'error');
  const missing = fixture(); missing.mind.activeModel = '';
  assert.throws(() => missing.api.prepare(), /思维设置/); assert.equal(missing.stopped(), 0);
  const broken = fixture(); broken.mind.getChatProviderInstance = async () => { throw new Error('private provider details'); };
  broken.api.send('你好', 'failed'); await tick();
  assert.equal(broken.api.poll('', 'failed').state, 'error');
  assert.ok(!broken.api.poll('', 'failed').message.includes('private'));
  console.log('PASS: native AIRI send once, microphone ownership, reply gating, WebAudio tracking, missing config and failure handling');
})().catch(error => { console.error(error); process.exitCode = 1; });
