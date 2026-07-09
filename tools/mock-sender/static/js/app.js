/**
 * 智慧路灯 Mock 数据发送器 - 卡片式传感器管理
 * v3 — 纯 MQTT，卡片网格 UI
 */

// ============================ 全局状态 ============================
let sensorList = [];
let historyList = [];
let uptimeSeconds = 0;
let uptimeInterval = null;
let pollInterval = null;
let newHistoryCount = 0;
let currentAutoSendMode = 'algorithm';

const TYPE_ICONS = { light: '☀️', temperature: '🌡️', humidity: '💧', power: '⚡' };
const TYPE_LABELS = { light: '光照', temperature: '温度', humidity: '湿度', power: '功率' };

// ============================ 初始化 ============================
document.addEventListener('DOMContentLoaded', () => {
    loadSensors();
    loadMqttConfig();
    startUptimeTimer();
    startPolling();
    connectLogStream();

    // 模态框关闭时重置状态
    const modal = document.getElementById('addSensorModal');
    if (modal) {
        modal.addEventListener('hidden.bs.modal', () => {
            document.getElementById('editSensorKey').value = '';
            document.getElementById('addSensorForm').reset();
            const btn = document.getElementById('btnAddSensorSubmit');
            if (btn) btn.innerHTML = '<i class="bi bi-plus-lg"></i> 添加传感器';
            const title = document.getElementById('addSensorModalTitle');
            if (title) title.innerHTML = '<i class="bi bi-plus-circle"></i> 添加传感器';
        });
    }
});

// ============================ 传感器卡片渲染 ============================

function loadSensors() {
    fetch('/api/sensors')
        .then(r => r.json())
        .then(data => {
            sensorList = data;
            renderSensorCards(data);
            updateSensorCount(data);
            updateHistoryFilter(data);
        })
        .catch(err => console.error('加载传感器失败:', err));
}

function renderSensorCards(sensors) {
    const grid = document.getElementById('sensorCardGrid');
    const noCard = document.getElementById('noSensorsCard');
    if (!grid) return;

    if (!sensors || sensors.length === 0) {
        grid.innerHTML = '';
        if (noCard) noCard.style.display = '';
        return;
    }
    if (noCard) noCard.style.display = 'none';

    grid.innerHTML = sensors.map(s => buildCard(s)).join('');
    updateHistoryFilter(sensors);
}

