'use strict';
const $ = id => document.getElementById(id);
const state = {
    auth: '',
    user: '',
    path: [],
    rows: [],
    query: '',
    filter: '',
    after: null,
    loading: false,
    generation: 0,
    uploading: false
};
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[c]));
const parent = () => state.path.at(-1)?.id ?? null;
const bytes = n => {
    n = Number(n || 0);
    const i = n ? Math.min(4, Math.floor(Math.log(n) / Math.log(1024))) : 0;
    return `${(n / 1024 ** i).toFixed(i ? 1 : 0)} ${['B', 'KB', 'MB', 'GB', 'TB'][i]}`;
};
const date = n => new Date(n).toLocaleDateString(undefined, {month: 'short', day: 'numeric', year: 'numeric'});

function message(text = '') {
    $('message').textContent = text;
}

async function api(path, options = {}) {
    const headers = {'Authorization': state.auth, ...options.headers};
    if (options.body !== undefined) {
        headers['Content-Type'] = 'application/json';
        options.body = JSON.stringify(options.body);
    }
    const response = await fetch('/api/v1' + path, {...options, headers, cache: 'no-store'});
    if (!response.ok) {
        let error;
        try {
            error = await response.json();
        } catch {
        }
        const e = new Error(error?.detail || ({
            401: 'Your session could not be authenticated. Sign in again.',
            403: 'You don’t have permission for this action.',
            412: 'This item changed. Refresh and try again.',
            503: 'Storage is temporarily unavailable. Please retry.'
        }[response.status]) || `Request failed (${response.status}).`);
        e.status = response.status;
        throw e;
    }
    return response.status === 204 ? null : response.text().then(t => t ? JSON.parse(t) : null);
}

function dialog(title, content) {
    $('dialog-body').onclick = null;
    $('dialog-title').textContent = title;
    $('dialog-body').innerHTML = content;
    if (!$('dialog').open) $('dialog').showModal();
}

$('close-dialog').onclick = () => $('dialog').close();

function formDialog(title, content, action) {
    dialog(title, `<form id="modal-form">${content}<div class="dialog-error" id="modal-error" role="alert"></div><button class="primary" type="submit">Continue</button></form>`);
    $('modal-form').onsubmit = async e => {
        e.preventDefault();
        const button = e.submitter;
        button.disabled = true;
        try {
            await action(new FormData(e.target));
        } catch (error) {
            $('modal-error').textContent = error.message;
        } finally {
            button.disabled = false;
        }
    };
}

function login() {
    formDialog('Welcome to your drive', `<p>A calmer place for everything you’re working on. Connect with your backend credentials.</p><label>Sign-in method<select name="method" id="auth-method"><option value="basic">Local account</option><option value="token">Access token (OIDC)</option></select></label><label id="user-field">Username<input name="user" value="alice" autocomplete="username" required></label><label><span id="secret-label">Password</span><input name="secret" type="password" autocomplete="current-password" required></label>`, async data => {
        const old = state.auth;
        state.auth = data.get('method') === 'token' ? 'Bearer ' + data.get('secret').trim() : 'Basic ' + btoa(unescape(encodeURIComponent(data.get('user') + ':' + data.get('secret'))));
        try {
            const profile = await api('/me');
            state.user = profile.subject;
            state.path = [];
            state.query = '';
            $('search').value = '';
            $('identity').textContent = profile.subject;
            $('dialog').close();
            profileView(profile);
            await load();
        } catch (e) {
            state.auth = old;
            throw e;
        }
    });
    $('auth-method').onchange = e => {
        const token = e.target.value === 'token';
        $('user-field').hidden = token;
        $('user-field').querySelector('input').required = !token;
        $('secret-label').textContent = token ? 'Access token' : 'Password';
    };
}

function profileView(p) {
    const total = Number(p.used) + Number(p.reserved) + Number(p.quarantined || 0);
    $('quota').value = p.quota ? total / Number(p.quota) * 100 : 0;
    $('percent').textContent = Math.round($('quota').value) + '%';
    $('storage-text').textContent = `${bytes(p.used)} of ${bytes(p.quota)} used` + (Number(p.reserved) ? ` · ${bytes(p.reserved)} pending` : '') + (Number(p.quarantined) ? ` · ${bytes(p.quarantined)} quarantined` : '');
}

async function refreshProfile() {
    try {
        profileView(await api('/me'));
    } catch (e) {
        message(e.message);
    }
}

