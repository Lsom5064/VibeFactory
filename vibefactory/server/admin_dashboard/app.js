const state = {
  data: null,
  search: "",
  status: "",
  selectedTaskId: "",
};

const el = (id) => document.getElementById(id);

document.addEventListener("DOMContentLoaded", () => {
  el("adminToken").value = localStorage.getItem("vf_admin_token") || "";
  el("adminToken").addEventListener("input", () => {
    localStorage.setItem("vf_admin_token", el("adminToken").value.trim());
  });
  el("refreshButton").addEventListener("click", loadDashboard);
  el("searchInput").addEventListener("input", (event) => {
    state.search = event.target.value.trim().toLowerCase();
    render();
  });
  el("statusFilter").addEventListener("change", (event) => {
    state.status = event.target.value;
    render();
  });
  el("closeTaskDetail").addEventListener("click", () => {
    state.selectedTaskId = "";
    el("taskDetailPanel").classList.remove("visible");
  });
  loadDashboard();
});

async function loadDashboard() {
  el("dbStatus").textContent = "로딩 중";
  const headers = {};
  const token = el("adminToken").value.trim();
  if (token) headers["x-admin-token"] = token;

  try {
    const response = await fetch("/admin/dashboard/data", { headers });
    if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
    state.data = await response.json();
    el("dbStatus").textContent = "DB 연결됨";
    el("updatedAt").textContent = new Date().toLocaleString("ko-KR");
    syncStatusFilter();
    render();
  } catch (error) {
    el("dbStatus").textContent = "로드 실패";
    el("updatedAt").textContent = String(error.message || error);
  }
}

function syncStatusFilter() {
  const select = el("statusFilter");
  const selected = select.value;
  select.innerHTML = '<option value="">전체 상태</option>';
  for (const row of state.data.status_counts || []) {
    const option = document.createElement("option");
    option.value = row.status;
    option.textContent = `${row.status} (${formatNumber(row.count)})`;
    select.appendChild(option);
  }
  select.value = selected;
}

function render() {
  if (!state.data) return;
  renderOverview(state.data.overview || {});
  renderStatusBars(state.data.status_counts || []);
  renderTokenTimeline(state.data.token_timeline || []);
  renderUsers(state.data.users || []);
  renderTasks(state.data.recent_tasks || []);
  renderAppAiUsage(state.data.app_ai_overview || {}, state.data.app_ai_usage_by_app || []);
  renderEvents(state.data.event_counts || []);
  renderTopTokens(state.data.top_token_tasks || []);
  renderRuntimeErrors(state.data.runtime_errors || []);
  renderAppLlmUsage(state.data.app_llm_usage || []);
}

function renderAppAiUsage(overview, rows) {
  el("appAiEnabledApps").textContent = `${formatNumber(overview.enabled_app_count)} / ${formatNumber(overview.configured_app_count)}`;
  el("appAiTodayRequests").textContent = formatNumber(overview.today_request_count);
  el("appAiTodayTokens").textContent = formatNumber(overview.today_tokens);
  el("appAiLimitErrors").textContent = formatNumber(overview.limit_error_count);
  el("appAiSummary").textContent =
    `전체 요청 ${formatNumber(overview.total_request_count)} · 전체 토큰 ${formatNumber(overview.total_tokens)}`;

  const body = el("appAiUsageBody");
  body.innerHTML = "";
  for (const row of rows) {
    body.appendChild(tableRow([
      appAiAppCell(row),
      mono(row.identity),
      riskPill(row.risk_level, row.enabled),
      limitCell(row.today_request_count, row.daily_request_limit, row.request_used_percent),
      limitCell(row.today_tokens, row.daily_token_limit, row.token_used_percent),
      `${formatNumber(row.total_request_count)} calls<br><span class="muted">${formatNumber(row.total_tokens)} tokens</span>`,
      formatDate(row.last_used_at),
    ]));
  }
}

function appAiAppCell(row) {
  return `
    <button class="link-button" type="button" data-task-id="${escapeHtml(row.task_id)}">${escapeHtml(row.app_name || "앱 이름 없음")}</button>
    <div class="muted mono">${escapeHtml(row.package_name || "")}</div>
    <div class="muted">${escapeHtml(row.model || "")}</div>
  `;
}

