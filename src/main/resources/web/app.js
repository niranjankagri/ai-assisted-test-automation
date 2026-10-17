// LeaveDesk UI: plain JavaScript, no build step. The token lives in sessionStorage, so a reload keeps
// the user logged in but a new browser context (every test) starts logged out.
'use strict';

const $ = (testId) => document.querySelector(`[data-testid="${testId}"]`);
let me = null;

async function api(method, path, body) {
  const headers = { 'Content-Type': 'application/json' };
  const token = sessionStorage.getItem('token');
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

function show(testId, text) {
  const el = $(testId);
  el.textContent = text;
  el.hidden = false;
}

function hide(...testIds) {
  testIds.forEach((id) => { $(id).hidden = true; });
}

async function login(event) {
  event.preventDefault();
  hide('login-error');
  try {
    const data = await api('POST', '/api/login', {
      username: document.getElementById('username').value,
      password: document.getElementById('password').value,
    });
    sessionStorage.setItem('token', data.token);
    await loadDashboard();
  } catch (err) {
    show('login-error', err.message);
  }
}

function logout() {
  sessionStorage.removeItem('token');
  me = null;
  $('dashboard-view').hidden = true;
  $('login-view').hidden = false;
  hide('current-user', 'logout');
}

async function loadDashboard() {
  me = await api('GET', '/api/me');
  $('login-view').hidden = true;
  $('dashboard-view').hidden = false;
  show('current-user', `${me.displayName} (${me.role === 'MANAGER' ? 'Manager' : 'Employee'})`);
  $('logout').hidden = false;
  // Managers decide on requests; they don't file their own in this demo.
  $('leave-form').hidden = me.role === 'MANAGER';
  $('annual-remaining').textContent = me.annualRemaining;
  await renderRows();
}

async function renderRows() {
  const leaves = await api('GET', '/api/leaves');
  const tbody = $('leave-rows');
  tbody.replaceChildren();
  $('empty-list').hidden = leaves.length > 0;
  for (const l of leaves) {
    const tr = document.createElement('tr');
    tr.dataset.testid = `leave-row-${l.id}`;
    for (const value of [l.id, l.owner, l.type, l.from, l.to, l.days]) {
      const td = document.createElement('td');
      td.textContent = value;
      tr.append(td);
    }
    const status = document.createElement('td');
    status.innerHTML = `<span class="badge ${l.status.toLowerCase()}" data-testid="leave-status-${l.id}">${l.status}</span>`;
    tr.append(status);
    const actions = document.createElement('td');
    if (l.status === 'PENDING' && me.role === 'MANAGER' && l.owner !== me.username) {
      actions.append(actionButton('Approve', `approve-${l.id}`, () => act(l.id, 'approve')));
      actions.append(actionButton('Reject', `reject-${l.id}`, () => act(l.id, 'reject')));
    }
    if (l.status === 'PENDING' && l.owner === me.username) {
      actions.append(actionButton('Cancel', `cancel-${l.id}`, () => act(l.id, 'cancel')));
    }
    tr.append(actions);
    tbody.append(tr);
  }
}

function actionButton(label, testId, onClick) {
  const b = document.createElement('button');
  b.type = 'button';
  b.textContent = label;
  b.dataset.testid = testId;
  b.addEventListener('click', onClick);
  return b;
}

async function act(id, action) {
  await api('POST', `/api/leaves/${id}/${action}`);
  await loadDashboard();
}

async function submitLeave(event) {
  event.preventDefault();
  hide('leave-error', 'leave-success');
  try {
    const leave = await api('POST', '/api/leaves', {
      type: document.getElementById('leave-type').value,
      from: document.getElementById('leave-from').value,
      to: document.getElementById('leave-to').value,
      reason: document.getElementById('leave-reason').value,
    });
    document.getElementById('leave-form').reset();
    await loadDashboard();
    show('leave-success', `Request #${leave.id} submitted (${leave.days} working days)`);
  } catch (err) {
    show('leave-error', err.message);
  }
}

document.getElementById('login-form').addEventListener('submit', login);
document.getElementById('leave-form').addEventListener('submit', submitLeave);
$('logout').addEventListener('click', logout);
if (sessionStorage.getItem('token')) loadDashboard().catch(logout);
