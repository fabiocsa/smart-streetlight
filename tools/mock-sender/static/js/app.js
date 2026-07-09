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
    loadDeviceConfig();
    loadAllDevices();
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
                <i class="bi bi-inbox"></i> 暂无传感器，点击右上角"添加传感器"或"注册到 Broker"
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

        // 自动发送模式标识
        const autoMode = s.autoSendMode || 'algorithm';
        const autoModeBadge = autoMode === 'fixed'
            ? '<span class="badge bg-warning text-dark ms-1" title="固定内容模式"><i class="bi bi-pin-angle-fill"></i></span>'
            : '';

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
            <td>${modeSelect}${autoModeBadge}</td>
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

// ============================ 搜索下拉组件 (设备选择器) ============================

let allDevices = [];           // 缓存的设备列表
let filteredDevices = [];      // 过滤后的设备列表
let highlightIdx = -1;         // 键盘高亮索引
let isUnboundMode = false;     // 是否为未绑定模式
let isManualDeviceInput = false; // 是否手动输入设备ID
let selectedDevice = null;     // 当前选中的设备对象

function loadAllDevices() {
    fetch('/api/devices/list')
        .then(r => r.json())
        .then(devices => {
            allDevices = devices || [];
        })
        .catch(() => { allDevices = []; });
}

function openDeviceDropdown() {
    if (isUnboundMode || isManualDeviceInput) return;
    filteredDevices = [...allDevices];
    highlightIdx = -1;
    renderDeviceDropdown();
    document.getElementById('deviceSelectWrapper').classList.add('open');
}

function filterDeviceOptions() {
    const q = document.getElementById('deviceSearchInput').value.toLowerCase().trim();
    if (!q) {
        filteredDevices = [...allDevices];
    } else {
        filteredDevices = allDevices.filter(d =>
            d.deviceId.toLowerCase().includes(q) ||
            (d.name && d.name.toLowerCase().includes(q)) ||
            (d.location && d.location.toLowerCase().includes(q))
        );
    }
    highlightIdx = -1;
    renderDeviceDropdown();
    const wrapper = document.getElementById('deviceSelectWrapper');
    if (!wrapper.classList.contains('open')) wrapper.classList.add('open');
}

function renderDeviceDropdown() {
    const dd = document.getElementById('deviceDropdown');
    if (filteredDevices.length === 0) {
        const q = document.getElementById('deviceSearchInput').value.trim();
        dd.innerHTML = q
            ? `<div class="ss-no-results">未找到匹配设备<br><small>尝试其他关键词或手动输入</small></div>
               <div class="ss-add-new" onmousedown="event.preventDefault();toggleManualDeviceInput()">
                   <i class="bi bi-plus-circle"></i> 使用 "${escHtml(q)}" 作为新设备ID</div>`
            : '<div class="ss-no-results">暂无设备数据<br><small>请检查数据库连接或手动输入</small></div>';
        return;
    }

    // ★ 修复: 不在 onmouseenter 中重新渲染 DOM（会导致 click 事件丢失）
    //    改用 CSS class 直接操作，不销毁元素。
    dd.innerHTML = filteredDevices.map((d, i) => {
        const selClass = selectedDevice && selectedDevice.deviceId === d.deviceId ? ' selected' : '';
        return `<div class="ss-option${selClass}" data-idx="${i}"
                     onmousedown="onOptionMouseDown(event, ${i})"
                     onmouseenter="onOptionHover(${i})">
            <span>
                <span class="ss-device-id">${escHtml(d.deviceId)}</span>
                <span class="ss-device-name">${escHtml(d.name || '')}</span>
            </span>
            <span class="ss-device-location">${escHtml(d.location || '')}</span>
        </div>`;
    }).join('');

    // 初始高亮
    updateDropdownHighlight();
}

// ★ 修复: 不再重新渲染，只切换 CSS class
function updateDropdownHighlight() {
    const dd = document.getElementById('deviceDropdown');
    const options = dd.querySelectorAll('.ss-option');
    options.forEach((opt, i) => {
        opt.classList.toggle('highlight', i === highlightIdx);
    });
}

function onOptionHover(idx) {
    if (highlightIdx === idx) return;
    highlightIdx = idx;
    updateDropdownHighlight();
}