function buildCard(s) {
    const typeIcon = TYPE_ICONS[s.sensorType] || '';
    const typeLabel = TYPE_LABELS[s.sensorType] || s.sensorType;
    const isRunning = s.running;
    const autoMode = s.autoSendMode || 'algorithm';
    const deviceGroup = s.deviceGroup || s.deviceName || '';
    const safeKey = escAttr(s.sensorKey);

    let cardStateClass = 'stopped';
    if (isRunning && autoMode === 'fixed') cardStateClass = 'running-fixed';
    else if (isRunning) cardStateClass = 'running-algorithm';

    const statusDotClass = isRunning ? 'running' : 'stopped';
    const statusText = isRunning ? `运行中 (${s.publishCount || 0}次)` : '已停止';
    const modeBadgeClass = autoMode === 'fixed' ? 'fixed' : 'algorithm';
    const modeBadgeText = autoMode === 'fixed' ? '📌 固定内容' : '🔵 算法动态';

    let lastPublishText = '-';
    if (s.lastPublish) {
        const sec = Math.floor((Date.now() / 1000) - s.lastPublish);
        if (sec < 60) lastPublishText = `${sec} 秒前`;
        else if (sec < 3600) lastPublishText = `${Math.floor(sec / 60)} 分钟前`;
        else lastPublishText = `${Math.floor(sec / 3600)} 小时前`;
    }

    return `
    <div class="sensor-card ${cardStateClass}" id="card-${safeKey}">
        <div class="sensor-card-header">
            <span class="sensor-card-icon">${typeIcon}</span>
            <span class="sensor-card-name" title="${escHtml(s.sensorKey)}">${escHtml(s.displayName || s.sensorKey)}</span>
            <div style="position:relative">
                <button class="sensor-card-menu-btn" onclick="toggleCardMenu(event,'${safeKey}')" title="更多操作">
                    <i class="bi bi-three-dots-vertical"></i>
                </button>
                <div class="sensor-card-dropdown" id="menu-${safeKey}">
                    <a href="javascript:void(0)" onclick="editSensor('${safeKey}')"><i class="bi bi-pencil"></i> 编辑传感器</a>
                    <a href="javascript:void(0)" onclick="editMessageTemplate('${safeKey}')"><i class="bi bi-code-slash"></i> 消息模板</a>
                    <a href="javascript:void(0)" onclick="publishOnce('${safeKey}')"><i class="bi bi-send"></i> 手动发送一次</a>
                    <a href="javascript:void(0)" class="text-danger" onclick="removeSensor('${safeKey}')"><i class="bi bi-trash"></i> 删除</a>
                </div>
            </div>
        </div>
        <div class="sensor-card-body">
            ${deviceGroup ? `<div class="mb-2"><span class="device-group-tag"><i class="bi bi-folder"></i> ${escHtml(deviceGroup)}</span></div>` : ''}
            <div class="card-row"><span class="label">类型</span><span class="value">${typeIcon} ${typeLabel}</span></div>
            <div class="card-row"><span class="label">主题</span><span class="value mono">${escHtml(s.dataTopic || '-')}</span></div>
            <div class="card-row"><span class="label">频率</span><span class="value">每 ${s.interval || 5} 秒</span></div>
            <div class="card-row"><span class="label">模式</span><span class="value"><span class="mode-badge ${modeBadgeClass}">${modeBadgeText}</span></span></div>
            <div class="card-row">
                <span class="label">状态</span>
                <span class="value"><span class="card-status-dot ${statusDotClass}"></span>${statusText}</span>
            </div>
            <div class="card-row"><span class="label">最后发送</span><span class="value">${lastPublishText}</span></div>
        </div>
        <div class="sensor-card-footer">
            ${isRunning
                ? `<button class="btn btn-sm btn-outline-secondary flex-fill" onclick="stopSensor('${safeKey}')"><i class="bi bi-stop-fill"></i> 停止</button>`
                : `<button class="btn btn-sm btn-outline-success flex-fill" onclick="startSensor('${safeKey}')"><i class="bi bi-play-fill"></i> 启动</button>`}
            <select class="form-select form-select-sm" style="width:80px;font-size:0.72rem"
                    onchange="changeSensorMode('${safeKey}', this.value)">
                <option value="auto" ${s.controlMode === 'auto' ? 'selected' : ''}>自动</option>
                <option value="manual" ${s.controlMode === 'manual' ? 'selected' : ''}>手动</option>
            </select>
            <button class="btn btn-sm btn-outline-primary" onclick="editSensor('${safeKey}')"
                    data-bs-toggle="modal" data-bs-target="#addSensorModal" title="编辑">
                <i class="bi bi-pencil"></i>
            </button>
            <button class="btn btn-sm btn-outline-danger" onclick="removeSensor('${safeKey}')" title="删除">
                <i class="bi bi-trash"></i>
            </button>
        </div>
    </div>`;
}

function updateSensorCount(sensors) {
    const running = sensors.filter(s => s.running).length;
    const el = document.getElementById('sensorCount');
    if (el) el.innerHTML = `<i class="bi bi-cpu"></i> 传感器: ${running}/${sensors.length}`;
}

// ============================ 卡片交互 ============================

function toggleCardMenu(e, sensorKey) {
    e.stopPropagation();
    document.querySelectorAll('.sensor-card-dropdown.show').forEach(d => d.classList.remove('show'));
    const menu = document.getElementById('menu-' + sensorKey);
    if (menu) menu.classList.toggle('show');
}

document.addEventListener('click', () => {
    document.querySelectorAll('.sensor-card-dropdown.show').forEach(d => d.classList.remove('show'));
});

