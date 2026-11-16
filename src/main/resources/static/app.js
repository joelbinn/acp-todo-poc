// Frontend för Chatten. Känner bara till WebSocket-protokollet (POC-specen), aldrig till ACP.
(() => {
  const $ = (id) => document.getElementById(id);
  const messages = $('messages'), statusEl = $('status'), input = $('input'), send = $('send'), stop = $('stop');
  const HIGHLIGHT_MS = 2000, REMOVED_MS = 3000;
  const state = { ready: false, turnRunning: false, agentBubble: null, thoughtBubble: null, plan: null, tools: new Map() };

  function el(tag, className, text) {
    const e = document.createElement(tag);
    if (className) e.className = className;
    if (text !== undefined) e.textContent = text;
    return e;
  }
  function append(node) {
    const stick = messages.scrollHeight - messages.scrollTop - messages.clientHeight < 40;
    messages.appendChild(node);
    if (stick) messages.scrollTop = messages.scrollHeight;
  }
  function setStatus(kind, text) { statusEl.dataset.state = kind; statusEl.textContent = text; }
  function render() {
    input.disabled = !state.ready;
    send.disabled = !state.ready || state.turnRunning;
    stop.disabled = !state.turnRunning;
  }
  // Ny text efter t.ex. ett tool-anrop ska hamna i en ny bubbla.
  function breakStreams() { state.agentBubble = null; state.thoughtBubble = null; }
  function appendStreamed(kind, text) {
    const key = kind === 'agent' ? 'agentBubble' : 'thoughtBubble';
    // Text efter en tanke (eller tvärtom) ska ligga under den, i en ny bubbla.
    state[kind === 'agent' ? 'thoughtBubble' : 'agentBubble'] = null;
    if (!state[key]) { state[key] = el('div', 'bubble ' + kind); append(state[key]); }
    state[key].textContent += text;
    messages.scrollTop = messages.scrollHeight;
  }

  const TOOL_LABELS = { pending: 'väntar', in_progress: 'pågår', completed: 'klar', failed: 'fel' };
  const MAX_RESULT = 2000;

  function toolCard(ev) {
    const details = el('details', 'tool');
    const summary = el('summary');
    summary.append(el('span', 'name', ev.title || ev.toolCallId), el('span', 'badge'));
    const result = el('pre'); result.hidden = true;
    details.append(summary, result);
    state.tools.set(ev.toolCallId, details);
    updateTool(ev.toolCallId, ev.status, null);
    return details;
  }
  function updateTool(id, status, content) {
    const card = state.tools.get(id);
    if (!card) return;
    if (status) { card.dataset.status = status; card.querySelector('.badge').textContent = TOOL_LABELS[status] || status; }
    if (content) { const pre = card.querySelector('pre'); pre.hidden = false; pre.textContent = content.length > MAX_RESULT ? content.slice(0, MAX_RESULT) : content; }
  }

  function renderPlan(entries) {
    if (!state.plan) { state.plan = el('div', 'plan'); state.plan.append(el('h2', null, 'Plan'), el('ul')); append(state.plan); }
    const list = state.plan.querySelector('ul');
    list.replaceChildren(...entries.map((e) => { const li = el('li', null, e.content); li.dataset.status = e.status; return li; }));
  }

  // Uppgiftspanelen: öppna överst, sedan slutförda, båda i id-ordning. Uppdateras bara från händelser.
  const taskList = $('tasks'), tasksEmpty = $('tasks-empty');
  const tasks = new Map(); // id -> { task, row }
  function taskRow(task) {
    const li = el('li');
    li.append(el('span', 'title', task.title));
    li.dataset.status = task.status;
    return li;
  }
  function sortTaskRows() {
    const rank = (t) => (t.status === 'OPEN' ? 0 : 1);
    const rows = [...tasks.values()].sort((a, b) => rank(a.task) - rank(b.task) || a.task.id - b.task.id).map((e) => e.row);
    // Borttagna rader som tonas ut ligger kvar sist tills de försvinner.
    const fading = [...taskList.querySelectorAll('li.removed')];
    taskList.replaceChildren(...rows, ...fading);
    tasksEmpty.hidden = tasks.size > 0 || fading.length > 0;
  }
  function flash(row) {
    row.classList.add('highlight');
    setTimeout(() => row.classList.remove('highlight'), HIGHLIGHT_MS);
  }
  function upsertTask(task) {
    const row = taskRow(task);
    tasks.set(task.id, { task, row });
    return row;
  }
  function removeTask(task) {
    const entry = tasks.get(task.id);
    if (!entry) return;
    tasks.delete(task.id);
    entry.row.classList.add('removed');
    sortTaskRows();
    setTimeout(() => { entry.row.remove(); sortTaskRows(); }, REMOVED_MS);
  }

  // Permission-dialog: en knapp per alternativ. Flera förfrågningar köas och visas en i taget.
  // Esc eller stängd dialog räknas som avböjt.
  const dialog = $('permission');
  const permissionQueue = [];
  function answerPermission(optionId) {
    const current = permissionQueue.shift();
    if (!current) return;
    ws.send(JSON.stringify({ type: 'permission_response', requestId: current.requestId, optionId }));
    renderPermission(true);
  }
  // Knappar märks efter den betrodda kind-uppräkningen, aldrig efter agentens egen text.
  const KIND_LABELS = { allow_once: 'Tillåt en gång', allow_always: 'Tillåt alltid', reject_once: 'Neka en gång', reject_always: 'Neka alltid' };
  const CLICK_GUARD_MS = 700;
  function renderPermission(guard) {
    const current = permissionQueue[0];
    if (!current) { if (dialog.open) dialog.close(); return; }
    $('permission-title').textContent = 'Agenten ber om tillåtelse' + (current.toolKind ? ' (' + current.toolKind + ')' : '');
    $('permission-text').textContent = 'Agenten beskriver det som: ' + current.title;
    const details = $('permission-details');
    details.hidden = !current.details;
    details.textContent = current.details || '';
    $('permission-options').replaceChildren(...current.options.map((o) => {
      const b = el('button', o.kind && o.kind.startsWith('allow') ? 'allow' : 'reject', KIND_LABELS[o.kind] || 'Neka');
      b.type = 'button';
      b.addEventListener('click', () => answerPermission(o.optionId));
      return b;
    }));
    if (!dialog.open) dialog.showModal();
    if (guard) { // ett klick på förra frågan får inte besvara nästa
      const buttons = [...$('permission-options').children];
      buttons.forEach((b) => { b.disabled = true; });
      setTimeout(() => buttons.forEach((b) => { b.disabled = false; }), CLICK_GUARD_MS);
    }
  }
  function showPermission(ev) { permissionQueue.push(ev); renderPermission(); }
  dialog.addEventListener('cancel', (e) => { e.preventDefault(); answerPermission(null); });

  const handlers = {
    session_ready() { state.ready = true; setStatus('ready', 'Redo'); },
    agent_text(ev) { appendStreamed('agent', ev.text); },
    agent_thought(ev) { appendStreamed('thought', ev.text); },
    tool_call(ev) { breakStreams(); append(toolCard(ev)); },
    tool_call_update(ev) { updateTool(ev.toolCallId, ev.status, ev.content); },
    plan(ev) { renderPlan(ev.entries); },
    permission_request(ev) { showPermission(ev); },
    tasks_snapshot(ev) {
      tasks.clear(); taskList.replaceChildren();
      ev.tasks.forEach(upsertTask);
      sortTaskRows();
    },
    task_changed(ev) {
      if (ev.change === 'DELETED') return removeTask(ev.task);
      const row = upsertTask(ev.task);
      sortTaskRows();
      flash(row);
    },
    turn_end(ev) {
      state.turnRunning = false; breakStreams(); state.plan = null;
      append(el('div', 'turn-end', 'Tur slut: ' + ev.stopReason));
    },
    error(ev) {
      state.turnRunning = false; breakStreams();
      append(el('div', 'error', ev.message));
      if (!state.ready) setStatus('error', 'Fel'); // en Tur som fallerar lämnar Chatten användbar
    },
  };

  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws/chat`);
  ws.onopen = () => setStatus('starting', 'Agent startar…');
  ws.onclose = () => { permissionQueue.length = 0; renderPermission(); state.ready = false; state.turnRunning = false; setStatus('closed', 'Frånkopplad'); render(); };
  ws.onmessage = (m) => {
    let ev; try { ev = JSON.parse(m.data); } catch { return; }
    const handler = handlers[ev.type];
    if (handler) handler(ev); // okända typer ignoreras
    render();
  };

  function submit() {
    const text = input.value.trim();
    if (!text || !state.ready || state.turnRunning) return;
    append(el('div', 'bubble user', text));
    ws.send(JSON.stringify({ type: 'prompt', text }));
    input.value = '';
    state.turnRunning = true; state.plan = null; breakStreams();
    render();
  }
  $('composer').addEventListener('submit', (e) => { e.preventDefault(); submit(); });
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); } });
  stop.addEventListener('click', () => ws.send(JSON.stringify({ type: 'cancel' })));
  render();
})();
