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

function memoryFixture(disk, ingest) {
  const session = {messages: []};
  const seen = [];
  const chat = {ingest: async (...args) => {
    seen.push(args);return ingest(session,...args);
  }};
  const stores = new Map([['chat',chat],['chat-session',session]]);
  const window = {localStorage: {getItem: k => disk.get(k), setItem: (k,v) => disk.set(k,v)}};
  vm.runInNewContext(script,{window,document:{
    querySelector:()=>({__vue_app__:{config:{globalProperties:{$pinia:{_s:stores}}}}}),
    querySelectorAll:()=>[]},Promise,Map,WeakSet});
  return {api:window.__nomiAiriVoice,chat,seen,window,session};
}
async function memoryRegression() {
  const disk=new Map();let sequence=0;
  const reply=async (session,text) => {
    session.messages.push({role:'user',content:text},
      {id:String(++sequence),role:'assistant',content:[{type:'text',text:'你叫小林。'}]});
    return {ok:true};
  };
  const first=memoryFixture(disk,reply);
  const options={model:'unchanged'};
  assert.deepEqual(await first.chat.ingest('我叫小林',options,'session-1'),{ok:true});
  assert.equal(first.api.memoryInfo().count,1);
  assert.equal(first.seen[0][1],options);assert.equal(first.seen[0][2],'session-1');
  const reopened=memoryFixture(disk,reply);
  await reopened.chat.ingest('我叫什么名字',options);
  assert.ok(reopened.seen[0][0].includes('我叫小林'));
  assert.ok(reopened.seen[0][0].endsWith('用户现在说：我叫什么名字'));
  await reopened.chat.ingest('聊点别的',options);
  assert.equal(reopened.seen[1][0],'聊点别的');
  assert.equal(reopened.api.memoryInfo().count,3);
  reopened.api.setMemory('false');
  await reopened.chat.ingest('不要记录',options);
  assert.equal(reopened.api.memoryInfo().count,3);
  assert.equal(reopened.seen[2][0],'不要记录');
  reopened.api.clearMemory();
  assert.equal(memoryFixture(disk,reply).api.memoryInfo().count,0);
  let finish;
  const delayed=memoryFixture(disk,async session=>{
    await new Promise(resolve=>{finish=resolve});
    session.messages.push({id:'late',role:'assistant',content:'迟到的回答'});
  });
  delayed.api.setMemory('true');
  const pending=delayed.chat.ingest('迟到的问题',options);
  delayed.api.clearMemory();finish();await pending;
  assert.equal(delayed.api.memoryInfo().count,0);
  const failed=memoryFixture(disk,async()=>{throw new Error('network')});
  await assert.rejects(failed.chat.ingest('未完成的对话',options));
  assert.equal(failed.api.memoryInfo().count,0);
  const stale=memoryFixture(disk,async()=>{});
  stale.session.messages.push({id:'old',role:'assistant',content:'旧答案'});
  await stale.chat.ingest('没有新回答',options);
  assert.equal(stale.api.memoryInfo().count,0);
  const broken=memoryFixture(new Map(),reply);
  broken.window.localStorage.setItem=()=>{throw new Error('quota')};
  await broken.chat.ingest('存储失败',options);
  assert.equal(broken.api.memoryInfo().storageError,true);
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
  await memoryRegression();
  console.log('PASS: AIRI local memory persistence, reload recall, options preservation, disabled/clear/late/failure safeguards;  native AIRI send once, microphone ownership, reply gating, WebAudio tracking, missing config and failure handling');
})().catch(error => { console.error(error); process.exitCode = 1; });