function startSensor(sensorKey) {
    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/start`, { method: 'POST' })
        .then(r => r.json())
        .then(resp => { if (!resp.error) loadSensors(); })
        .catch(err => console.error('启动失败:', err));
}

function stopSensor(sensorKey) {
    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/stop`, { method: 'POST' })
        .then(r => r.json())
        .then(resp => { if (!resp.error) loadSensors(); })
        .catch(err => console.error('停止失败:', err));
}

function changeSensorMode(sensorKey, newMode) {
    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/config`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ controlMode: newMode }),
    })
    .then(r => r.json())
    .then(resp => {
        if (resp.error) { alert(resp.error); return; }
        const s = sensorList.find(x => x.sensorKey === sensorKey);
        if (s) s.controlMode = newMode;
        renderSensorCards(sensorList);
    })
    .catch(err => alert('切换失败: ' + err.message));
}

function removeSensor(sensorKey) {
    if (!confirm(`确定要删除传感器 ${sensorKey} 吗？此操作不可恢复。`)) return;
    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}`, { method: 'DELETE' })
        .then(r => r.json()).then(resp => { if (!resp.error) loadSensors(); })
        .catch(err => alert('删除失败: ' + err.message));
}

function publishOnce(sensorKey) {
    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/publish-once`, { method: 'POST' })
        .then(r => r.json()).then(resp => { if (resp.error) alert(resp.error); })
        .catch(err => alert('发送失败: ' + err.message));
}

function startAllSensors() {
    const btn = document.getElementById('btnStartAll');
    if (btn) { btn.disabled = true; btn.innerHTML = '<i class="bi bi-hourglass-split"></i> 启动中...'; }
    fetch('/api/sensors/start-all', { method: 'POST' })
        .then(r => r.json()).then(resp => { loadSensors(); })
        .catch(err => alert('启动失败: ' + err.message))
        .finally(() => { if (btn) { btn.disabled = false; btn.innerHTML = '<i class="bi bi-play-fill"></i> 全部启动'; } });
}

function stopAllSensors() {
    if (!confirm('确定要停止所有传感器吗？可通过「全部启动」恢复。')) return;
    const btn = document.getElementById('btnStopAll');
    if (btn) { btn.disabled = true; btn.innerHTML = '<i class="bi bi-hourglass-split"></i> 停止中...'; }
    fetch('/api/sensors/stop-all', { method: 'POST' })
        .then(r => r.json()).then(resp => { loadSensors(); })
        .catch(err => alert('停止失败: ' + err.message))
        .finally(() => { if (btn) { btn.disabled = false; btn.innerHTML = '<i class="bi bi-stop-fill"></i> 全部停止'; } });
}

// ============================ 添加/编辑传感器 ============================

function submitSensorForm() {
    const editKey = document.getElementById('editSensorKey')?.value || '';
    if (editKey) updateSensor(editKey);
    else addSensor();
}

function addSensor() {
    const form = document.getElementById('addSensorForm');
    const fd = new FormData(form);
    const data = {};
    fd.forEach((v, k) => { data[k] = v; });

    const deviceId = (data.deviceId || '').trim();
    if (!deviceId) {
        alert('请输入所属设备ID（需与后端已创建的设备ID一致）');
        return;
    }

    const body = {
        deviceId: deviceId,
        sensorType: data.sensorType || 'light',
        displayName: data.displayName || '',
        deviceGroup: data.deviceGroup || '',
        dataTopic: data.dataTopic || `streetlight/${deviceId}/sensor/data`,
        interval: parseInt(data.interval) || 5,
        enabled: true,
        controlMode: data.controlMode || 'auto',
        dataRange: { min: parseFloat(data.min) || 0, max: parseFloat(data.max) || 800 },
        configJson: JSON.stringify({ min: parseFloat(data.min) || 0, max: parseFloat(data.max) || 800 }),
    };

    const btn = document.getElementById('btnAddSensorSubmit');
    if (btn) { btn.disabled = true; btn.innerHTML = '<i class="bi bi-hourglass-split"></i> 添加中...'; }

    fetch('/api/sensors', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    })
    .then(r => r.json())
    .then(resp => {
        if (resp.error) { alert(resp.error); return; }
        const modal = bootstrap.Modal.getInstance(document.getElementById('addSensorModal'));
        if (modal) modal.hide();
        form.reset();
        document.getElementById('editSensorKey').value = '';
        loadSensors();
    })
    .catch(err => alert('添加失败: ' + err.message))
    .finally(() => { if (btn) { btn.disabled = false; btn.innerHTML = '<i class="bi bi-plus-lg"></i> 添加传感器'; } });
}

function editSensor(sensorKey) {
    const s = sensorList.find(x => x.sensorKey === sensorKey);
    if (!s) return;

    const title = document.getElementById('addSensorModalTitle');
    if (title) title.innerHTML = '<i class="bi bi-pencil"></i> 编辑传感器 — ' + escHtml(sensorKey);
    document.getElementById('editSensorKey').value = sensorKey;

    const form = document.getElementById('addSensorForm');
    if (!form) return;
    form.querySelector('[name="deviceId"]').value = s.deviceId || '';
    form.querySelector('[name="sensorType"]').value = s.sensorType || 'light';
    form.querySelector('[name="displayName"]').value = s.displayName || '';
    form.querySelector('[name="deviceGroup"]').value = s.deviceGroup || s.deviceName || '';
    form.querySelector('[name="dataTopic"]').value = s.dataTopic || '';
    form.querySelector('[name="interval"]').value = s.interval || 5;
    form.querySelector('[name="controlMode"]').value = s.controlMode || 'auto';
    let dr = s.dataRange || {};
    if (!dr.min && s.configJson) { try { dr = JSON.parse(s.configJson); } catch(e) {} }
    form.querySelector('[name="min"]').value = dr.min || 0;
    form.querySelector('[name="max"]').value = dr.max || 800;

    const btn = document.getElementById('btnAddSensorSubmit');
    if (btn) btn.innerHTML = '<i class="bi bi-check-lg"></i> 保存修改';

    new bootstrap.Modal(document.getElementById('addSensorModal')).show();
}

function updateSensor(sensorKey) {
    const form = document.getElementById('addSensorForm');
    const fd = new FormData(form);
    const data = {};
    fd.forEach((v, k) => { data[k] = v; });

    const body = {
        sensorType: data.sensorType || 'light',
        displayName: data.displayName || '',
        deviceGroup: data.deviceGroup || '',
        interval: parseInt(data.interval) || 5,
        dataTopic: data.dataTopic || '',
        controlMode: data.controlMode || 'auto',
        dataRange: { min: parseFloat(data.min) || 0, max: parseFloat(data.max) || 800 },
    };

    const btn = document.getElementById('btnAddSensorSubmit');
    if (btn) { btn.disabled = true; btn.innerHTML = '<i class="bi bi-hourglass-split"></i> 保存中...'; }

    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/config`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    })
    .then(r => r.json())
    .then(resp => {
        if (resp.error) { alert(resp.error); return; }
        const modal = bootstrap.Modal.getInstance(document.getElementById('addSensorModal'));
        if (modal) modal.hide();
        document.getElementById('editSensorKey').value = '';
        form.reset();
        loadSensors();
    })
    .catch(err => alert('更新失败: ' + err.message))
    .finally(() => {
        if (btn) { btn.disabled = false; btn.innerHTML = '<i class="bi bi-plus-lg"></i> 添加传感器'; }
        const title = document.getElementById('addSensorModalTitle');
        if (title) title.innerHTML = '<i class="bi bi-plus-circle"></i> 添加传感器';
    });
}