function limitCell(value, limit, percent) {
  return `
    <div class="limit-cell">
      <div><strong>${formatNumber(value)}</strong><span class="muted"> / ${formatNumber(limit)}</span></div>
      <div class="mini-bar"><span style="width:${Math.max(2, Number(percent || 0))}%"></span></div>
      <div class="muted">${formatNumber(percent)}%</div>
    </div>
  `;
}

function riskPill(riskLevel, enabled) {
  const label = !enabled ? "비활성" : {
    critical: "위험",
    watch: "주의",
    normal: "정상",
  }[riskLevel] || "정상";
  return `<span class="risk ${escapeHtml(riskLevel || "normal")}">${label}</span>`;
}

function renderOverview(overview) {
  el("totalTasks").textContent = formatNumber(overview.total_tasks);
  el("userCount").textContent = formatNumber(overview.user_count);
  el("totalTokens").textContent = formatNumber(overview.total_tokens || overview.recorded_total_tokens);
  el("avgDuration").textContent = formatDuration(overview.avg_terminal_duration_seconds);
  el("statusSummary").textContent =
    `성공 ${formatNumber(overview.success_tasks)} · 실패 ${formatNumber(overview.failed_tasks)} · 진행 ${formatNumber(overview.running_tasks)}`;
  el("usageRecords").textContent = `usage records ${formatNumber(overview.usage_record_count)} · events ${formatNumber(overview.event_count)}`;
}

function renderStatusBars(rows) {
  const root = el("statusBars");
  root.innerHTML = "";
  const max = Math.max(1, ...rows.map((row) => Number(row.count || 0)));
  for (const row of rows) {
    root.appendChild(barRow(row.status, row.count, max));
  }
}

function renderTokenTimeline(rows) {
  const root = el("tokenTimeline");
  root.innerHTML = "";
  const ordered = [...rows].sort((left, right) => String(right.day).localeCompare(String(left.day)));
  const max = Math.max(1, ...ordered.map((row) => Number(row.total_tokens || 0)));
  for (const row of ordered) {
    root.appendChild(tokenTimelineRow(row, max));
  }
}

function tokenTimelineRow(row, max) {
  const item = document.createElement("article");
  item.className = "token-row";
  const tokens = Number(row.total_tokens || 0);
  const width = Math.max(2, Math.round((tokens / max) * 100));
  item.innerHTML = `
    <div class="token-row-main">
      <div class="token-row-label">
        <strong>${escapeHtml(row.day || "-")}</strong>
        <span>${formatNumber(row.task_count)} tasks</span>
      </div>
      <div class="token-bar"><span style="width:${width}%"></span></div>
      <strong class="token-value">${formatNumber(tokens)}</strong>
    </div>
  `;
  return item;
}

function renderUsers(rows) {
  el("userCountLabel").textContent = `${formatNumber(rows.length)} users`;
  const body = el("usersBody");
  body.innerHTML = "";
  for (const row of rows) {
    body.appendChild(tableRow([
      mono(row.identity),
      formatNumber(row.task_count),
      formatNumber(row.success_count),
      formatNumber(row.failed_count),
      formatNumber(row.total_tokens),
      formatDate(row.last_seen_at),
    ]));
  }
}

function renderTasks(rows) {
  const filtered = rows.filter((row) => {
    const haystack = [
      row.app_name,
      row.identity,
      row.status,
      row.prompt_preview,
      row.package_name,
      row.task_id,
    ].join(" ").toLowerCase();
    return (!state.status || row.status === state.status) && (!state.search || haystack.includes(state.search));
  });

  el("taskCountLabel").textContent = `${formatNumber(filtered.length)} / ${formatNumber(rows.length)} tasks`;
  const body = el("tasksBody");
  body.innerHTML = "";
  for (const row of filtered) {
    body.appendChild(tableRow([
      appCell(row),
      mono(row.identity),
      statusPill(row.status),
      textBlock(row.prompt_preview, row.message_preview),
      tokenCell(row),
      `${formatDuration(row.duration_seconds)}<br><span class="muted">${formatDate(row.created_at)}</span>`,
      row.apk_url ? `<a href="${escapeHtml(row.apk_url)}" target="_blank" rel="noreferrer">APK</a>` : '<span class="muted">-</span>',
    ]));
  }
}

