/**
 * 智慧路灯 Mock 数据发送器 - 卡片式传感器管理
 * v3 — 纯 MQTT，卡片网格 UI
 */

// ============================ 全局状态 ============================
let sensorList = [];
let historyList = [];
let historyPaused = false;
let historyUserScrolled = false;
let uptimeSeconds = 0;
let uptimeInterval = null;
let pollInterval = null;
let newHistoryCount = 0;
let currentAutoSendMode = 'algorithm';
let currentFilter = 'all';
let currentSearch = '';
let sortableInstance = null;
let isDragging = false;
let lastRenderFingerprint = '';   // 用于检测传感器结构是否变化，避免不必要的全量重渲染

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

    // 拖拽进行中时跳过重渲染，避免打断操作
    if (isDragging) return;

    // 应用筛选和搜索
    let filtered = sensors;
    if (currentFilter !== 'all') {
        filtered = filtered.filter(s => s.sensorType === currentFilter);
    }
    if (currentSearch) {
        const kw = currentSearch.toLowerCase();
        filtered = filtered.filter(s =>
            (s.displayName || '').toLowerCase().includes(kw) ||
            (s.sensorKey || '').toLowerCase().includes(kw) ||
            (s.groupTag || '').toLowerCase().includes(kw)
        );
    }

    // 空传感器列表 → 清空
    if (!sensors || sensors.length === 0) {
        grid.innerHTML = '';
        lastRenderFingerprint = '';
        if (noCard) noCard.style.display = '';
        updateHistoryFilter(sensors);
        return;
    }
    if (noCard) noCard.style.display = 'none';

    // 搜索无结果
    if (filtered.length === 0 && sensors.length > 0) {
        grid.innerHTML = `<div class="no-search-results">
            <i class="bi bi-search"></i>
            <span>没有匹配的传感器</span>
            <div class="hint">试试其他关键词或清除筛选</div>
        </div>`;
        lastRenderFingerprint = '';
        updateHistoryFilter(sensors);
        return;
    }

    // 计算当前渲染指纹（传感器 key + 顺序）
    const fingerprint = filtered.map(s => s.sensorKey).join(',');

    if (fingerprint === lastRenderFingerprint && grid.children.length === filtered.length) {
        // ★ 结构未变 → 原地更新卡片动态数据，不重建 DOM，避免动画闪烁
        updateCardsInPlace(grid, filtered);
    } else {
        // ★ 结构变化（增/删/排序/筛选）→ 全量重建
        grid.innerHTML = filtered.map((s, i) => buildCard(s, i)).join('');
        lastRenderFingerprint = fingerprint;
        // 初始化拖拽
        initSortable();
    }

    updateHistoryFilter(sensors);
}

/**
 * 原地更新卡片中的动态数据（不触发 CSS cardIn 动画）
 */