function onOptionMouseDown(e, idx) {
    // ★ 修复: 使用 mousedown 而非 click，避免 blur/focus 干扰
    //    preventDefault 阻止输入框失去焦点
    e.preventDefault();
    selectDevice(idx);
}

function handleDeviceKeydown(e) {
    const wrapper = document.getElementById('deviceSelectWrapper');
    if (!wrapper.classList.contains('open')) {
        if (e.key === 'ArrowDown') { openDeviceDropdown(); e.preventDefault(); }
        return;
    }
    if (e.key === 'ArrowDown') {
        e.preventDefault();
        highlightIdx = Math.min(highlightIdx + 1, filteredDevices.length - 1);
        updateDropdownHighlight();
    } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        highlightIdx = Math.max(highlightIdx - 1, -1);
        updateDropdownHighlight();
    } else if (e.key === 'Enter') {
        e.preventDefault();
        if (highlightIdx >= 0 && highlightIdx < filteredDevices.length) {
            selectDevice(highlightIdx);
        }
    } else if (e.key === 'Escape') {
        closeDeviceDropdown();
    }
}

function selectDevice(idx) {
    if (idx < 0 || idx >= filteredDevices.length) return;
    const d = filteredDevices[idx];
    selectedDevice = d;

    // 依次设置各字段（每个都可能为空，做好防御）
    const searchInput = document.getElementById('deviceSearchInput');
    const hiddenInput = document.getElementById('deviceIdHidden');
    const nameInput = document.getElementById('deviceNameFilled');
    const locationInput = document.getElementById('locationFilled');

    if (searchInput) searchInput.value = `${d.deviceId} — ${d.name || ''}`;
    if (hiddenInput) hiddenInput.value = d.deviceId;
    if (nameInput) nameInput.value = d.name || '';
    if (locationInput) locationInput.value = d.location || '';

    autoFillDataTopic();
    closeDeviceDropdown();
}

function closeDeviceDropdown() {
    const wrapper = document.getElementById('deviceSelectWrapper');
    if (wrapper) wrapper.classList.remove('open');
    highlightIdx = -1;
}

// 点击外部关闭下拉（使用 mousedown 确保在 click 之前捕获）
document.addEventListener('mousedown', function(e) {
    const wrapper = document.getElementById('deviceSelectWrapper');
    if (wrapper && !wrapper.classList.contains('open')) return;
    if (wrapper && !wrapper.contains(e.target)) {
        // 延迟关闭，让选项的 mousedown 先执行
        setTimeout(() => closeDeviceDropdown(), 150);
    }
});

function toggleUnboundMode() {
    isUnboundMode = !isUnboundMode;
    const area = document.getElementById('deviceBindArea');
    const hint = document.getElementById('unboundHint');
    const btn = document.getElementById('btnUnboundToggle');
    const hiddenInput = document.getElementById('deviceIdHidden');

    if (isUnboundMode) {
        area.style.display = 'none';
        hint.style.display = '';
        hiddenInput.value = '';
        selectedDevice = null;
        document.getElementById('deviceSearchInput').value = '';
        document.getElementById('deviceNameFilled').value = '';
        document.getElementById('locationFilled').value = '';
        document.getElementById('dataTopicInput').value = '';
        btn.innerHTML = '绑定到设备';
        btn.title = '切换回设备绑定模式';
    } else {
        area.style.display = '';
        hint.style.display = 'none';
        document.getElementById('dataTopicInput').value = '';
        btn.innerHTML = '设为未绑定';
        btn.title = '切换为无主传感器（不绑定设备）';
    }
}

function toggleManualDeviceInput() {
    closeDeviceDropdown();
    isManualDeviceInput = !isManualDeviceInput;
    const manualDiv = document.getElementById('manualDeviceInput');
    const searchInput = document.getElementById('deviceSearchInput');
    const hiddenInput = document.getElementById('deviceIdHidden');

    if (isManualDeviceInput) {
        manualDiv.style.display = '';
        searchInput.value = '';
        searchInput.placeholder = '已切换为手动输入模式';
        searchInput.disabled = true;
        hiddenInput.value = '';
        selectedDevice = null;
        document.getElementById('deviceNameFilled').value = '';
        document.getElementById('locationFilled').value = '';
    } else {
        manualDiv.style.display = 'none';
        searchInput.disabled = false;
        searchInput.placeholder = '输入设备ID或名称搜索...';
        document.querySelector('#manualDeviceInput input').value = '';
    }
}

