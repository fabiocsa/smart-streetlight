/**
 * 智慧路灯 Mock 数据发送器 - 前端交互逻辑
 * 适配新版传感器复合键 {deviceId}_{sensorId}
 */

// ============================ 全局状态 ============================
let sensorList = [];
let historyList = [];
let uptimeSeconds = 0;
let uptimeInterval = null;
let pollInterval = null;

// 排序状态
let sortState = { field: null, order: 'asc' };  // null | 'asc' | 'desc'

// 历史面板未读新消息计数
let newHistoryCount = 0;

// ============================ 页面初始化 ============================
document.addEventListener('DOMContentLoaded', () => {
    loadSensors();
    loadMqttConfig();
    loadDbConfig();
    startUptimeTimer();
    startPolling();
    connectLogStream();
});

// ============================ 传感器管理 ============================

function loadSensors() {
    fetch('/api/sensors')
        .then(r => r.json())
        .then(data => {
            sensorList = data;
            renderSensorTable(data);
            updateSensorCount(data);
        })
        .catch(err => console.error('加载传感器失败:', err));
}

function renderSensorTable(sensors) {
    const tbody = document.getElementById('sensorTableBody');
    if (!sensors || sensors.length === 0) {
        tbody.innerHTML = `<tr id="noSensorsRow">
            <td colspan="11" class="text-center text-muted py-4">
                <i class="bi bi-inbox"></i> 暂无传感器，点击右上角"从后端同步"或"添加传感器"
            </td>
        </tr>`;
        updateSortArrows();
        return;
    }

    // 排序
    const sorted = [...sensors];
    if (sortState.field) {
        sorted.sort((a, b) => {
            let va = a[sortState.field], vb = b[sortState.field];
            if (typeof va === 'string') va = va.toLowerCase();
            if (typeof vb === 'string') vb = vb.toLowerCase();
            if (va == null) va = '';
            if (vb == null) vb = '';
            if (va < vb) return sortState.order === 'asc' ? -1 : 1;
            if (va > vb) return sortState.order === 'asc' ? 1 : -1;
            return 0;
        });
    }

    const typeLabels = { light: '光照', temperature: '温度', humidity: '湿度', power: '功率' };
    const typeIcons = { light: '☀️', temperature: '🌡️', humidity: '💧', power: '⚡' };

    tbody.innerHTML = sorted.map(s => {
        // 状态圆点 + 文本
        let statusDotClass, statusText;
        if (s.running && s.controlMode === 'auto') {
            statusDotClass = 'status-dot-active';
            statusText = '发送中';
        } else if (s.running && s.controlMode === 'manual') {
            statusDotClass = 'status-dot-manual';
            statusText = '待命';
        } else {
            statusDotClass = 'status-dot-stopped';
            statusText = '已停止';
        }
        const statusIndicator = `<span class="status-dot ${statusDotClass}"></span> ${statusText}`;

        const typeIcon = typeIcons[s.sensorType] || '';
        const typeBadge = `<span class="badge bg-light text-dark">${typeIcon} ${typeLabels[s.sensorType] || s.sensorType}</span>`;

        // 行内模式切换下拉框
        const modeSelect = `<select class="form-select form-select-sm mode-select"
                onchange="changeSensorMode('${escHtml(s.sensorKey)}', this.value)">
            <option value="auto" ${s.controlMode === 'auto' ? 'selected' : ''}>自动</option>
            <option value="manual" ${s.controlMode === 'manual' ? 'selected' : ''}>手动</option>
        </select>`;

        // 未绑定状态标识
        const isUnbound = !s.deviceId;
        const deviceIdCell = isUnbound
            ? '<span class="badge bg-warning text-dark"><i class="bi bi-unlink"></i> 未绑定</span>'
            : `<code>${escHtml(s.deviceId)}</code>`;
        const deviceNameCell = isUnbound
            ? '<span class="text-warning fw-bold">未绑定设备</span>'
            : (escHtml(s.deviceName) || '<span class="text-muted">-</span>');
        const rowClass = isUnbound ? 'table-warning' : '';

        return `<tr class="${rowClass}">
            <td>${deviceNameCell}</td>
            <td>${escHtml(s.displayName) || '<span class="text-muted">-</span>'}</td>
            <td>${typeBadge}</td>
            <td>${deviceIdCell}</td>
            <td style="display:none"><small class="text-muted">${escHtml(s.sensorKey)}</small></td>
            <td><small>${escHtml(s.dataTopic) || '-'}</small></td>
            <td>${s.interval}s</td>
            <td>${s.publishCount}</td>
            <td class="status-cell">${statusIndicator}</td>
            <td>${modeSelect}</td>
            <td class="text-nowrap">
                <button class="btn btn-sm btn-outline-success" onclick="publishOnce('${escHtml(s.sensorKey)}')"
                        title="手动发送一次">
                    <i class="bi bi-send"></i>
                </button>
                <button class="btn btn-sm btn-outline-secondary" onclick="editMessageTemplate('${escHtml(s.sensorKey)}')"
                        title="编辑MQTT消息模板">
                    <i class="bi bi-code-slash"></i>
                </button>
                <button class="btn btn-sm btn-outline-primary" onclick="editSensor('${escHtml(s.sensorKey)}')"
                        title="编辑配置" data-bs-toggle="modal" data-bs-target="#addSensorModal">
                    <i class="bi bi-pencil"></i>
                </button>
                ${!isUnbound ? `
                <button class="btn btn-sm btn-outline-warning" onclick="unbindSensor('${escHtml(s.sensorKey)}')"
                        title="解绑传感器（保留在数据库，device_id=NULL）">
                    <i class="bi bi-unlink"></i>
                </button>` : ''}
                <button class="btn btn-sm btn-outline-info" onclick="showRebindDialog('${escHtml(s.sensorKey)}')"
                        title="重新绑定到其他设备">
                    <i class="bi bi-link-45deg"></i>
                </button>
                <button class="btn btn-sm btn-outline-danger" onclick="removeSensor('${escHtml(s.sensorKey)}')"
                        title="删除传感器">
                    <i class="bi bi-trash"></i>
                </button>
            </td>
        </tr>`;
    }).join('');

    updateSortArrows();
}