function breadcrumbs() {
    $('breadcrumbs').innerHTML = `<button data-level="0">My drive</button>` + state.path.map((p, i) => `<span aria-hidden="true">/</span><button data-level="${i + 1}">${esc(p.name)}</button>`).join('');
}

function render() {
    breadcrumbs();
    $('count').textContent = `${state.rows.length} item${state.rows.length === 1 ? '' : 's'}`;
    $('files').innerHTML = state.rows.length ? state.rows.map(row => `<article class="file-row" data-kind="${esc(row.kind)}"><div class="file-name"><span class="file-icon" aria-hidden="true">${row.kind === 'FOLDER' ? '▰' : '▤'}</span><button class="name-button" data-action="open" data-id="${row.id}">${esc(row.name)}</button></div><span class="file-date">${date(row.updated)}</span><span class="file-kind">${row.kind === 'FOLDER' ? 'Folder' : (row.name.split('.').length > 1 ? esc(row.name.split('.').at(-1).toUpperCase()) : 'File')}</span><div class="actions"><button data-action="rename" data-id="${row.id}" aria-label="Rename ${esc(row.name)}" title="Rename">✎</button><button data-action="share" data-id="${row.id}" aria-label="Share ${esc(row.name)}" title="Share">♧</button><button data-action="details" data-id="${row.id}" aria-label="Details for ${esc(row.name)}" title="Details and actions">•••</button></div></article>`).join('') : `<div class="empty"><div class="file-icon">${state.query ? '⌕' : '▰'}</div><h2>${state.query ? 'No matching files' : 'A fresh space for your files'}</h2><p>${state.query ? 'Try another name or file type.' : 'Upload your first file or create a folder.<br>You can also drop files here.'}</p></div>`;
}

async function load(append = false) {
    if (!state.auth) {
        login();
        return;
    }
    const generation = ++state.generation;
    state.loading = true;
    message('Loading your files…');
    $('more').disabled = true;
    const params = new URLSearchParams({limit: '100'});
    const search = state.query || state.filter;
    if (parent()) params.set(search ? 'folder' : 'parent', parent());
    if (state.query) params.set('q', state.query);
    if (state.filter) params.set('type', state.filter);
    if (append && state.after) params.set('after', state.after);
    try {
        const rows = await api((search ? '/search?' : '/files?') + params);
        if (generation !== state.generation) return;
        state.rows = append ? [...state.rows, ...rows] : rows;
        state.after = rows.at(-1)?.id;
        render();
        $('more').hidden = rows.length < 100;
        message();
    } catch (e) {
        if (generation === state.generation) {
            message(e.message);
            if (!append) {
                state.rows = [];
                $('files').innerHTML = '<div class="empty"><h2>Unable to load files</h2><p>Check your connection and use Refresh to try again.</p></div>';
            }
        }
    } finally {
        if (generation === state.generation) state.loading = false;
        $('more').disabled = false;
    }
}

function requireAuth(fn) {
    return () => state.auth ? fn() : login();
}

$('account').onclick = () => {
    if (!state.auth) return login();
    dialog('Your account', `<p>Signed in as <strong>${esc(state.user)}</strong>. Credentials stay in memory for this tab only.</p><button class="secondary" id="signout">Sign out</button>`);
    $('signout').onclick = () => {
        if (state.uploading) {
            message('Wait for the current upload to finish before signing out.');
            return;
        }
        state.auth = '';
        state.user = '';
        state.rows = [];
        state.path = [];
        ++state.generation;
        $('identity').textContent = 'Connect your drive';
        $('storage-text').textContent = 'Sign in to view storage';
        $('quota').value = 0;
        $('percent').textContent = '—';
        render();
        login();
    };
};
$('home').onclick = () => {
    state.path = [];
    state.query = '';
    $('search').value = '';
    load();
};
$('breadcrumbs').onclick = e => {
    const button = e.target.closest('[data-level]');
    if (button) {
        state.path = state.path.slice(0, Number(button.dataset.level));
        state.query = '';
        $('search').value = '';
        load();
    }
};
$('refresh').onclick = () => {
    load();
    if (state.auth) refreshProfile();
};
$('search-form').onsubmit = e => {
    e.preventDefault();
    state.query = $('search').value.trim();
    load();
};
$('search').onsearch = () => {
    if (!$('search').value) {
        state.query = '';
        load();
    }
};
$('filter').onchange = e => {
    state.filter = e.target.value;
    load();
};
$('more').onclick = () => load(true);
for (const mode of ['list', 'grid']) $(mode + '-view').onclick = () => {
    $('dropzone').classList.toggle('grid', mode === 'grid');
    for (const m of ['list', 'grid']) $(m + '-view').setAttribute('aria-pressed', m === mode);
};
$('folder').onclick = requireAuth(() => formDialog('Create a folder', '<label>Folder name<input name="name" maxlength="255" required autofocus placeholder="e.g. Brand projects"></label>', async data => {
    await api('/folders', {
        method: 'POST',
        headers: {'Idempotency-Key': crypto.randomUUID()},
        body: {parent: parent(), name: data.get('name').trim()}
    });
    $('dialog').close();
    await load();
}));