// ============================ 智能默认值 & 自动填充 ============================

const sensorDefaults = {
    light:    { displayName: '光照传感器', interval: 5,  min: 0,   max: 800 },
    temperature: { displayName: '温度传感器', interval: 10, min: -20, max: 60 },
    humidity: { displayName: '湿度传感器', interval: 10, min: 0,   max: 100 },
    power:    { displayName: '功率传感器', interval: 15, min: 0,   max: 200 },
};

function onSensorTypeChange(type) {
    const def = sensorDefaults[type] || sensorDefaults.light;
    // 仅在新添加模式下自动填充（编辑模式下不覆盖已有值）
    const btn = document.getElementById('btnAddSensorSubmit');
    if (btn.onclick !== addSensor && btn.onclick.toString().includes('updateSensor')) return;

    document.getElementById('displayNameInput').value = def.displayName;
    document.getElementById('intervalInput').value = def.interval;
    document.getElementById('minLuxInput').value = def.min;
    document.getElementById('maxLuxInput').value = def.max;
    autoFillDataTopic();
}

function autoFillDataTopic() {
    const deviceId = getEffectiveDeviceId();
    const sensorType = document.querySelector('[name="sensorType"]').value || 'light';
    if (deviceId) {
        document.getElementById('dataTopicInput').value =
            `streetlight/${deviceId}/sensor/data`;
    } else if (isUnboundMode) {
        document.getElementById('dataTopicInput').value = '';
    }
}

function getEffectiveDeviceId() {
    if (isUnboundMode) return '';
    if (isManualDeviceInput) {
        return document.querySelector('[name="deviceIdManual"]').value.trim();
    }
    return document.getElementById('deviceIdHidden').value.trim();
}

function onDataFormatChange(format) {
    if (format === 'custom') {
        // 提示用户使用消息模板编辑器
        const sensorKey = document.getElementById('btnAddSensorSubmit').dataset.sensorKey;
        if (sensorKey) {
            alert('保存后可在传感器列表点击 < > 按钮编辑自定义消息模板');
        }
    }
}

// ============================ 添加 / 编辑传感器 ============================

function addSensor() {
    const deviceId = getEffectiveDeviceId();

    if (!deviceId && !isUnboundMode) {
        alert('请选择或输入设备 ID，或点击"设为未绑定"创建无主传感器');
        return;
    }

    const form = document.getElementById('addSensorForm');
    const data = {
        deviceId: deviceId,
        deviceName: form.deviceName.value.trim() || '',
        displayName: form.displayName.value.trim() || form.sensorType.value || '',
        sensorType: form.sensorType.value,
        dataTopic: form.dataTopic.value.trim() || '',
        name: form.displayName.value.trim() || deviceId || 'unbound',
        location: form.location.value.trim() || '',
        interval: parseInt(form.interval.value) || 5,
        controlMode: form.controlMode.value,
        dataRange: {
            min: parseFloat(form.min.value) || 0,
            max: parseFloat(form.max.value) || 800,
        },
    };

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
        resetSensorForm();
        loadSensors();
    })
    .catch(err => alert('添加失败: ' + err.message));
}