function updateSensorCount(sensors) {
    const running = sensors.filter(s => s.running).length;
    document.getElementById('sensorCount').innerHTML =
        `<i class="bi bi-cpu"></i> 传感器: ${running}/${sensors.length}`;
}

function addSensor() {
    const form = document.getElementById('addSensorForm');
    const data = {
        deviceId: form.deviceId.value.trim(),
        deviceName: form.deviceName.value.trim() || '',
        displayName: form.displayName.value.trim() || '',
        sensorType: form.sensorType.value,
        dataTopic: form.dataTopic.value.trim() || '',
        name: form.displayName.value.trim() || form.deviceId.value.trim(),
        location: form.location.value.trim(),
        interval: parseInt(form.interval.value) || 5,
        controlMode: form.controlMode.value,
        dataRange: {
            min: parseFloat(form.min.value) || 0,
            max: parseFloat(form.max.value) || 800,
        },
    };

    if (!data.deviceId) {
        alert('设备 ID 不能为空');
        return;
    }

    fetch('/api/sensors', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    })
    .then(r => r.json().then(j => ({ ok: r.ok, data: j })))
    .then(({ ok, data }) => {
        if (!ok) {
            alert(data.error || '添加失败');
            return;
        }
        const modal = bootstrap.Modal.getInstance(document.getElementById('addSensorModal'));
        if (modal) modal.hide();
        form.reset();
        loadSensors();
    })
    .catch(err => alert('添加失败: ' + err.message));
}

