// Optional Android speech input adapter. Runs only in the configured main page.
// Based on moeru-ai/airi chat/consciousness/audio-device store APIs, inspected 2026-10-01.
(() => {
  if (window.__nomiAiriVoice) return;
  const jobs = new Map();
  let activeAudio = 0;
  const nodes = new WeakSet();
  const audioPrototype = window.AudioBufferSourceNode?.prototype;
  if (audioPrototype) {
    const originalStart = audioPrototype.start;
    audioPrototype.start = function (...args) {
      const result = originalStart.apply(this, args);
      if (!nodes.has(this)) {
        nodes.add(this); activeAudio++;
        this.addEventListener('ended', () => { activeAudio = Math.max(0, activeAudio - 1); }, {once: true});
      }
      return result;
    };
  }
  const value = x => x && typeof x === 'object' && 'value' in x ? x.value : x;
  function stores() {
    const root = document.querySelector('#app');
    const app = root?.__vue_app__;
    const pinia = app?.config?.globalProperties?.$pinia;
    const registry = pinia?._s;
    if (!registry?.get) throw new Error('AIRI 网页接口尚未准备好，请刷新或使用网页听觉设置');
    return {
      chat: registry.get('chat') || registry.get('chat-store'),
      mind: registry.get('consciousness') || registry.get('consciousness-store'),
      hearing: registry.get('hearing-store'),
      microphone: registry.get('settings-audio-devices'),
      speaking: registry.get('character-speaking'),
    };
  }
  function engine() {
    const state = stores();
    if (!state.chat?.ingest || !state.mind?.getChatProviderInstance)
      throw new Error('当前 AIRI 网页不兼容手机听觉入口，请使用网页听觉设置');
    if (!value(state.mind.activeProvider) || !value(state.mind.activeModel))
      throw new Error('请先在 AIRI 思维设置中选择聊天服务和模型');
    return state;
  }
  function busy(state) {
    return Array.from(jobs.values()).some(job => job.state === 'pending')
      || !!value(state.chat?.sending) || !!value(state.speaking?.nowSpeaking)
      || activeAudio > 0 || !!window.speechSynthesis?.speaking
      || Array.from(document.querySelectorAll('audio,video')).some(e => !e.paused && !e.ended);
  }
  function prepare() {
    const state = engine();
    if (busy(state)) return {state: 'busy'};
    // One microphone owner: do not run webpage ASR alongside native Android ASR.
    if (state.microphone) {
      state.microphone.enabled = false;
      state.microphone.stopStream?.();
    }
    return {state: 'ready'};
  }
  function send(text, id) {
    if (jobs.has(id)) return {state: 'accepted'};
    const state = engine();
    if (busy(state)) return {state: 'error', message: 'AIRI 还在回复，请等回复结束后再说'};
    if (!text.trim()) return {state: 'error', message: '没有识别到文字'};
    if (jobs.size >= 12) jobs.delete(jobs.keys().next().value);
    const job = {state: 'pending'};
    jobs.set(id, job);
    Promise.resolve().then(async () => {
      const provider = await state.mind.getChatProviderInstance(value(state.mind.activeProvider));
      await state.chat.ingest(text, {
        model: value(state.mind.activeModel), chatProvider: provider,
        temperature: value(state.mind.activeTemperature), topP: value(state.mind.activeTopP),
      });
      job.state = 'done';
    }).catch(() => {
      job.state = 'error'; job.message = 'AIRI 请求失败，请检查聊天服务配置和网页错误提示';
    });
    return {state: 'accepted'};
  }
  function poll(_text, id) {
    const job = jobs.get(id);
    if (!job) return {state: 'error', message: '网页已刷新，请重新开始手机听觉'};
    if (job.state !== 'done') return job;
    return {state: busy(stores()) ? 'busy' : 'done'};
  }
  function diagnose() {
    let state = {};
    try { state = stores(); } catch (_) {}
    return {
      webSpeech: !!(window.SpeechRecognition || window.webkitSpeechRecognition),
      providerSelected: !!value(state.hearing?.activeTranscriptionProvider),
      chatConfigured: !!value(state.mind?.activeProvider) && !!value(state.mind?.activeModel),
    };
  }
  function activity() { return {state: busy(stores()) ? 'busy' : 'ready'}; }
  function disableWebMic() {
    try { const state = stores(); if (state.microphone) { state.microphone.enabled = false; state.microphone.stopStream?.(); } } catch (_) {}
  }
  window.__nomiAiriVoice = {prepare, send, poll, diagnose, activity, disableWebMic};
})();