async function download(row) {
    const result = await api(`/files/${row.id}/download`);
    const a = document.createElement('a');
    a.href = result.url;
    a.rel = 'noopener noreferrer';
    a.download = row.name;
    a.click();
}

async function mutate(row, action, extra = {}) {
    await api(`/files/${row.id}`, {
        method: 'PATCH',
        headers: {'Idempotency-Key': crypto.randomUUID()},
        body: {expected: row.revision, action, ...extra}
    });
    $('dialog').close();
    await load();
    await refreshProfile();
}

$('files').onclick = async e => {
    const button = e.target.closest('[data-action]');
    if (!button) return;
    const row = state.rows.find(r => r.id === button.dataset.id);
    if (!row) return;
    try {
        switch (button.dataset.action) {
            case 'open':
                if (row.kind === 'FOLDER') {
                    state.path.push(row);
                    state.query = '';
                    $('search').value = '';
                    await load();
                } else await download(row);
                break;
            case 'rename':
                formDialog('Rename item', `<label>Name<input name="name" value="${esc(row.name)}" maxlength="255" required autofocus></label>`, async data => mutate(row, 'rename', {name: data.get('name').trim()}));
                break;
            case 'share':
                showShares(row);
                break;
            case 'details':
                dialog(row.name, `<p>${row.kind === 'FOLDER' ? 'Folder' : 'File'} · Revision ${row.revision}<br>Modified ${date(row.updated)}</p>${row.kind === 'FILE' ? '<button class="secondary" id="versions">Version history</button> ' : ''}<button class="secondary" id="move">Move to folder</button> <button class="secondary" id="delete">Delete</button>`);
                if (row.kind === 'FILE') $('versions').onclick = () => versions(row);
                $('move').onclick = () => formDialog('Move item', '<p>Enter a destination folder ID, or leave blank to move to the root of your drive.</p><label>Folder ID<input name="parent" pattern="[a-fA-F0-9-]{36}" placeholder="Root of My drive"></label>', async data => mutate(row, 'move', {parent: data.get('parent') || null}));
                $('delete').onclick = () => formDialog('Delete this item?', `<p>Delete <strong>${esc(row.name)}</strong>${row.kind === 'FOLDER' ? ' and its contents' : ''}? It will disappear from your drive.</p>`, async () => mutate(row, 'delete'));
                break;
        }
    } catch (error) {
        message(error.message);
    }
};

async function versions(row) {
    try {
        const list = await api(`/files/${row.id}/versions`);
        dialog('Version history', `<p>${esc(row.name)} · Latest ${list.length} versions</p>` + list.map(v => `<div class="version"><span>${date(v.created)} · ${bytes(v.size)}${v.id === row.current_version ? ' · Current' : ''}</span>${v.id !== row.current_version ? `<button class="quiet" data-restore="${v.id}">Restore</button>` : ''}</div>`).join(''));
        $('dialog-body').onclick = async e => {
            const button = e.target.closest('[data-restore]');
            if (!button) return;
            button.disabled = true;
            try {
                await api(`/files/${row.id}/versions/${button.dataset.restore}/restore`, {
                    method: 'POST',
                    body: {expected: row.revision}
                });
                $('dialog').close();
                await load();
                await refreshProfile();
            } catch (error) {
                message(error.message);
            } finally {
                button.disabled = false;
            }
        };
    } catch (e) {
        message(e.message);
    }
}