function removeSensor(sensorKey) {
    if (!confirm(`确定删除传感器 ${sensorKey}？`)) return;

    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}`, { method: 'DELETE' })
        .then(r => r.json())
        .then(() => loadSensors())
        .catch(err => alert('删除失败: ' + err.message));
}

function editSensor(sensorKey) {
    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}`)
        .then(r => r.json())
        .then(s => {
            if (s.error) { alert(s.error); return; }
            const form = document.getElementById('addSensorForm');
            form.deviceId.value = s.deviceId || '';
            form.deviceId.readOnly = true;
            form.deviceName.value = s.deviceName || '';
            form.displayName.value = s.displayName || '';
            form.sensorType.value = s.sensorType || 'light';
            form.dataTopic.value = s.dataTopic || '';
            form.location.value = s.location || '';
            form.interval.value = s.interval || 5;
            form.controlMode.value = s.controlMode || 'auto';
            form.min.value = s.dataRange?.min || 0;
            form.max.value = s.dataRange?.max || 800;
            const btn = document.querySelector('#addSensorModal .btn-primary');
            btn.innerHTML = '<i class="bi bi-check-lg"></i> 保存';
            btn.onclick = updateSensor;
            btn.dataset.sensorKey = sensorKey;
        })
        .catch(err => alert('获取传感器信息失败: ' + err.message));
}

function updateSensor() {
    const form = document.getElementById('addSensorForm');
    const sensorKey = document.querySelector('#addSensorModal .btn-primary').dataset.sensorKey;
    const data = {
        deviceName: form.deviceName.value.trim(),
        displayName: form.displayName.value.trim(),
        sensorType: form.sensorType.value,
        dataTopic: form.dataTopic.value.trim(),
        name: form.displayName.value.trim(),
        location: form.location.value.trim(),
        interval: parseInt(form.interval.value) || 5,
        controlMode: form.controlMode.value,
        dataRange: {
            min: parseFloat(form.min.value) || 0,
            max: parseFloat(form.max.value) || 800,
        },
    };

    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/config`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    })
    .then(r => r.json())
    .then(() => {
        const modal = bootstrap.Modal.getInstance(document.getElementById('addSensorModal'));
        if (modal) modal.hide();
        form.reset();
        form.deviceId.readOnly = false;
        const btn = document.querySelector('#addSensorModal .btn-primary');
        btn.innerHTML = '<i class="bi bi-plus-lg"></i> 添加';
        btn.onclick = addSensor;
        loadSensors();
    })
    .catch(err => alert('更新失败: ' + err.message));
}

// 重置模态框
document.getElementById('addSensorModal').addEventListener('hidden.bs.modal', () => {
    const form = document.getElementById('addSensorForm');
    form.reset();
    form.deviceId.readOnly = false;
    const btn = document.querySelector('#addSensorModal .btn-primary');
    btn.innerHTML = '<i class="bi bi-plus-lg"></i> 添加';
    btn.onclick = addSensor;
});

// ============================ 传感器控制 ============================

function publishOnce(sensorKey) {
    const btn = event.target.closest('button');
    if (btn) {
        btn.disabled = true;
        btn.innerHTML = '<i class="bi bi-hourglass-split"></i>';
    }
    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/publish-once`, { method: 'POST' })
        .then(r => r.json())
        .then(resp => {
            if (resp.error) {
                alert(resp.error);
            }
            loadSensors();
            loadHistory();
        })
        .catch(err => alert('发送失败: ' + err.message))
        .finally(() => {
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = '<i class="bi bi-send"></i>';
            }
        });
}

function stopAllSensors() {
    const btn = document.getElementById('btnStopAll');
    btn.disabled = true;
    btn.innerHTML = '<i class="bi bi-hourglass-split"></i> 停止中...';
    fetch('/api/sensors/stop-all', { method: 'POST' })
        .then(r => r.json())
        .then(resp => {
            alert(resp.message || '已停止所有传感器');
            loadSensors();
        })
        .catch(err => alert('停止失败: ' + err.message))
        .finally(() => {
            btn.disabled = false;
            btn.innerHTML = '<i class="bi bi-stop-fill"></i> 全部停止';
        });
}