function renderEvents(rows) {
  const root = el("eventList");
  root.innerHTML = "";
  const max = Math.max(1, ...rows.map((row) => Number(row.count || 0)));
  for (const row of rows) {
    root.appendChild(barRow(row.event_type, row.count, max));
  }
}

function renderTopTokens(rows) {
  const root = el("topTokenTasks");
  root.innerHTML = "";
  for (const row of rows) {
    root.appendChild(listRow(
      `<strong>${escapeHtml(row.app_name)}</strong><div class="muted mono">${escapeHtml(row.identity)}</div>`,
      `<strong>${formatNumber(row.total_tokens)}</strong><div class="muted">${escapeHtml(row.status)}</div>`
    ));
  }
}

function renderRuntimeErrors(rows) {
  el("runtimeErrorCount").textContent = `${formatNumber(rows.length)} errors`;
  const root = el("runtimeErrors");
  root.innerHTML = "";
  if (!rows.length) {
    root.innerHTML = '<div class="muted">최근 런타임 오류가 없습니다.</div>';
    return;
  }
  for (const row of rows) {
    const card = document.createElement("article");
    card.className = "error-card";
    card.innerHTML = `
      <strong>${escapeHtml(row.message_text || row.error_message || "런타임 오류")}</strong>
      <span class="muted">${escapeHtml(row.app_name || "앱 이름 없음")} · ${escapeHtml(row.identity || "")}</span>
      <span class="mono">${escapeHtml(row.package_name || "")}</span>
      <span class="muted">${formatDate(row.created_at)}</span>
    `;
    root.appendChild(card);
  }
}

function renderAppLlmUsage(rows) {
  const root = el("appLlmUsage");
  root.innerHTML = "";
  if (!rows.length) {
    root.innerHTML = '<div class="muted">앱 내부 LLM 호출 기록이 없습니다.</div>';
    return;
  }
  for (const row of rows) {
    root.appendChild(listRow(
      `<strong>${escapeHtml(row.package_name)}</strong><div class="muted mono">${escapeHtml(row.task_id)}</div>`,
      `<strong>${formatNumber(row.total_tokens)}</strong><div class="muted">${escapeHtml(row.status)} · ${formatDate(row.created_at)}</div>`
    ));
  }
}

function barRow(label, value, max, note = "") {
  const row = document.createElement("div");
  row.className = "bar-row";
  const width = Math.max(2, Math.round((Number(value || 0) / max) * 100));
  row.innerHTML = `
    <div title="${escapeHtml(label)}">${escapeHtml(label)}</div>
    <div class="bar-track"><div class="bar-fill" style="width:${width}%"></div></div>
    <div class="mono">${formatNumber(value)}</div>
    ${note ? `<div class="muted">${escapeHtml(note)}</div>` : ""}
  `;
  return row;
}

function tableRow(cells) {
  const tr = document.createElement("tr");
  tr.innerHTML = cells.map((cell) => `<td>${cell}</td>`).join("");
  return tr;
}

function listRow(left, right) {
  const row = document.createElement("div");
  row.className = "list-row";
  row.innerHTML = `<div>${left}</div><div>${right}</div>`;
  return row;
}

function appCell(row) {
  return `
    <button class="link-button" type="button" data-task-id="${escapeHtml(row.task_id)}">${escapeHtml(row.app_name || "앱 이름 없음")}</button>
    <div class="muted mono">${escapeHtml(row.task_id)}</div>
    <div class="muted">${escapeHtml(row.package_name || "")}</div>
  `;
}

document.addEventListener("click", (event) => {
  const button = event.target.closest("[data-task-id]");
  if (!button) return;
  loadTaskDetail(button.dataset.taskId);
});

async function loadTaskDetail(taskId) {
  if (!taskId) return;
  window.location.href = `/admin/dashboard/task/${encodeURIComponent(taskId)}`;
}

