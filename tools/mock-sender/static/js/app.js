/**
 * 智慧路灯 Mock 数据发送器 - 前端交互逻辑
 */

// ============================ 全局状态 ============================
let sensorList = [];
let uptimeSeconds = 0;
let uptimeInterval = null;
let pollInterval = null;

// ============================ 页面初始化 ============================
document.addEventListener('DOMContentLoaded', () => {
    loadSensors();
    loadMqttConfig();
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
            <td colspan="9" class="text-center text-muted py-4">
                <i class="bi bi-inbox"></i> 暂无传感器，点击右上角添加
            </td>
        </tr>`;
        return;
    }

    tbody.innerHTML = sensors.map(s => {
        const statusBadge = s.running
            ? '<span class="badge bg-success status-pulse"><i class="bi bi-check-circle"></i> 运行中</span>'
            : '<span class="badge bg-secondary"><i class="bi bi-pause-circle"></i> 已停止</span>';

        const lightBadge = s.lightStatus === 'on'
            ? '<span class="badge bg-warning text-dark"><i class="bi bi-lightbulb-fill"></i> 开</span>'
            : '<span class="badge bg-dark"><i class="bi bi-lightbulb"></i> 关</span>';

        const modeBadge = s.controlMode === 'auto'
            ? '<span class="badge bg-info"><i class="bi bi-robot"></i> 自动</span>'
            : '<span class="badge bg-secondary"><i class="bi bi-person"></i> 手动</span>';

        return `<tr>
            <td><code>${escHtml(s.deviceId)}</code></td>
            <td>${escHtml(s.name)}</td>
            <td>${escHtml(s.location)}</td>
            <td>${statusBadge}</td>
            <td>${s.interval}s</td>
            <td>${lightBadge}</td>
            <td>${modeBadge}</td>
            <td>${s.publishCount}</td>
            <td class="text-nowrap">
                <button class="btn btn-sm btn-outline-danger" onclick="removeSensor('${escHtml(s.deviceId)}')"
                        title="删除传感器">
                    <i class="bi bi-trash"></i>
                </button>
                <button class="btn btn-sm btn-outline-primary" onclick="editSensor('${escHtml(s.deviceId)}')"
                        title="编辑配置" data-bs-toggle="modal" data-bs-target="#addSensorModal">
                    <i class="bi bi-pencil"></i>
                </button>
            </td>
        </tr>`;
    }).join('');
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
        name: form.name.value.trim() || form.deviceId.value.trim(),
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
        // 关闭模态框
        const modal = bootstrap.Modal.getInstance(document.getElementById('addSensorModal'));
        if (modal) modal.hide();
        form.reset();
        loadSensors();
    })
    .catch(err => alert('添加失败: ' + err.message));
}

function removeSensor(deviceId) {
    if (!confirm(`确定删除传感器 ${deviceId}？`)) return;

    fetch(`/api/sensors/${encodeURIComponent(deviceId)}`, { method: 'DELETE' })
        .then(r => r.json())
        .then(() => loadSensors())
        .catch(err => alert('删除失败: ' + err.message));
}

function editSensor(deviceId) {
    fetch(`/api/sensors/${encodeURIComponent(deviceId)}`)
        .then(r => r.json())
        .then(s => {
            const form = document.getElementById('addSensorForm');
            form.deviceId.value = s.deviceId;
            form.deviceId.readOnly = true;
            form.name.value = s.name;
            form.location.value = s.location;
            form.interval.value = s.interval;
            form.controlMode.value = s.controlMode;
            form.min.value = s.dataRange?.min || 0;
            form.max.value = s.dataRange?.max || 800;
            // 改按钮文字
            const btn = document.querySelector('#addSensorModal .btn-primary');
            btn.innerHTML = '<i class="bi bi-check-lg"></i> 保存';
            btn.onclick = updateSensor;
            btn.dataset.deviceId = deviceId;
        });
}

function updateSensor() {
    const form = document.getElementById('addSensorForm');
    const deviceId = form.deviceId.value.trim();
    const data = {
        name: form.name.value.trim(),
        location: form.location.value.trim(),
        interval: parseInt(form.interval.value) || 5,
        controlMode: form.controlMode.value,
        dataRange: {
            min: parseFloat(form.min.value) || 0,
            max: parseFloat(form.max.value) || 800,
        },
    };

    fetch(`/api/sensors/${encodeURIComponent(deviceId)}/config`, {
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
        // 恢复按钮
        const btn = document.querySelector('#addSensorModal .btn-primary');
        btn.innerHTML = '<i class="bi bi-plus-lg"></i> 添加';
        btn.onclick = addSensor;
        loadSensors();
    });
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
        el.innerHTML = `<span class="text-success"><i class="bi bi-check-circle"></i> ${resp.message}</span>`;
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
        // 断线自动重连
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
    }, 3000);
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