function startAllSensors() {
    const btn = document.getElementById('btnStartAll');
    btn.disabled = true;
    btn.innerHTML = '<i class="bi bi-hourglass-split"></i> 启动中...';
    fetch('/api/sensors/start-all', { method: 'POST' })
        .then(r => r.json())
        .then(resp => {
            alert(resp.message || '已启动所有传感器');
            loadSensors();
        })
        .catch(err => alert('启动失败: ' + err.message))
        .finally(() => {
            btn.disabled = false;
            btn.innerHTML = '<i class="bi bi-play-fill"></i> 全部启动';
        });
}

function changeSensorMode(sensorKey, newMode) {
    // 乐观更新：立即修改本地状态并重绘
    const sensor = sensorList.find(s => s.sensorKey === sensorKey);
    if (sensor) {
        sensor.controlMode = newMode;
        // 切换到手动模式时，自动发送被跳过，状态圆点需同步更新
        if (newMode === 'manual') {
            sensor.statusText = '待命';
        }
        renderSensorTable(sensorList);
        updateSensorCount(sensorList);
    }

    // 异步持久化到后端
    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/config`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ controlMode: newMode }),
    })
    .catch(err => {
        console.error('模式切换持久化失败:', err);
        // 失败时回滚：从 API 重新加载
        setTimeout(() => loadSensors(), 500);
    });
}

// ============================ 排序 ============================

function toggleSort(field) {
    if (sortState.field === field) {
        // 同一字段: asc → desc → 取消
        if (sortState.order === 'asc') {
            sortState.order = 'desc';
        } else {
            sortState.field = null;
            sortState.order = 'asc';
        }
    } else {
        sortState.field = field;
        sortState.order = 'asc';
    }
    renderSensorTable(sensorList);
}

function updateSortArrows() {
    // 清除所有箭头
    document.querySelectorAll('.sort-arrow').forEach(el => el.textContent = '');
    if (sortState.field) {
        const arrow = document.getElementById('sort-' + sortState.field);
        if (arrow) {
            arrow.textContent = sortState.order === 'asc' ? ' ▲' : ' ▼';
        }
    }
}

// ============================ 发送历史 ============================

function loadHistory() {
    const filter = document.getElementById('historyFilter');
    const key = filter ? filter.value : '';
    let url = '/api/sensors/history';
    if (key) url += '?key=' + encodeURIComponent(key);

    fetch(url)
        .then(r => r.json())
        .then(data => {
            historyList = Array.isArray(data) ? data : [];
            renderHistory();
            updateHistoryFilter();
        })
        .catch(err => console.error('加载历史失败:', err));
}

function renderHistory() {
    const container = document.getElementById('historyContainer');
    const filter = document.getElementById('historyFilter');
    const filterKey = filter ? filter.value : '';

    let items = historyList;
    if (filterKey) {
        items = items.filter(h => h.sensorKey === filterKey);
    }

    // ---- 保存滚动状态（在 innerHTML 替换前） ----
    const prevCount = parseInt(container.dataset.itemCount || '0');
    const currentCount = items.length;
    // 判断用户是否在底部附近（50px 内视为"在底部"）
    const atBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 50;

    if (!items || items.length === 0) {
        container.innerHTML = '<div class="text-center text-muted py-3 small"><i class="bi bi-hourglass-split"></i> 等待数据发送...</div>';
        container.dataset.itemCount = '0';
        newHistoryCount = 0;
        updateNewHistoryBadge();
        return;
    }

    container.innerHTML = items.slice(0, 50).map((h, i) => {
        const label = `${escHtml(h.deviceName || h.deviceId)} / ${escHtml(h.displayName || h.sensorKey)}`;
        const payloadStr = JSON.stringify(h.payload, null, 2);
        const collapseId = `hist-${i}-${Date.now()}`;

        return `<div class="history-item">
            <div class="history-header" data-bs-toggle="collapse" data-bs-target="#${collapseId}"
                 aria-expanded="false" aria-controls="${collapseId}">
                <span class="history-time">[${escHtml(h.time)}]</span>
                <span class="history-label">${label}</span>
                <i class="bi bi-chevron-down history-chevron ms-auto"></i>
            </div>
            <div class="collapse" id="${collapseId}">
                <div class="history-topic"><small class="text-muted">主题: ${escHtml(h.topic)}</small></div>
                <pre class="history-payload">${escHtml(payloadStr)}</pre>
            </div>
        </div>`;
    }).join('');

    if (items.length > 50) {
        container.innerHTML += `<div class="text-center text-muted py-1 small">显示最新 50 条，共 ${items.length} 条</div>`;
    }

    // ---- 恢复滚动位置 ----
    if (prevCount === 0 || atBottom) {
        // 首次加载 或 用户本来在底部 → 跟到最新
        container.scrollTop = container.scrollHeight;
        newHistoryCount = 0;
    } else if (currentCount > prevCount) {
        // 用户在浏览历史，有新消息到达 → 保留位置，计数
        newHistoryCount += currentCount - prevCount;
    }
    container.dataset.itemCount = currentCount;
    updateNewHistoryBadge();
}

function scrollToHistoryBottom() {
    const container = document.getElementById('historyContainer');
    container.scrollTop = container.scrollHeight;
    newHistoryCount = 0;
    updateNewHistoryBadge();
}

function updateNewHistoryBadge() {
    const badge = document.getElementById('newHistoryBadge');
    if (!badge) return;
    if (newHistoryCount > 0) {
        badge.textContent = `${newHistoryCount} 条新`;
        badge.style.display = 'inline-block';
    } else {
        badge.style.display = 'none';
    }
}

function clearHistory() {
    if (!confirm('确定清空所有发送历史？')) return;
    fetch('/api/sensors/history', { method: 'DELETE' })
        .then(r => r.json())
        .then(() => {
            historyList = [];
            renderHistory();
        })
        .catch(err => alert('清空失败: ' + err.message));
}

function updateHistoryFilter() {
    const filter = document.getElementById('historyFilter');
    if (!filter) return;
    const currentVal = filter.value;
    // 收集所有传感器
    const sensors = sensorList || [];
    filter.innerHTML = '<option value="">全部传感器</option>' +
        sensors.map(s => `<option value="${escHtml(s.sensorKey)}">${escHtml(s.displayName || s.sensorKey)}</option>`).join('');
    filter.value = currentVal;
}

function onHistoryFilterChange() {
    // 切换筛选条件时，重置滚动状态和计数
    newHistoryCount = 0;
    const container = document.getElementById('historyContainer');
    container.dataset.itemCount = '0';
    renderHistory();
}

// ============================ 后端同步 (保留兼容) ============================

function loadBackendUrl() {
    fetch('/api/status')
        .then(r => r.json())
        .then(status => {
            if (status.backendUrl) {
                document.getElementById('backendUrlInput').value = status.backendUrl;
            }
        })
        .catch(() => {});
}

function saveBackendUrl() {
    const url = document.getElementById('backendUrlInput').value.trim();
    if (!url) { alert('请输入后端 URL'); return; }
    fetch('/api/config/backend-url', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ backendUrl: url }),
    })
    .then(r => r.json())
    .then(resp => {
        if (resp.error) { alert(resp.error); return; }
        alert(resp.message);
    })
    .catch(err => alert('保存失败: ' + err.message));
}

function syncFromBackend() {
    const btn = document.getElementById('btnSyncBackend');
    if (btn) btn.disabled = true;
    if (btn) btn.innerHTML = '<i class="bi bi-hourglass-split"></i> 同步中...';

    const el = document.getElementById('syncResult');
    if (el) el.innerHTML = '<span class="text-info"><i class="bi bi-arrow-repeat spin"></i> 正在从后端同步...</span>';

    fetch('/api/sensors/sync-from-backend', { method: 'POST' })
        .then(r => r.json())
        .then(resp => {
            if (el) {
                el.innerHTML = `<span class="text-success"><i class="bi bi-check-circle"></i> ${resp.message}</span>`;
                setTimeout(() => el.innerHTML = '', 10000);
            }
            loadSensors();
        })
        .catch(err => {
            if (el) el.innerHTML = `<span class="text-danger">同步失败: ${err.message}</span>`;
        })
        .finally(() => {
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = '<i class="bi bi-cloud-download"></i> 从后端同步';
            }
        });
}

// ============================ 数据库同步 ============================

function loadDbConfig() {
    fetch('/api/config/database')
        .then(r => r.json())
        .then(cfg => {
            if (cfg.host) document.getElementById('dbHostInput').value = cfg.host;
            if (cfg.port) document.getElementById('dbPortInput').value = cfg.port;
            if (cfg.database) document.getElementById('dbNameInput').value = cfg.database;
            if (cfg.user) document.getElementById('dbUserInput').value = cfg.user;
        })
        .catch(() => {});
}

function saveDbConfig() {
    const data = {
        host: document.getElementById('dbHostInput').value.trim(),
        port: parseInt(document.getElementById('dbPortInput').value) || 3306,
        database: document.getElementById('dbNameInput').value.trim(),
        user: document.getElementById('dbUserInput').value.trim(),
        password: document.getElementById('dbPassInput').value,
    };

    fetch('/api/config/database', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    })
    .then(r => r.json())
    .then(resp => {
        if (resp.error) { alert(resp.error); return; }
        if (resp.warning) { alert(resp.warning); return; }
        alert(resp.message || '数据库配置已保存');
    })
    .catch(err => alert('保存失败: ' + err.message));
}

function syncFromDatabase() {
    const btn = document.getElementById('btnSyncDB');
    if (btn) {
        btn.disabled = true;
        btn.innerHTML = '<i class="bi bi-hourglass-split"></i> 同步中...';
    }

    const el = document.getElementById('dbSyncResult');
    if (el) el.innerHTML = '<span class="text-info"><i class="bi bi-arrow-repeat spin"></i> 正在从数据库同步...</span>';

    fetch('/api/sensors/sync-from-db', { method: 'POST' })
        .then(r => r.json())
        .then(resp => {
            if (el) {
                el.innerHTML = `<span class="text-success"><i class="bi bi-check-circle"></i> ${resp.message}</span>`;
                setTimeout(() => el.innerHTML = '', 10000);
            }
            loadSensors();
        })
        .catch(err => {
            if (el) el.innerHTML = `<span class="text-danger">同步失败: ${err.message}</span>`;
        })
        .finally(() => {
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = '<i class="bi bi-database"></i> 从数据库同步';
            }
        });
}

// ============================ 解绑 / 换绑 ============================

function unbindSensor(sensorKey) {
    if (!confirm(`确定要解绑传感器 ${sensorKey}？\n\n解绑后 device_id 将被设为 NULL，传感器将独立运行并可在以后重新绑定。`)) return;

    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/unbind`, { method: 'POST' })
        .then(r => r.json())
        .then(resp => {
            if (resp.error) {
                alert(resp.error);
                return;
            }
            loadSensors();
        })
        .catch(err => alert('解绑失败: ' + err.message));
}