function updateCardsInPlace(grid, sensors) {
    const now = Date.now();
    sensors.forEach((s, i) => {
        const card = document.getElementById('card-' + escAttr(s.sensorKey));
        if (!card) {
            // 卡片缺失（罕见）→ 回退到全量渲染
            grid.innerHTML = sensors.map((s2, j) => buildCard(s2, j)).join('');
            lastRenderFingerprint = sensors.map(x => x.sensorKey).join(',');
            initSortable();
            return;
        }

        // 1. 更新卡片状态类
        const isRunning = s.running;
        const autoMode = s.autoSendMode || 'algorithm';
        card.classList.remove('running-algorithm', 'running-fixed', 'stopped');
        if (isRunning && autoMode === 'fixed') card.classList.add('running-fixed');
        else if (isRunning) card.classList.add('running-algorithm');
        else card.classList.add('stopped');

        // 2. 更新状态圆点
        const dot = card.querySelector('.card-status-dot');
        if (dot) {
            dot.classList.remove('running', 'stopped');
            dot.classList.add(isRunning ? 'running' : 'stopped');
        }

        // 3. 更新状态文本
        const statusSpan = card.querySelector('.card-row .value .card-status-dot');
        if (statusSpan) {
            const parent = statusSpan.parentElement;
            const newDotClass = isRunning ? 'running' : 'stopped';
            const newText = isRunning ? `运行中 (${s.publishCount || 0}次)` : '已停止';
            parent.innerHTML = `<span class="card-status-dot ${newDotClass}"></span>${newText}`;
        }

        // 4. 更新最后发送时间
        const rows = card.querySelectorAll('.card-row');
        rows.forEach(row => {
            const label = row.querySelector('.label');
            if (label && label.textContent === '最后发送') {
                const value = row.querySelector('.value');
                if (value) {
                    let text = '-';
                    if (s.lastPublish) {
                        const sec = Math.floor(now / 1000 - s.lastPublish);
                        if (sec < 60) text = `${sec} 秒前`;
                        else if (sec < 3600) text = `${Math.floor(sec / 60)} 分钟前`;
                        else text = `${Math.floor(sec / 3600)} 小时前`;
                    }
                    value.textContent = text;
                }
            }
        });

        // 5. 更新模式 badge
        const modeBadge = card.querySelector('.mode-badge');
        if (modeBadge) {
            modeBadge.classList.remove('algorithm', 'fixed');
            modeBadge.classList.add(autoMode === 'fixed' ? 'fixed' : 'algorithm');
            modeBadge.textContent = autoMode === 'fixed' ? '📌 固定内容' : '🔵 算法动态';
        }

        // 6. 更新控制模式下拉框
        const modeSelect = card.querySelector('.sensor-card-footer select');
        if (modeSelect && s.controlMode) {
            modeSelect.value = s.controlMode;
        }

        // 7. 更新启动/停止按钮（仅当运行状态变化时替换）
        const footerBtns = card.querySelector('.sensor-card-footer');
        const existingBtn = card.querySelector('.sensor-card-footer .btn:first-child');
        if (footerBtns && existingBtn) {
            const btnIsStop = existingBtn.classList.contains('btn-outline-secondary');
            if (isRunning && !btnIsStop) {
                // 需要显示「停止」按钮
                const safeKey = escAttr(s.sensorKey);
                existingBtn.outerHTML = `<button class="btn btn-sm btn-outline-secondary flex-fill" onclick="stopSensor('${safeKey}')"><i class="bi bi-stop-fill"></i> 停止</button>`;
            } else if (!isRunning && btnIsStop) {
                // 需要显示「启动」按钮
                const safeKey = escAttr(s.sensorKey);
                existingBtn.outerHTML = `<button class="btn btn-sm btn-outline-success flex-fill" onclick="startSensor('${safeKey}')"><i class="bi bi-play-fill"></i> 启动</button>`;
            }
        }

        // 8. 更新频率显示
        rows.forEach(row => {
            const label = row.querySelector('.label');
            if (label && label.textContent === '频率') {
                const value = row.querySelector('.value');
                if (value) value.textContent = `每 ${s.interval || 5} 秒`;
            }
        });
    });
}