function autoFillDataTopic() {
    const deviceIdEl = document.getElementById('deviceIdInput');
    const deviceId = deviceIdEl?.value?.trim();
    if (!deviceId) {
        alert('请先填写所属设备ID');
        return;
    }
    const el = document.getElementById('dataTopicInput');
    if (el) el.value = `streetlight/${deviceId}/sensor/data`;
}

// ============================ MQTT 配置 ============================

function loadMqttConfig() {
    fetch('/api/config/mqtt')
        .then(r => r.json())
        .then(cfg => {
            const form = document.getElementById('mqttConfigForm');
            if (!form) return;
            setFormVal(form, 'broker', cfg.broker);
            setFormVal(form, 'port', cfg.port);
            setFormVal(form, 'clientId', cfg.clientId);
            setFormVal(form, 'username', cfg.username);
            if (cfg.password) setFormVal(form, 'password', cfg.password);
        }).catch(() => {});
}

function saveMqttConfig() {
    const form = document.getElementById('mqttConfigForm');
    const data = {
        broker: form.broker.value.trim(),
        port: parseInt(form.port.value) || 1883,
        clientId: form.clientId.value.trim(),
        username: form.username.value.trim(),
        password: form.password.value,
    };
    if (!data.broker) { alert('Broker 地址不能为空'); return; }

    fetch('/api/config/mqtt', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    }).then(r => r.json()).then(resp => {
        if (resp.error) alert(resp.error);
        else alert(resp.message || 'MQTT 配置已保存');
    }).catch(err => alert('保存失败: ' + err.message));
}