// 缓存的设备列表
let deviceList = [];

function loadDeviceList() {
    fetch('/api/devices/list')
        .then(r => r.json())
        .then(devices => {
            deviceList = devices || [];
            const select = document.getElementById('rebindDeviceSelect');
            if (select) {
                select.innerHTML = '<option value="">-- 选择设备 --</option>' +
                    deviceList.map(d => `<option value="${escHtml(d.deviceId)}">${escHtml(d.name || d.deviceId)} (${escHtml(d.deviceId)})</option>`).join('');
            }
        })
        .catch(() => {});
}

function onDeviceSelectChange() {
    const select = document.getElementById('rebindDeviceSelect');
    const input = document.getElementById('rebindDeviceId');
    if (select && input) {
        if (select.value === '__custom__') {
            input.style.display = '';
            input.value = '';
            input.focus();
        } else {
            input.style.display = 'none';
            input.value = select.value;
        }
    }
}

function showRebindDialog(sensorKey) {
    const s = sensorList.find(s => s.sensorKey === sensorKey);
    if (!s) return;

    document.getElementById('rebindSensorLabel').value = s.displayName || sensorKey;
    document.getElementById('rebindModal').dataset.sensorKey = sensorKey;

    // 加载设备列表并显示选择器
    loadDeviceList();

    const select = document.getElementById('rebindDeviceSelect');
    const input = document.getElementById('rebindDeviceId');
    if (select) select.value = '';
    if (input) { input.style.display = 'none'; input.value = ''; }

    const modal = new bootstrap.Modal(document.getElementById('rebindModal'));
    modal.show();
}