function removeSensor(sensorKey) {
    if (!confirm(`确定删除传感器 ${sensorKey}？\n\n此操作不可恢复。如需保留记录，请使用"解绑"功能。`)) return;

    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}`, { method: 'DELETE' })
        .then(r => r.json())
        .then(() => loadSensors())
        .catch(err => alert('删除失败: ' + err.message));
}

function editSensor(sensorKey) {
    // 先加载设备列表
    loadAllDevices();

    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}`)
        .then(r => r.json())
        .then(s => {
            if (s.error) { alert(s.error); return; }
            const form = document.getElementById('addSensorForm');
            const title = document.getElementById('addSensorModalTitle');
            title.innerHTML = '<i class="bi bi-pencil"></i> 编辑传感器';

            // 设备选择
            if (s.deviceId) {
                isUnboundMode = false;
                document.getElementById('deviceBindArea').style.display = '';
                document.getElementById('unboundHint').style.display = 'none';
                document.getElementById('btnUnboundToggle').innerHTML = '设为未绑定';
                selectedDevice = { deviceId: s.deviceId, name: s.deviceName || '', location: s.location || '' };
                document.getElementById('deviceSearchInput').value = `${s.deviceId} — ${s.deviceName || ''}`;
                document.getElementById('deviceIdHidden').value = s.deviceId;
                document.getElementById('deviceNameFilled').value = s.deviceName || '';
                document.getElementById('locationFilled').value = s.location || '';
                document.getElementById('deviceSearchInput').disabled = true; // 编辑模式不可改设备
            } else {
                isUnboundMode = true;
                document.getElementById('deviceBindArea').style.display = 'none';
                document.getElementById('unboundHint').style.display = '';
                document.getElementById('btnUnboundToggle').innerHTML = '绑定到设备';
                document.getElementById('deviceIdHidden').value = '';
            }

            // 传感器字段
            form.sensorType.value = s.sensorType || 'light';
            form.displayName.value = s.displayName || '';
            form.dataTopic.value = s.dataTopic || '';
            form.location.value = s.location || '';
            form.interval.value = s.interval || 5;
            form.controlMode.value = s.controlMode || 'auto';
            form.min.value = s.dataRange?.min || 0;
            form.max.value = s.dataRange?.max || 800;
            if (s.messageTemplate) {
                form.dataFormat.value = 'custom';
            } else {
                form.dataFormat.value = 'standard';
            }

            const btn = document.getElementById('btnAddSensorSubmit');
            btn.innerHTML = '<i class="bi bi-check-lg"></i> 保存';
            btn.onclick = updateSensor;
            btn.dataset.sensorKey = sensorKey;
        })
        .catch(err => alert('获取传感器信息失败: ' + err.message));
}

function updateSensor() {
    const sensorKey = document.getElementById('btnAddSensorSubmit').dataset.sensorKey;
    const form = document.getElementById('addSensorForm');
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
        resetSensorForm();
        loadSensors();
    })
    .catch(err => alert('更新失败: ' + err.message));
}

function resetSensorForm() {
    const form = document.getElementById('addSensorForm');
    form.reset();
    document.getElementById('deviceSearchInput').disabled = false;
    document.getElementById('deviceSearchInput').value = '';
    document.getElementById('deviceSearchInput').placeholder = '输入设备ID或名称搜索...';
    document.getElementById('deviceIdHidden').value = '';
    document.getElementById('deviceNameFilled').value = '';
    document.getElementById('locationFilled').value = '';
    document.getElementById('displayNameInput').value = '';
    document.getElementById('dataTopicInput').value = '';
    document.getElementById('intervalInput').value = '5';
    document.getElementById('minLuxInput').value = '0';
    document.getElementById('maxLuxInput').value = '800';
    document.getElementById('manualDeviceInput').style.display = 'none';
    isUnboundMode = false;
    isManualDeviceInput = false;
    selectedDevice = null;
    document.getElementById('deviceBindArea').style.display = '';
    document.getElementById('unboundHint').style.display = 'none';
    document.getElementById('btnUnboundToggle').innerHTML = '设为未绑定';
    form.dataFormat.value = 'standard';

    const title = document.getElementById('addSensorModalTitle');
    title.innerHTML = '<i class="bi bi-plus-circle"></i> 添加传感器';

    const btn = document.getElementById('btnAddSensorSubmit');
    btn.innerHTML = '<i class="bi bi-plus-lg"></i> 添加传感器';
    btn.onclick = addSensor;
    delete btn.dataset.sensorKey;
}