async function showShares(row) {
    try {
        const shares = await api(`/files/${row.id}/shares`);
        formDialog('Share ' + row.name, `<p>Give someone access using their account subject identifier.</p><label>Account subject<input name="subject" required placeholder="e.g. bob"></label><label>Access<select name="role"><option>VIEWER</option><option>EDITOR</option></select></label><div>${shares.map(s => `<div class="version"><span>${esc(s.subject || 'Share link')} · ${esc(s.role || 'Link')}</span><button type="button" class="quiet" data-revoke="${s.id}">Revoke</button></div>`).join('')}</div>`, async data => {
            await api(`/files/${row.id}/shares`, {
                method: 'POST',
                body: {subject: data.get('subject').trim(), role: data.get('role')}
            });
            await showShares(row);
        });
        $('dialog-body').onclick = async e => {
            const button = e.target.closest('[data-revoke]');
            if (!button) return;
            button.disabled = true;
            try {
                await api(`/files/${row.id}/shares/${button.dataset.revoke}`, {method: 'DELETE'});
                await showShares(row);
            } catch (error) {
                $('modal-error').textContent = error.message;
                button.disabled = false;
            }
        };
    } catch (error) {
        message(error.message);
    }
}

const digest = async blob => btoa(String.fromCharCode(...new Uint8Array(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer()))));
$('upload').onclick = requireAuth(() => $('file-input').click());
$('file-input').onchange = e => {
    uploadFiles([...e.target.files]);
    e.target.value = '';
};
for (const event of ['dragenter', 'dragover']) $('dropzone').addEventListener(event, e => {
    e.preventDefault();
    $('dropzone').classList.add('dragging');
});
for (const event of ['dragleave', 'drop']) $('dropzone').addEventListener(event, e => {
    e.preventDefault();
    $('dropzone').classList.remove('dragging');
});
$('dropzone').addEventListener('drop', e => {
    if (!state.auth) return login();
    uploadFiles([...e.dataTransfer.files]);
});

async function uploadFiles(files) {
    if (state.uploading) {
        message('An upload is already in progress. Please wait.');
        return;
    }
    if (!files.length) return;
    state.uploading = true;
    $('upload').disabled = true;
    const destination = parent();
    $('transfer').hidden = false;
    for (const file of files) {
        let session;
        try {
            // Web Crypto hashes the complete buffer; cap browser uploads to bound memory use.
            if (file.size > 100 * 1024 * 1024) throw new Error('Browser uploads are limited to 100 MB per file. Use the sync client for larger files.');
            $('transfer').textContent = `Preparing ${file.name}…`;
            session = await api('/uploads', {
                method: 'POST',
                headers: {'Idempotency-Key': crypto.randomUUID()},
                body: {
                    parent: destination,
                    name: file.name,
                    node: null,
                    expected: 0,
                    size: file.size,
                    sha256: await digest(file)
                }
            });
            const count = Math.max(1, Math.ceil(file.size / Number(session.part_size)));
            for (let i = 0; i < count; i++) {
                const part = file.slice(i * session.part_size, Math.min(file.size, (i + 1) * session.part_size));
                $('transfer').textContent = `Uploading ${file.name} · part ${i + 1} of ${count}`;
                const signed = await api(`/uploads/${session.id}/parts`, {
                    method: 'POST',
                    body: {number: i + 1, sha256: await digest(part)}
                });
                const headers = {};
                for (const [key, value] of Object.entries(signed.headers)) {
                    if (key.toLowerCase() !== 'host') headers[key] = Array.isArray(value) ? value.join(',') : value;
                }
                const response = await fetch(signed.url, {method: 'PUT', headers, body: part, credentials: 'omit'});
                if (!response.ok) throw new Error(`Storage upload failed (${response.status}).`);
            }
            $('transfer').textContent = `Verifying ${file.name}…`;
            await api(`/uploads/${session.id}/complete`, {method: 'POST'});
            for(let attempt=0;attempt<3600;attempt++) {
                const status=await api(`/uploads/${session.id}`);
                if(status.state==='COMPLETED')break;
                if(status.state!=='COMPLETING')throw new Error(`Completion ${status.state}`);
                await new Promise(resolve=>setTimeout(resolve,1000));
                if(attempt===3599)throw new Error('Completion is still pending; recover the same session.');
            }
        } catch (error) {
            let cleanup = '';
            if (session) {
                try {
                    await api(`/uploads/${session.id}/abort`, {method: 'POST'});
                } catch {
                    cleanup = ` Upload ${session.id} could not be canceled; check its status before retrying.`;
                }
            }
            message(`${file.name}: ${error.message}${cleanup}`);
            break;
        }
    }
    state.uploading = false;
    $('upload').disabled = false;
    $('transfer').hidden = true;
    const lastMessage = $('message').textContent;
    await load();
    if (lastMessage) message(lastMessage);
    await refreshProfile();
}

$('dialog').addEventListener('close', () => {
    $('dialog-body').onclick = null;
    $('dialog-body').replaceChildren();
});
render();
login();
