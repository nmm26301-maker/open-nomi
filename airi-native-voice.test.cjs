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

function memoryFixture(disk, ingest, modern=false) {
  const threads=new Map([['session-1',[]]]);const generations=new Map();
  const session={activeSessionId:'session-1',
    getSessionMessagesIfLoaded:id=>threads.get(id),
    getSessionGeneration:id=>generations.get(id) || 0};
  Object.defineProperty(session,'messages',{get:()=>threads.get(session.activeSessionId),set:v=>threads.set(session.activeSessionId,v)});
  const seen=[];
  async function run(args) {
    seen.push(args);const id=args[2] || session.activeSessionId;
    if(!threads.has(id))threads.set(id,[]);
    return ingest({messages:threads.get(id)},...args);
  }
  const chat={ingest:modern ? async function(text,options,...rest) {
    const supplement=options?.systemPromptSupplement;
    return run([text,options,...rest]);
  } : async function(...args){return run(args);}};
  const stores=new Map([['chat',chat],['chat-session',session]]);
  const window={localStorage:{getItem:k=>disk.get(k),setItem:(k,v)=>disk.set(k,v)}};
  vm.runInNewContext(script,{window,document:{
    querySelector:()=>({__vue_app__:{config:{globalProperties:{$pinia:{_s:stores}}}}}),
    querySelectorAll:()=>[]},Promise,Map,WeakSet});
  return {api:window.__nomiAiriVoice,chat,seen,window,session,threads,generations,stores};
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
  const stale=memoryFixture(disk,async session=>{session.messages.push({role:'user',content:'只有新问题，没有新回答'});});
  stale.session.messages.push({id:'old',role:'assistant',content:'旧答案'});
  await stale.chat.ingest('没有新回答',options);
  assert.equal(stale.api.memoryInfo().count,0);
  const broken=memoryFixture(new Map(),reply);
  broken.window.localStorage.setItem=()=>{throw new Error('quota')};
  await broken.chat.ingest('存储失败',options);
  assert.equal(broken.api.memoryInfo().storageError,true);assert.equal(broken.api.memoryInfo().count,0);
  const remembered=new Map([['open-nomi-airi-memory-v1',JSON.stringify({enabled:true,revision:0,turns:[{u:'我的猫叫奶糖',a:'记住了',t:1}]})]]);
  const modern=memoryFixture(remembered,reply,true);
  const originalOptions={model:'model',systemPromptSupplement:'保留原有工具提示',temperature:0.4};
  await modern.chat.ingest('猫叫什么',originalOptions);
  assert.equal(modern.seen[0][0],'猫叫什么');
  assert.ok(modern.seen[0][1].systemPromptSupplement.includes('奶糖'));
  assert.ok(modern.seen[0][1].systemPromptSupplement.includes('保留原有工具提示'));
  assert.equal(originalOptions.systemPromptSupplement,'保留原有工具提示');
  assert.equal(modern.seen[0][1].temperature,0.4);
  modern.session.activeSessionId='session-2';modern.session.messages=[];
  modern.stores.set('llm-toolset-prompts',{activeToolsetPrompt:'默认工具提示'});
  await modern.chat.ingest('新会话继续聊',{model:'model'});
  assert.equal(modern.seen[1][0],'新会话继续聊');
  assert.ok(modern.seen[1][1].systemPromptSupplement.includes('默认工具提示'));
  assert.ok(modern.seen[1][1].systemPromptSupplement.includes('历史资料'));
  let attempts=0;
  const retry=memoryFixture(remembered,async (...args)=>{
    if(++attempts===1)throw new Error('first failed');return reply(...args);
  });
  await assert.rejects(retry.chat.ingest('第一次失败',{}));
  await retry.chat.ingest('重新问',{});
  assert.ok(retry.seen[1][0].includes('历史资料'));
  let settle;
  const switched=memoryFixture(new Map(),async session=>{
    await new Promise(r=>{settle=r});session.messages.push({id:'owned',role:'assistant',content:'原会话答案'});
  });
  const turn=switched.chat.ingest('原会话问题',{});
  switched.session.activeSessionId='other';switched.session.messages=[{id:'other',role:'assistant',content:'别的会话答案'}];
  settle();await turn;
  assert.equal(switched.api.memoryInfo().recent[0].a,'原会话答案');
  let resolve;
  const reset=memoryFixture(new Map(),async session=>{
    await new Promise(r=>{resolve=r});session.messages.push({id:'late',role:'assistant',content:'已清除会话的答案'});
  });
  const late=reset.chat.ingest('旧会话问题',{});
  reset.generations.set('session-1',1);resolve();await late;
  assert.equal(reset.api.memoryInfo().count,0);
  const interrupted=memoryFixture(new Map(),async session=>{
    session.messages.push({id:'partial',role:'assistant',interrupted:true,content:'不完整回答'});
  });
  await interrupted.chat.ingest('被打断的问题',{});assert.equal(interrupted.api.memoryInfo().count,0);
  const emoji=memoryFixture(new Map(),reply);
  await emoji.chat.ingest('中'.repeat(1199)+'😀',{});
  const raw=JSON.parse(emoji.window.localStorage.getItem('open-nomi-airi-memory-v1'));
  assert.ok(!/[\uD800-\uDBFF]$/.test(raw.turns[0].u));

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