function renderTaskDetail(detail) {
  const task = detail.task || {};
  el("taskDetailTitle").textContent = task.app_name || "앱 이름 없음";
  el("taskDetailMeta").textContent =
    `${task.task_id || ""} · ${task.identity || ""} · ${formatDate(task.created_at)} · ${formatDuration(task.duration_seconds)}`;
  el("taskDetailSummary").innerHTML = `
    <div>${statusPill(task.status)}</div>
    <div><strong>패키지</strong><span class="mono">${escapeHtml(task.package_name || "-")}</span></div>
    <div><strong>토큰</strong><span>${formatNumber(task.total_tokens)}</span></div>
    <div><strong>APK</strong><span>${task.apk_url ? `<a href="${escapeHtml(task.apk_url)}" target="_blank" rel="noreferrer">다운로드</a>` : "-"}</span></div>
    <div class="summary-wide"><strong>초기 요청</strong><span>${escapeHtml(task.prompt_preview || task.prompt || "")}</span></div>
  `;

  renderConversation(detail.events || []);
  renderTaskUsageRecords(detail.usage_records || []);
  renderTaskSnapshots(detail.snapshots || []);
}

function renderConversation(events) {
  const root = el("taskConversation");
  root.innerHTML = "";
  if (!events.length) {
    root.innerHTML = '<div class="muted">저장된 채팅/이벤트가 없습니다.</div>';
    return;
  }
  for (const event of events) {
    const item = document.createElement("article");
    item.className = `conversation-item ${escapeHtml(event.kind || "system")}`;
    item.innerHTML = `
      <div class="conversation-meta">
        <span>${escapeHtml(labelForKind(event.kind))}</span>
        <span>${formatDate(event.created_at)}</span>
      </div>
      <div class="conversation-content">${escapeHtml(event.content || "")}</div>
      <div class="conversation-detail">${escapeHtml(event.detail || event.event_type || "")}</div>
    `;
    root.appendChild(item);
  }
}

function renderTaskUsageRecords(rows) {
  const root = el("taskUsageRecords");
  root.innerHTML = "";
  if (!rows.length) {
    root.innerHTML = '<div class="muted">토큰 사용 기록이 없습니다.</div>';
    return;
  }
  for (const row of rows) {
    root.appendChild(listRow(
      `<strong>${escapeHtml(row.source)} · ${escapeHtml(row.model)}</strong><div class="muted">${formatDate(row.created_at)}</div>`,
      `<strong>${formatNumber(row.total_tokens)}</strong><div class="muted">in ${formatNumber(row.input_tokens)} · out ${formatNumber(row.output_tokens)}</div>`
    ));
  }
}

function renderTaskSnapshots(rows) {
  const root = el("taskSnapshots");
  root.innerHTML = "";
  if (!rows.length) return;
  root.appendChild(sectionLabel("버전 스냅샷"));
  for (const row of rows) {
    root.appendChild(listRow(
      `<strong>${escapeHtml(row.revision_label)}</strong><div class="muted">${escapeHtml(row.source)}</div>`,
      `<div class="muted">${formatDate(row.created_at)}</div>`
    ));
  }
}

function sectionLabel(text) {
  const node = document.createElement("div");
  node.className = "section-label";
  node.textContent = text;
  return node;
}

function labelForKind(kind) {
  return {
    user: "사용자",
    assistant: "AI",
    status: "상태",
    error: "오류",
    system: "시스템",
  }[kind] || "이벤트";
}

function textBlock(primary, secondary) {
  return `
    <div>${escapeHtml(primary || "")}</div>
    ${secondary ? `<div class="muted">${escapeHtml(secondary)}</div>` : ""}
  `;
}

function tokenCell(row) {
  return `
    <strong>${formatNumber(row.total_tokens)}</strong>
    <div class="muted">in ${formatNumber(row.input_tokens)} · out ${formatNumber(row.output_tokens)}</div>
  `;
}

function statusPill(status) {
  const cls = String(status || "").toLowerCase().replace(/\s+/g, "-");
  return `<span class="status ${escapeHtml(cls)}">${escapeHtml(status || "-")}</span>`;
}

function mono(value) {
  return `<span class="mono">${escapeHtml(value || "-")}</span>`;
}

function formatNumber(value) {
  const number = Number(value || 0);
  return Number.isFinite(number) ? number.toLocaleString("ko-KR") : "0";
}

function formatDate(value) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
}

function formatDuration(value) {
  const seconds = Math.max(0, Math.round(Number(value || 0)));
  if (!seconds) return "-";
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  if (minutes < 60) return `${minutes}분 ${rest}초`;
  const hours = Math.floor(minutes / 60);
  return `${hours}시간 ${minutes % 60}분`;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