// ============================ 配置指令 ============================

function sendMockConfig() {
    const action = document.getElementById('cmdAction')?.value || '';
    const deviceId = document.getElementById('cmdDeviceId')?.value?.trim() || '';
    let params = {};
    try {
        const raw = document.getElementById('cmdParams')?.value?.trim();
        if (raw) params = JSON.parse(raw);
    } catch (e) { alert('参数 JSON 格式错误'); return; }

    const el = document.getElementById('cmdResult');
    if (el) el.innerHTML = '<span class="text-info">发送中...</span>';

    fetch('/api/mock-config/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action, deviceId, params }),
    }).then(r => r.json()).then(resp => {
        if (el) el.innerHTML = `<span class="${resp.success ? 'text-success' : 'text-warning'}">${resp.message}</span>`;
    }).catch(err => { if (el) el.innerHTML = `<span class="text-danger">${err.message}</span>`; });
}

// ============================ 消息模板 ============================

function editMessageTemplate(sensorKey) {
    const s = sensorList.find(x => x.sensorKey === sensorKey);
    if (!s) return;
    document.getElementById('templateSensorLabel').textContent = `${sensorKey} (${s.sensorType || 'light'})`;
    document.getElementById('msgTemplateModal').dataset.sensorKey = sensorKey;

    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/message-template`)
        .then(r => r.json())
        .then(data => {
            document.getElementById('msgTemplateInput').value = data.template || '';
            currentAutoSendMode = data.autoSendMode || 'algorithm';
            document.getElementById('modeAlgorithm').checked = (currentAutoSendMode === 'algorithm');
            document.getElementById('modeFixed').checked = (currentAutoSendMode === 'fixed');
            document.getElementById('autoSendContentInput').value = data.autoSendContent || '';
            applyAutoSendModeUI(currentAutoSendMode);
        }).catch(() => {
            document.getElementById('msgTemplateInput').value = '';
            document.getElementById('autoSendContentInput').value = '';
            document.getElementById('modeAlgorithm').checked = true;
            document.getElementById('modeFixed').checked = false;
            applyAutoSendModeUI('algorithm');
        });

    new bootstrap.Modal(document.getElementById('msgTemplateModal')).show();
}

function onAutoSendModeChange(mode) {
    currentAutoSendMode = mode;
    applyAutoSendModeUI(mode);
}

function applyAutoSendModeUI(mode) {
    const algoDesc = document.getElementById('algorithmModeDesc');
    const fixedDesc = document.getElementById('fixedModeDesc');
    const templateSec = document.getElementById('templateSection');
    const badge = document.getElementById('fixedContentBadge');
    if (algoDesc) algoDesc.style.display = mode === 'algorithm' ? '' : 'none';
    if (fixedDesc) fixedDesc.style.display = mode === 'fixed' ? '' : 'none';
    if (templateSec) templateSec.style.display = mode === 'algorithm' ? '' : 'none';
    if (badge) badge.style.display = mode === 'fixed' ? '' : 'none';
}

function saveAllTemplateSettings() {
    const sensorKey = document.getElementById('msgTemplateModal').dataset.sensorKey;
    const template = document.getElementById('msgTemplateInput')?.value || '';
    const content = document.getElementById('autoSendContentInput')?.value || '';
    const status = document.getElementById('templateSaveStatus');

    if (status) status.textContent = '保存中...';

    Promise.all([
        fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/message-template`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ template }),
        }),
        fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/auto-send-config`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ autoSendMode: currentAutoSendMode, autoSendContent: content }),
        }),
    ])
    .then(() => {
        if (status) { status.textContent = '已保存'; status.className = 'text-success small me-auto'; }
        loadSensors();
        setTimeout(() => { if (status) { status.textContent = ''; status.className = 'text-muted small me-auto'; } }, 2000);
    })
    .catch(err => { if (status) status.textContent = '保存失败: ' + err.message; });
}

function resetTemplate() {
    document.getElementById('msgTemplateInput').value = '';
}

function formatTemplate() {
    const el = document.getElementById('msgTemplateInput');
    try { el.value = JSON.stringify(JSON.parse(el.value), null, 2); } catch(e) { alert('JSON 格式错误'); }
}

function generateSampleContent() {
    const key = document.getElementById('msgTemplateModal').dataset.sensorKey;
    const s = sensorList.find(x => x.sensorKey === key);
    const type = s ? s.sensorType : 'light';
    fetch(`/api/sensors/generate-sample/${type}?deviceId=${encodeURIComponent(s?.deviceId || 'SL-001')}`)
        .then(r => r.json())
        .then(data => { document.getElementById('autoSendContentInput').value = data.sample; })
        .catch(() => {});
}

function formatAutoSendContent() {
    const el = document.getElementById('autoSendContentInput');
    try { el.value = JSON.stringify(JSON.parse(el.value), null, 2); } catch(e) { alert('JSON 格式错误'); }
}

function validateAutoSendContent() {
    const el = document.getElementById('autoSendValidation');
    try {
        JSON.parse(document.getElementById('autoSendContentInput').value);
        el.innerHTML = '<span class="text-success"><i class="bi bi-check-circle"></i> JSON 格式正确</span>';
    } catch(e) {
        el.innerHTML = `<span class="text-danger"><i class="bi bi-x-circle"></i> JSON 错误: ${e.message}</span>`;
    }
}

function loadPresetTemplate(type) {
    fetch(`/api/sensors/generate-sample/${type}`)
        .then(r => r.json())
        .then(data => {
            const obj = JSON.parse(data.sample);
            obj.deviceId = '{{deviceId}}';
            obj.timestamp = '{{timestamp}}';
            document.getElementById('autoSendContentInput').value = JSON.stringify(obj, null, 2);
        }).catch(() => {});
}

// ============================ 发送历史 ============================

function loadHistory() {
    fetch('/api/sensors/history')
        .then(r => r.json())
        .then(data => {
            historyList = data || [];
            renderHistory();
        }).catch(() => {});
}

function renderHistory(filterKey) {
    const container = document.getElementById('historyContainer');
    if (!container) return;
    let items = historyList;
    if (filterKey) items = items.filter(h => h.sensorKey === filterKey);

    if (!items.length) {
        container.innerHTML = '<div class="text-center text-muted py-3 small"><i class="bi bi-hourglass-split"></i> 等待数据发送...</div>';
        return;
    }
    container.innerHTML = items.map(h => `
        <div class="history-item">
            <div class="history-header" data-bs-toggle="collapse" data-bs-target="#hist-${h.time.replace(/\D/g,'')}">
                <span class="history-time">${escHtml(h.time)}</span>
                <span class="history-label">${escHtml(h.displayName || h.deviceId)}</span>
                <span class="history-chevron ms-auto"><i class="bi bi-chevron-down"></i></span>
            </div>
            <div class="collapse"><div class="history-topic small text-muted">${escHtml(h.topic)}</div>
            <pre class="history-payload">${escHtml(JSON.stringify(h.payload, null, 2))}</pre></div>
        </div>`).join('');
}

function clearHistory() {
    fetch('/api/sensors/history', { method: 'DELETE' })
        .then(r => r.json()).then(() => { historyList = []; renderHistory(); })
        .catch(err => console.error('清空历史失败:', err));
}

function onHistoryFilterChange() {
    const filter = document.getElementById('historyFilter')?.value || '';
    renderHistory(filter);
}

function scrollToHistoryBottom() {
    const container = document.getElementById('historyContainer');
    if (container) container.scrollTop = container.scrollHeight;
    newHistoryCount = 0;
    const badge = document.getElementById('newHistoryBadge');
    if (badge) badge.style.display = 'none';
}

function updateHistoryFilter(sensors) {
    const select = document.getElementById('historyFilter');
    if (!select) return;
    const currentVal = select.value;
    const keys = sensors.map(s => s.sensorKey);
    select.innerHTML = '<option value="">全部传感器</option>' +
        keys.map(k => `<option value="${escHtml(k)}">${escHtml(k)}</option>`).join('');
    select.value = currentVal;
}

// ============================ 实时日志 ============================

function connectLogStream() {
    const evtSource = new EventSource('/api/logs/stream');
    evtSource.onmessage = (e) => {
        try {
            const data = JSON.parse(e.data);
            const container = document.getElementById('logContainer');
            if (!container) return;
            const cls = 'log-' + (data.level || 'info').toLowerCase();
            container.innerHTML += `<span class="${cls}">[${data.time}] ${data.message}</span>\n`;
            container.scrollTop = container.scrollHeight;
            if (container.innerHTML.length > 50000) {
                container.innerHTML = container.innerHTML.slice(-30000);
            }
        } catch(ex) {}
    };
    evtSource.onerror = () => { /* reconnect handled by browser */ };
}

function clearLogs() {
    const container = document.getElementById('logContainer');
    if (container) container.innerHTML = '日志已清空\n';
}

// ============================ 轮询 ============================

function startPolling() {
    pollInterval = setInterval(() => {
        fetch('/api/status').then(r => r.json()).then(status => {
            const mqttEl = document.getElementById('mqttStatus');
            if (mqttEl) {
                if (status.mqtt?.connected) {
                    mqttEl.className = 'badge bg-success';
                    mqttEl.innerHTML = '<i class="bi bi-plug-fill"></i> MQTT 已连接';
                } else {
                    mqttEl.className = 'badge bg-secondary';
                    mqttEl.innerHTML = '<i class="bi bi-plug"></i> MQTT 未连接';
                }
            }
            uptimeSeconds = Math.floor(status.uptime || 0);
        }).catch(() => {});

        loadSensors();
        loadHistory();
    }, 5000);
}

function startUptimeTimer() {
    uptimeInterval = setInterval(() => {
        uptimeSeconds++;
        const h = Math.floor(uptimeSeconds / 3600);
        const m = Math.floor((uptimeSeconds % 3600) / 60);
        const s = uptimeSeconds % 60;
        const el = document.getElementById('uptime');
        if (el) el.textContent = `运行: ${String(h).padStart(2,'0')}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`;
    }, 1000);
}

// ============================ 工具函数 ============================

function escHtml(str) {
    if (!str) return '';
    return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function escAttr(str) {
    if (!str) return '';
    return String(str).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function setVal(id, val) {
    const el = document.getElementById(id);
    if (el && val !== undefined && val !== null) el.value = val;
}

function setFormVal(form, name, val) {
    const el = form.querySelector(`[name="${name}"]`);
    if (el && val !== undefined && val !== null) el.value = val;
}