function rebindSensor() {
    const sensorKey = document.getElementById('rebindModal').dataset.sensorKey;
    const select = document.getElementById('rebindDeviceSelect');
    const input = document.getElementById('rebindDeviceId');

    // 优先使用选择值，其次使用手动输入
    let newDeviceId = '';
    if (select && select.value && select.value !== '__custom__') {
        newDeviceId = select.value;
    } else if (input && input.value.trim()) {
        newDeviceId = input.value.trim();
    }

    if (!newDeviceId) {
        alert('请选择或输入目标设备 ID');
        return;
    }

    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/rebind`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: newDeviceId }),
    })
    .then(r => r.json())
    .then(resp => {
        if (resp.error) {
            alert(resp.error);
            return;
        }
        const modal = bootstrap.Modal.getInstance(document.getElementById('rebindModal'));
        if (modal) modal.hide();
        alert(resp.message);
        loadSensors();
    })
    .catch(err => alert('重新绑定失败: ' + err.message));
}

// ============================ 消息模板编辑 ============================

function editMessageTemplate(sensorKey) {
    const s = sensorList.find(s => s.sensorKey === sensorKey);
    if (!s) return;

    document.getElementById('templateSensorLabel').textContent = sensorKey;
    document.getElementById('msgTemplateModal').dataset.sensorKey = sensorKey;

    // 加载当前模板
    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/message-template`)
        .then(r => r.json())
        .then(data => {
            document.getElementById('msgTemplateInput').value = data.template || '';
        })
        .catch(() => {
            document.getElementById('msgTemplateInput').value = '';
        });

    const modal = new bootstrap.Modal(document.getElementById('msgTemplateModal'));
    modal.show();
}