function buildCard(s, index) {
    const typeIcon = TYPE_ICONS[s.sensorType] || '';
    const typeLabel = TYPE_LABELS[s.sensorType] || s.sensorType;
    const isRunning = s.running;
    const autoMode = s.autoSendMode || 'algorithm';
    const groupTag = s.groupTag || '';
    const safeKey = escAttr(s.sensorKey);
    const idx = index != null ? index : 0;

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
    <div class="sensor-card ${cardStateClass}" id="card-${safeKey}" style="--card-index:${idx}" data-sensor-key="${safeKey}">
        <div class="sensor-card-header">
            <span class="drag-handle" title="拖动排序"><i class="bi bi-grip-vertical"></i></span>
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
            ${groupTag ? `<div class="mb-2"><span class="device-group-tag"><i class="bi bi-folder"></i> ${escHtml(groupTag)}</span></div>` : ''}
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
            <button class="btn btn-sm btn-outline-primary" onclick="editSensor('${safeKey}')" title="编辑">
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
        lastRenderFingerprint = ''; // 模式切换可能改变卡片类 → 强制渲染
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

// ============================ 筛选 & 搜索 ============================

function setSensorFilter(filter) {
    currentFilter = filter;
    lastRenderFingerprint = ''; // 筛选变化 → 强制全量渲染
    // 更新 chip 样式
    document.querySelectorAll('.filter-chip').forEach(chip => {
        chip.classList.toggle('active', chip.dataset.filter === filter);
    });
    renderSensorCards(sensorList);
}

function onSensorSearch() {
    const input = document.getElementById('sensorSearch');
    const clearBtn = document.getElementById('searchClearBtn');
    if (!input) return;
    currentSearch = input.value.trim();
    lastRenderFingerprint = ''; // 搜索词变化 → 强制全量渲染
    if (clearBtn) clearBtn.style.display = currentSearch ? '' : 'none';
    renderSensorCards(sensorList);
}

function clearSensorSearch() {
    const input = document.getElementById('sensorSearch');
    const clearBtn = document.getElementById('searchClearBtn');
    if (input) { input.value = ''; input.focus(); }
    if (clearBtn) clearBtn.style.display = 'none';
    currentSearch = '';
    lastRenderFingerprint = ''; // 清除搜索 → 强制全量渲染
    renderSensorCards(sensorList);
}

// ============================ 拖拽排序 (SortableJS) ============================

function initSortable() {
    const grid = document.getElementById('sensorCardGrid');
    if (!grid) return;

    // 销毁旧实例
    if (sortableInstance) {
        sortableInstance.destroy();
        sortableInstance = null;
    }

    // 添加拖拽手柄样式
    grid.classList.add('drag-enabled');

    sortableInstance = new Sortable(grid, {
        animation: 200,
        easing: 'cubic-bezier(0.34, 1.56, 0.64, 1)',
        handle: '.drag-handle',
        ghostClass: 'sortable-ghost',
        chosenClass: 'sortable-chosen',
        dragClass: 'sortable-drag',
        delay: 80,
        delayOnTouchOnly: true,
        onStart: function() {
            isDragging = true;
        },
        onEnd: function(evt) {
            isDragging = false;
            lastRenderFingerprint = ''; // 拖拽重排后重置指纹
            // 更新 sensorList 顺序以匹配 DOM
            const orderedKeys = [];
            grid.querySelectorAll('.sensor-card').forEach(card => {
                const key = card.dataset.sensorKey;
                if (key) orderedKeys.push(key);
            });
            // 按新顺序重排 sensorList
            const map = {};
            sensorList.forEach(s => { map[s.sensorKey] = s; });
            const newList = orderedKeys.map(k => map[k]).filter(Boolean);
            // 保留不在 grid 中的传感器（可能被筛选隐藏）
            const hiddenCards = sensorList.filter(s => !orderedKeys.includes(s.sensorKey));
            sensorList = [...newList, ...hiddenCards];
            // 延迟重算指纹（等 DOM 稳定）
            setTimeout(() => {
                const grid2 = document.getElementById('sensorCardGrid');
                if (grid2) {
                    const keys = [];
                    grid2.querySelectorAll('.sensor-card').forEach(c => {
                        if (c.dataset.sensorKey) keys.push(c.dataset.sensorKey);
                    });
                    lastRenderFingerprint = keys.join(',');
                }
            }, 250);
        }
    });
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

    const sensorId = parseInt(data.sensorId) || Math.floor(Date.now() % 100000);
    const sensorType = data.sensorType || 'light';
    const body = {
        sensorId: sensorId,
        sensorType: sensorType,
        displayName: data.displayName || '',
        groupTag: data.groupTag || '',
        dataTopic: data.dataTopic || `streetlight/sensor/${sensorId}/data`,
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
    form.querySelector('[name="sensorType"]').value = s.sensorType || 'light';
    // v4: deviceId no longer needed
    form.querySelector('[name="displayName"]').value = s.displayName || '';
    form.querySelector('[name="groupTag"]').value = s.groupTag || '';
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
        groupTag: data.groupTag || '',
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
    const sensorId = Math.floor(Date.now() % 100000);
    const el = document.getElementById('dataTopicInput');
    if (el) el.value = `streetlight/sensor/${sensorId}/data`;
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

const CMD_HINTS = {
    set_frequency:    '{"interval": 3}',
    set_data_range:   '{"min": 0, "max": 600}',
    set_light_status: '{"sensorId": 15704, "status": "on"}',
    add_sensor:       '{"sensorId": 12345, "sensorType": "light", "displayName": "新传感器"}',
    remove_sensor:    '{"sensorId": 15704}',
    stop_sensor:      '{"sensorId": 15704}',
    start_sensor:     '{"sensorId": 15704}',
};

function onCmdActionChange() {
    const action = document.getElementById('cmdAction')?.value || '';
    const input = document.getElementById('cmdParams');
    if (input && CMD_HINTS[action]) {
        input.value = CMD_HINTS[action];
    }
}

function sendMockConfig() {
    const action = document.getElementById('cmdAction')?.value || '';
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
        body: JSON.stringify({ action, params }),
    }).then(r => r.json()).then(resp => {
        if (el) el.innerHTML = `<span class="${resp.success ? 'text-success' : 'text-warning'}">${resp.message}</span>`;
        setTimeout(() => { if (el) el.innerHTML = ''; }, 3000);
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
    fetch(`/api/sensors/generate-sample/${type}`)
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
            document.getElementById('autoSendContentInput').value = data.sample;
        }).catch(() => {});
}

// ============================ 发送历史 ============================

function loadHistory() {
    fetch('/api/sensors/history')
        .then(r => r.json())
        .then(data => {
            const prevLen = historyList.length;
            const newData = data || [];
            historyList = newData;
            // 检测新条目用于暂停时的徽章计数
            if (historyPaused || historyUserScrolled) {
                newHistoryCount += Math.max(0, newData.length - prevLen);
                const badge = document.getElementById('newHistoryBadge');
                if (badge && newHistoryCount > 0) {
                    badge.textContent = '+' + newHistoryCount;
                    badge.style.display = 'inline-block';
                }
            }
            renderHistory();
        }).catch(() => {});
}

function renderHistory(filterKey) {
    const container = document.getElementById('historyContainer');
    if (!container) return;
    let items = historyList;
    if (filterKey) items = items.filter(h => h.sensorKey === filterKey);

    // 保存已展开的条目
    const expandedIds = new Set();
    container.querySelectorAll('.collapse.show').forEach(el => {
        if (el.id) expandedIds.add(el.id);
    });

    if (!items.length) {
        container.innerHTML = '<div class="text-center text-muted py-3 small"><i class="bi bi-hourglass-split"></i> 等待数据发送...</div>';
        return;
    }
    container.innerHTML = items.map(h => {
        const histId = 'hist-' + h.time.replace(/\D/g, '');
        const showClass = expandedIds.has(histId) ? ' show' : '';
        return `
        <div class="history-item">
            <div class="history-header" data-bs-toggle="collapse" data-bs-target="#${histId}">
                <span class="history-time">${escHtml(h.time)}</span>
                <span class="history-label">${escHtml(h.displayName || h.sensorKey || '')}</span>
                <span class="history-chevron ms-auto"><i class="bi bi-chevron-down"></i></span>
            </div>
            <div class="collapse${showClass}" id="${histId}"><div class="history-topic small text-muted">${escHtml(h.topic)}</div>
            <pre class="history-payload">${escHtml(JSON.stringify(h.payload, null, 2))}</pre></div>
        </div>`}).join('');

    // 自动滚动到底部（除非用户暂停或主动上滚）
    if (!historyPaused && !historyUserScrolled) {
        container.scrollTop = container.scrollHeight;
    }
}

function toggleHistoryPause() {
    historyPaused = !historyPaused;
    historyUserScrolled = false;
    const btn = document.getElementById('historyPauseBtn');
    if (btn) {
        if (historyPaused) {
            btn.innerHTML = '<i class="bi bi-pause-fill"></i> 已暂停';
            btn.className = 'btn btn-sm btn-outline-danger';
        } else {
            btn.innerHTML = '<i class="bi bi-play-fill"></i> 滚动中';
            btn.className = 'btn btn-sm btn-outline-warning';
            scrollToHistoryBottom();
        }
    }
}

function onHistoryScroll() {
    const container = document.getElementById('historyContainer');
    if (!container) return;
    // 用户滚离底部超过 50px 视为主动查看旧记录
    const distToBottom = container.scrollHeight - container.scrollTop - container.clientHeight;
    if (distToBottom > 50 && !historyPaused) {
        historyUserScrolled = true;
        const badge = document.getElementById('newHistoryBadge');
        if (badge) badge.style.display = 'inline-block';
    }
    if (distToBottom < 10) {
        historyUserScrolled = false;
        const badge = document.getElementById('newHistoryBadge');
        if (badge) badge.style.display = 'none';
    }
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

let logPaused = false;
let logUserScrolled = false;

function connectLogStream() {
    const evtSource = new EventSource('/api/logs/stream');
    evtSource.onmessage = (e) => {
        try {
            const data = JSON.parse(e.data);
            const container = document.getElementById('logContainer');
            if (!container) return;
            const cls = 'log-' + (data.level || 'info').toLowerCase();
            container.innerHTML += `<span class="${cls}">[${data.time}] ${data.message}</span>\n`;
            // 用户未暂停且未主动上滚时自动滚动
            if (!logPaused && !logUserScrolled) {
                container.scrollTop = container.scrollHeight;
            }
            // 限制日志长度
            if (container.innerHTML.length > 50000) {
                container.innerHTML = container.innerHTML.slice(-30000);
            }
        } catch(ex) {}
    };
    evtSource.onerror = () => { /* reconnect handled by browser */ };
}

function toggleLogPause() {
    logPaused = !logPaused;
    logUserScrolled = false;
    const btn = document.getElementById('logPauseBtn');
    if (btn) {
        if (logPaused) {
            btn.innerHTML = '<i class="bi bi-pause-fill"></i> 已暂停';
            btn.className = 'btn btn-sm btn-outline-danger';
        } else {
            btn.innerHTML = '<i class="bi bi-play-fill"></i> 滚动中';
            btn.className = 'btn btn-sm btn-outline-warning';
            // 恢复时滚到底部
            const container = document.getElementById('logContainer');
            if (container) container.scrollTop = container.scrollHeight;
        }
    }
}

function onLogScroll() {
    const container = document.getElementById('logContainer');
    if (!container) return;
    const distToBottom = container.scrollHeight - container.scrollTop - container.clientHeight;
    if (distToBottom > 30 && !logPaused) {
        logUserScrolled = true;
    }
    if (distToBottom < 10) {
        logUserScrolled = false;
    }
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