// 模态框关闭时重置
document.getElementById('addSensorModal').addEventListener('hidden.bs.modal', () => {
    resetSensorForm();
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
    // 已废弃：改用 registerDevice() 通过 MQTT 注册到 Broker
    registerDevice();
}

// ============================ 设备注册 ============================

function loadDeviceConfig() {
    fetch('/api/device/config')
        .then(r => r.json())
        .then(cfg => {
            if (cfg.deviceId) {
                const el = document.getElementById('deviceIdInput');
                if (el) el.value = cfg.deviceId;
            }
            if (cfg.name) {
                const el = document.getElementById('deviceNameInput');
                if (el) el.value = cfg.name;
            }
            if (cfg.location) {
                const el = document.getElementById('deviceLocationInput');
                if (el) el.value = cfg.location;
            }
        })
        .catch(() => {});
}

function saveDeviceConfig() {
    const data = {
        deviceId: (document.getElementById('deviceIdInput')?.value || '').trim(),
        name: (document.getElementById('deviceNameInput')?.value || '').trim(),
        location: (document.getElementById('deviceLocationInput')?.value || '').trim(),
    };

    fetch('/api/device/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    })
    .then(r => r.json())
    .then(resp => {
        if (resp.error) { alert(resp.error); return; }
        alert(resp.message || '设备配置已保存');
    })
    .catch(err => alert('保存失败: ' + err.message));
}

function registerDevice() {
    const btn = document.getElementById('btnRegister');
    if (btn) {
        btn.disabled = true;
        btn.innerHTML = '<i class="bi bi-hourglass-split"></i> 注册中...';
    }

    const el = document.getElementById('deviceRegResult');
    if (el) el.innerHTML = '<span class="text-info"><i class="bi bi-arrow-repeat spin"></i> 正在向 Broker 注册设备...</span>';

    fetch('/api/device/register', { method: 'POST' })
        .then(r => r.json())
        .then(resp => {
            if (el) {
                if (resp.success) {
                    el.innerHTML = `<span class="text-success"><i class="bi bi-check-circle"></i> ${resp.message}</span>`;
                } else {
                    el.innerHTML = `<span class="text-danger">${resp.message}</span>`;
                }
                setTimeout(() => el.innerHTML = '', 10000);
            }
            loadSensors();
        })
        .catch(err => {
            if (el) el.innerHTML = `<span class="text-danger">注册失败: ${err.message}</span>`;
        })
        .finally(() => {
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = '<i class="bi bi-broadcast"></i> 注册到 Broker';
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

// ============================ 消息模板 & 自动发送配置 ============================

let currentAutoSendMode = 'algorithm';

function editMessageTemplate(sensorKey) {
    const s = sensorList.find(s => s.sensorKey === sensorKey);
    if (!s) return;

    document.getElementById('templateSensorLabel').textContent =
        `${sensorKey} (${s.sensorType || 'light'})`;
    document.getElementById('msgTemplateModal').dataset.sensorKey = sensorKey;

    // 加载当前模板和自动发送配置
    fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/message-template`)
        .then(r => r.json())
        .then(data => {
            document.getElementById('msgTemplateInput').value = data.template || '';
            // 自动发送模式
            const mode = data.autoSendMode || 'algorithm';
            currentAutoSendMode = mode;
            document.getElementById('modeAlgorithm').checked = (mode === 'algorithm');
            document.getElementById('modeFixed').checked = (mode === 'fixed');
            document.getElementById('autoSendContentInput').value = data.autoSendContent || '';
            // 更新 UI 状态
            applyAutoSendModeUI(mode);
        })
        .catch(() => {
            document.getElementById('msgTemplateInput').value = '';
            document.getElementById('autoSendContentInput').value = '';
            document.getElementById('modeAlgorithm').checked = true;
            document.getElementById('modeFixed').checked = false;
            applyAutoSendModeUI('algorithm');
        });

    const modal = new bootstrap.Modal(document.getElementById('msgTemplateModal'));
    modal.show();
}

function onAutoSendModeChange(mode) {
    currentAutoSendMode = mode;
    applyAutoSendModeUI(mode);
}

function applyAutoSendModeUI(mode) {
    const templateSection = document.getElementById('templateSection');
    const fixedDesc = document.getElementById('fixedModeDesc');
    const algoDesc = document.getElementById('algorithmModeDesc');
    const fixedBadge = document.getElementById('fixedContentBadge');
    const contentInput = document.getElementById('autoSendContentInput');

    if (mode === 'fixed') {
        if (templateSection) templateSection.style.opacity = '0.5';
        if (fixedDesc) fixedDesc.style.display = '';
        if (algoDesc) algoDesc.style.display = 'none';
        if (fixedBadge) { fixedBadge.className = 'badge bg-warning text-dark'; fixedBadge.textContent = '固定模式生效中'; }
    } else {
        if (templateSection) templateSection.style.opacity = '1';
        if (fixedDesc) fixedDesc.style.display = 'none';
        if (algoDesc) algoDesc.style.display = '';
        if (fixedBadge) { fixedBadge.className = 'badge bg-secondary'; fixedBadge.textContent = '仅固定模式生效'; }
    }
}

function generateSampleContent() {
    const sensorKey = document.getElementById('msgTemplateModal').dataset.sensorKey;
    const s = sensorList.find(s => s.sensorKey === sensorKey);
    if (!s) return;

    const sensorType = s.sensorType || 'light';
    const deviceId = s.deviceId || 'SL-001';

    fetch(`/api/sensors/generate-sample/${encodeURIComponent(sensorType)}?deviceId=${encodeURIComponent(deviceId)}`)
        .then(r => r.json())
        .then(data => {
            document.getElementById('autoSendContentInput').value = data.sample || '';
            formatAutoSendContent();
        })
        .catch(err => alert('生成失败: ' + err.message));
}

function formatAutoSendContent() {
    const textarea = document.getElementById('autoSendContentInput');
    try {
        const obj = JSON.parse(textarea.value);
        textarea.value = JSON.stringify(obj, null, 2);
        document.getElementById('autoSendValidation').innerHTML =
            '<span class="text-success"><i class="bi bi-check-circle"></i> JSON 格式正确</span>';
    } catch (e) {
        document.getElementById('autoSendValidation').innerHTML =
            `<span class="text-danger"><i class="bi bi-x-circle"></i> JSON 格式错误: ${e.message}</span>`;
    }
}

function validateAutoSendContent() {
    const textarea = document.getElementById('autoSendContentInput');
    try {
        JSON.parse(textarea.value);
        document.getElementById('autoSendValidation').innerHTML =
            '<span class="text-success"><i class="bi bi-check-circle"></i> JSON 格式正确 ✓</span>';
    } catch (e) {
        document.getElementById('autoSendValidation').innerHTML =
            `<span class="text-danger"><i class="bi bi-x-circle"></i> JSON 格式错误: ${e.message}</span>`;
    }
}

function loadPresetTemplate(type) {
    fetch(`/api/sensors/generate-sample/${encodeURIComponent(type)}?deviceId=SL-001`)
        .then(r => r.json())
        .then(data => {
            document.getElementById('autoSendContentInput').value = data.sample || '';
            formatAutoSendContent();
        })
        .catch(err => alert('加载模板失败: ' + err.message));
}

function saveAllTemplateSettings() {
    const sensorKey = document.getElementById('msgTemplateModal').dataset.sensorKey;
    const template = document.getElementById('msgTemplateInput').value;
    const mode = currentAutoSendMode;
    const fixedContent = document.getElementById('autoSendContentInput').value;

    const statusEl = document.getElementById('templateSaveStatus');
    statusEl.textContent = '保存中...';

    // 保存消息模板
    const p1 = fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/message-template`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ template: template }),
    });

    // 保存自动发送配置
    const p2 = fetch(`/api/sensors/${encodeURIComponent(sensorKey)}/auto-send-config`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ autoSendMode: mode, autoSendContent: fixedContent }),
    });

    Promise.all([p1, p2])
        .then(() => {
            statusEl.textContent = '已保存 ✓';
            setTimeout(() => {
                const modal = bootstrap.Modal.getInstance(document.getElementById('msgTemplateModal'));
                if (modal) modal.hide();
                statusEl.textContent = '';
                loadSensors();
            }, 600);
        })
        .catch(err => {
            statusEl.textContent = '保存失败';
            alert('保存失败: ' + err.message);
        });
}

function resetTemplate() {
    document.getElementById('msgTemplateInput').value = '';
    document.getElementById('autoSendContentInput').value = '';
    document.getElementById('modeAlgorithm').checked = true;
    document.getElementById('modeFixed').checked = false;
    currentAutoSendMode = 'algorithm';
    applyAutoSendModeUI('algorithm');
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