function saveTemplate() {
    const sensorKey = document.getElementById('msgTemplateModal').dataset.sensorKey;
    const template = document.getElementById('msgTemplateInput').value;

    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/message-template`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ template: template }),
    })
    .then(r => r.json())
    .then(resp => {
        if (resp.error) {
            alert(resp.error);
            return;
        }
        const modal = bootstrap.Modal.getInstance(document.getElementById('msgTemplateModal'));
        if (modal) modal.hide();
        alert('消息模板已保存');
        loadSensors();
    })
    .catch(err => alert('保存失败: ' + err.message));
}

function resetTemplate() {
    document.getElementById('msgTemplateInput').value = '';
}

function formatTemplate() {
    const textarea = document.getElementById('msgTemplateInput');
    try {
        const obj = JSON.parse(textarea.value);
        textarea.value = JSON.stringify(obj, null, 2);
    } catch {
        alert('JSON 格式无效，请检查');
    }
}

// ============================ MQTT 配置 ============================

function loadMqttConfig() {
    fetch('/api/config/mqtt')
        .then(r => r.json())
        .then(cfg => {
            const form = document.getElementById('mqttConfigForm');
            Object.keys(cfg).forEach(key => {
                const el = form.querySelector(`[name="${key}"]`);
                if (el) el.value = cfg[key];
            });
        })
        .catch(err => console.error('加载MQTT配置失败:', err));
}

function saveMqttConfig() {
    const form = document.getElementById('mqttConfigForm');
    const data = {};
    form.querySelectorAll('input').forEach(el => {
        const val = el.value.trim();
        if (el.name === 'port') data[el.name] = parseInt(val) || 1883;
        else data[el.name] = val;
    });

    fetch('/api/config/mqtt', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    })
    .then(r => r.json())
    .then(resp => {
        if (resp.error) {
            alert(resp.error);
            return;
        }
        loadMqttConfig();
    })
    .catch(err => alert('保存失败: ' + err.message));
}

// ============================ 配置指令 ============================

function sendMockConfig() {
    const action = document.getElementById('cmdAction').value;
    const deviceId = document.getElementById('cmdDeviceId').value.trim();
    const paramsStr = document.getElementById('cmdParams').value.trim();
    let params = {};
    if (paramsStr) {
        try { params = JSON.parse(paramsStr); }
        catch (e) { document.getElementById('cmdResult').innerHTML = '<span class="text-danger">参数 JSON 格式错误</span>'; return; }
    }

    const payload = { action, deviceId, params };

    fetch('/api/mock-config/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    })
    .then(r => r.json())
    .then(resp => {
        const el = document.getElementById('cmdResult');
        el.innerHTML = `<span class="text-success"><i class="bi bi-check-circle"></i> ${resp.message || resp.warning}</span>`;
        setTimeout(() => el.innerHTML = '', 3000);
        loadSensors();
    })
    .catch(err => {
        document.getElementById('cmdResult').innerHTML = `<span class="text-danger">发送失败: ${err.message}</span>`;
    });
}

// ============================ 实时日志 (SSE) ============================

function connectLogStream() {
    const evtSource = new EventSource('/api/logs/stream');

    evtSource.onmessage = (event) => {
        try {
            const data = JSON.parse(event.data);
            if (data.type === 'connected') return;
            appendLog(data);
        } catch (e) {
            // ignore
        }
    };

    evtSource.onerror = () => {
        setTimeout(connectLogStream, 3000);
    };
}

function appendLog(record) {
    const container = document.getElementById('logContainer');
    const level = (record.level || 'INFO').toLowerCase();
    const line = document.createElement('div');
    line.className = `log-${level}`;
    line.textContent = `[${record.time}] ${record.message}`;
    container.appendChild(line);
    container.scrollTop = container.scrollHeight;
}

function clearLogs() {
    document.getElementById('logContainer').innerHTML = '';
}

// ============================ 轮询 ============================

function startPolling() {
    pollInterval = setInterval(() => {
        loadSensors();
        loadHistory();
        fetch('/api/status')
            .then(r => r.json())
            .then(status => {
                const badge = document.getElementById('mqttStatus');
                if (status.mqtt.connected) {
                    badge.className = 'badge bg-success';
                    badge.innerHTML = `<i class="bi bi-plug-fill"></i> MQTT 已连接`;
                } else {
                    badge.className = 'badge bg-danger';
                    badge.innerHTML = `<i class="bi bi-plug"></i> MQTT 断开`;
                }
            })
            .catch(() => {});
        // 更新未绑定传感器计数
        updateUnboundCount();
    }, 3000);
}

function updateUnboundCount() {
    const unbound = sensorList.filter(s => !s.deviceId).length;
    const badge = document.getElementById('unboundCount');
    if (badge) {
        if (unbound > 0) {
            badge.style.display = 'inline-block';
            badge.innerHTML = `<i class="bi bi-unlink"></i> 未绑定: ${unbound}`;
        } else {
            badge.style.display = 'none';
        }
    }
}

function startUptimeTimer() {
    uptimeInterval = setInterval(() => {
        uptimeSeconds++;
        const h = String(Math.floor(uptimeSeconds / 3600)).padStart(2, '0');
        const m = String(Math.floor((uptimeSeconds % 3600) / 60)).padStart(2, '0');
        const s = String(uptimeSeconds % 60).padStart(2, '0');
        document.getElementById('uptime').textContent = `运行: ${h}:${m}:${s}`;
    }, 1000);
}

// ============================ 工具函数 ============================

function escHtml(str) {
    if (!str) return '';
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}
