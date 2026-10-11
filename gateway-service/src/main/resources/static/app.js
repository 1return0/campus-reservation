/* Browser demo only. Authorization is always enforced by Gateway and the services. */
const $ = (id) => document.getElementById(id);
let accessToken = null;
let roles = [];
let feedbackTimer;

function node(tag, content, className) {
  const result = document.createElement(tag);
  if (content !== undefined && content !== null) result.textContent = String(content);
  if (className) result.className = className;
  return result;
}

function action(label, handler, className = "small secondary") {
  const result = node("button", label, className);
  result.type = "button";
  result.addEventListener("click", () => run(handler));
  return result;
}

function show(message, error = false) {
  const target = $("feedback");
  target.textContent = message;
  target.classList.toggle("error", error);
  target.style.display = "block";
  clearTimeout(feedbackTimer);
  feedbackTimer = setTimeout(() => { target.style.display = "none"; }, 6500);
}

async function run(handler) {
  try { await handler(); } catch (error) { show(error.message || "请求失败", true); }
}

async function request(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  const response = await fetch(path, { ...options, headers, cache: "no-store" });
  const text = await response.text();
  let payload;
  try { payload = text ? JSON.parse(text) : null; } catch { payload = null; }
  if (!response.ok) {
    if (response.status === 401) logout();
    throw new Error(payload?.message || `HTTP ${response.status}`);
  }
  if (payload && Object.hasOwn(payload, "code")) {
    if (payload.code !== 0) throw new Error(payload.message || "业务请求失败");
    return payload.data;
  }
  return payload;
}

function jwtRoles(token) {
  try {
    const raw = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const encoded = raw.padEnd(Math.ceil(raw.length / 4) * 4, "=");
    const claims = JSON.parse(atob(encoded));
    return Array.isArray(claims.roles) ? claims.roles : [];
  } catch { return []; }
}

function formObject(form) { return Object.fromEntries(new FormData(form).entries()); }
function jsonBody(value) { return JSON.stringify(value); }
function fill(targetId, items, render) {
  const target = $(targetId);
  target.replaceChildren();
  if (!items?.length) target.append(node("p", "暂无记录", "empty"));
  else items.forEach(item => target.append(render(item)));
}
function item(title, subtitle, status) {
  const box = node("article", null, "item");
  const head = node("div", null, "item-head");
  head.append(node("span", title), node("span", status, "badge"));
  box.append(head, node("small", subtitle));
  return box;
}
function actions(box, ...buttons) {
  const group = node("div", null, "actions");
  buttons.filter(Boolean).forEach(button => group.append(button));
  if (group.childElementCount) box.append(group);
  return box;
}
function date(value) { return value ? String(value).replace("T", " ").slice(0, 16) : "—"; }
function resourceTypeLabel(type) {
  return {
    LECTURE: "讲座",
    WORKSHOP: "培训 / 工作坊",
    ACTIVITY: "校园活动",
    STUDY_ROOM: "研讨室",
    MEETING_ROOM: "旧版固定场次资源"
  }[type] || type;
}

function logout() {
  accessToken = null;
  roles = [];
  $("identity").textContent = "尚未登录";
  $("workspace").hidden = true;
  $("auth-section").hidden = false;
  $("student-panel").hidden = true;
  $("study-room-student-panel").hidden = true;
  $("staff-panel").hidden = true;
  $("admin-panel").hidden = true;
}

async function login(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const credentials = formObject(form);
  const result = await request("/api/v1/auth/login", {
    method: "POST", body: jsonBody(credentials)
  });
  if (!result?.accessToken) throw new Error("登录响应缺少令牌");
  accessToken = result.accessToken;
  form.reset();
  roles = jwtRoles(accessToken);
  $("identity").textContent = `已登录 · ${roles.join(" / ") || "用户"}`;
  $("auth-section").hidden = true;
  $("workspace").hidden = false;
  const student = roles.includes("STUDENT");
  const staff = roles.includes("SUPPORT_STAFF") || roles.includes("ADMIN");
  $("student-panel").hidden = !student;
  $("study-room-student-panel").hidden = !student;
  $("staff-panel").hidden = !staff;
  $("admin-panel").hidden = !staff;
  $("resource-form").hidden = !staff;
  $("slot-form").hidden = !staff;
  $("role-description").textContent = student
    ? "讲座等固定场次报名、研讨室自选区间预约与个人通知"
    : "活动场次与研讨室运营、签到履约和通知";
  show("登录成功。令牌只保留在本页内存中，刷新后需重新登录。");
  if (student) await Promise.all([loadCatalog(), loadOrders(), loadNotifications(),
    loadStudyTeams(), loadStudyRooms(), loadStudyBookings()]);
  if (staff) await Promise.all([loadWork(), loadAdminResources(), loadAdminStudyWindows(),
    loadStudyWork()]);
}

async function register(event) {
  event.preventDefault();
  const form = event.currentTarget;
  await request("/api/v1/auth/register", {
    method: "POST", body: jsonBody(formObject(form))
  });
  show("学生账号已创建，请使用左侧表单登录。");
  form.reset();
}

async function loadCatalog() {
  const resources = (await request("/api/v1/resources"))
    .filter(resource => resource.resourceType !== "STUDY_ROOM");
  fill("catalog-list", resources, resource => actions(
    item(resource.resourceName, `${resource.resourceCode} · ${resourceTypeLabel(resource.resourceType)}`, resource.status),
    action("查看固定场次", () => loadSlots(resource.resourceId))
  ));
}

async function loadSlots(resourceId) {
  const slots = await request(`/api/v1/resources/${resourceId}/slots`);
  fill("slot-list", slots, slot => {
    const box = item(slot.eventTitle || `${date(slot.startsAt)} — ${date(slot.endsAt)}`,
      `${date(slot.startsAt)} — ${date(slot.endsAt)} · 场次 ${slot.slotId} · 报名名额 ${slot.capacity} · 当前余量 ${slot.availableCapacity}（以提交结果为准）`, slot.status);
    if (slot.speakerName) box.append(node("small", `主讲人：${slot.speakerName}`));
    if (slot.activityDescription) box.append(node("p", slot.activityDescription, "activity-description"));
    return actions(box, action("报名 / 候补", async () => {
      const result = await request(`/api/v1/slots/${slot.slotId}/reservations`, {
        method: "POST", headers: { "Idempotency-Key": crypto.randomUUID() }
      });
      show(`报名结果：${result.status}，订单号 ${result.reservationNo}`);
      await Promise.all([loadOrders(), loadSlots(resourceId)]);
    }));
  });
}

async function loadOrders() {
  const orders = await request("/api/v1/reservations/me");
  fill("order-list", orders, order => actions(
    item(order.reservationNo, `固定场次 ${order.slotId} · ${date(order.createdAt)}${order.waitlistPosition ? ` · 候补第 ${order.waitlistPosition} 位` : ""}`, order.status),
    ["CONFIRMED", "WAITLISTED"].includes(order.status)
      ? action("取消预约", async () => {
          if (!confirm("确认取消这笔预约？")) return;
          await request(`/api/v1/reservations/${order.reservationId}`, { method: "DELETE" });
          show("预约已取消");
          await loadOrders();
        }, "small danger") : null
  ));
}

async function loadNotifications() {
  const result = await request("/api/v1/notifications/me?page=1&size=20");
  fill("notification-list", result.items, note => actions(
    item(note.title, `${note.content} · ${date(note.createdAt)}`, note.readAt ? "已读" : "未读"),
    !note.readAt ? action("标记已读", async () => {
      await request(`/api/v1/notifications/${note.id}/read`, { method: "PATCH" });
      await loadNotifications();
    }) : null
  ));
}

async function loadWork() {
  const result = await request("/api/v1/admin/reservations?page=1&size=20");
  fill("work-list", result.items, order => {
    const box = item(order.reservationNo,
      `学生 ${order.requesterReference} · 场次 ${order.slotId} · 处理人 ${order.handledByReference || "未处理"}`, order.status);
    const ops = [];
    if (order.status === "CONFIRMED") {
      ops.push(action("签到", () => operateOrder(order.reservationId, "check-in")));
      ops.push(action("登记爽约", () => operateOrder(order.reservationId, "no-show")));
    }
    if (order.status === "CHECKED_IN") ops.push(action("完成", () => operateOrder(order.reservationId, "complete")));
    return actions(box, ...ops);
  });
}

async function operateOrder(id, operation) {
  await request(`/api/v1/admin/reservations/${id}/${operation}`, { method: "PATCH" });
  show("操作已提交，通知将异步送达学生。");
  await loadWork();
}

async function loadAdminResources() {
  const resources = await request("/api/v1/admin/resources");
  const target = $("admin-list");
  target.replaceChildren();
  if (!resources.length) { target.append(node("p", "尚无资源", "empty")); return; }
  for (const resource of resources) {
    const box = item(resource.resourceName,
      `${resource.resourceCode} · ID ${resource.resourceId} · ${resourceTypeLabel(resource.resourceType)}`, resource.status);
    const resourceActions = [];
    if (resource.status === "DRAFT") resourceActions.push(action("发布", async () => {
      await request(`/api/v1/admin/resources/${resource.resourceId}/publish`, { method: "PATCH" });
      await loadAdminResources();
      if (resource.resourceType === "STUDY_ROOM") await loadAdminStudyWindows();
    }));
    if (resource.status !== "CLOSED" && roles.includes("ADMIN")) resourceActions.push(action("关闭资源", async () => {
      if (!confirm("只有没有开放时段的资源才能关闭。继续？")) return;
      await request(`/api/v1/admin/resources/${resource.resourceId}/close`, { method: "PATCH" });
      await loadAdminResources();
    }, "small danger"));
    actions(box, ...resourceActions);
    const slots = await request(`/api/v1/admin/resources/${resource.resourceId}/slots`);
    for (const slot of slots) {
      const line = item(`${slot.eventTitle || `固定场次 ${slot.slotId}`}: ${date(slot.startsAt)} — ${date(slot.endsAt)}`,
        `报名名额 ${slot.capacity} · 当前余量 ${slot.availableCapacity}`, slot.status);
      if (slot.speakerName) line.append(node("small", `主讲人：${slot.speakerName}`));
      if (slot.activityDescription) line.append(node("p", slot.activityDescription, "activity-description"));
      if (slot.status === "OPEN" && roles.includes("ADMIN")) actions(line, action("维护取消", async () => {
        const reason = prompt("请输入取消原因（最多 300 字；将记录操作人和影响数量）");
        if (reason === null) return;
        const result = await request(`/api/v1/admin/slots/${slot.slotId}/cancel`, {
          method: "PATCH", body: jsonBody({ reason })
        });
        show(`已取消：确认订单 ${result.confirmedCount}，候补 ${result.waitlistedCount}`);
        await loadAdminResources();
      }, "small danger"));
      if (slot.status === "CANCELLED") actions(line, action("查看取消记录", async () => {
        const result = await request(`/api/v1/admin/slots/${slot.slotId}/cancellation`);
        show(`操作人 ${result.operatorReference}；原因：${result.reason}；确认 ${result.confirmedCount}；候补 ${result.waitlistedCount}`);
      }));
      box.append(line);
    }
    target.append(box);
  }
}

async function createResource(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formObject(form);
  data.noShowGraceMinutes = Number(data.noShowGraceMinutes);
  await request("/api/v1/admin/resources", { method: "POST", body: jsonBody(data) });
  show(data.resourceType === "STUDY_ROOM"
    ? "研讨室草稿已创建；发布后请开放使用窗口。"
    : "活动资源草稿已创建；发布后请开放固定场次。");
  form.reset();
  await loadAdminResources();
}

async function createSlot(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formObject(form);
  // Snowflake IDs exceed JavaScript's safe integer range; send the exact string.
  data.capacity = Number(data.capacity);
  await request("/api/v1/admin/slots", { method: "POST", body: jsonBody(data) });
  show("新的固定场次已开放。");
  form.reset();
  await loadAdminResources();
}

$("login-form").addEventListener("submit", e => run(() => login(e)));
$("register-form").addEventListener("submit", e => run(() => register(e)));
$("resource-form").addEventListener("submit", e => run(() => createResource(e)));
$("slot-form").addEventListener("submit", e => run(() => createSlot(e)));
$("logout-button").addEventListener("click", logout);
$("reload-catalog").addEventListener("click", () => run(loadCatalog));
$("reload-orders").addEventListener("click", () => run(loadOrders));
$("reload-notifications").addEventListener("click", () => run(loadNotifications));
$("reload-work").addEventListener("click", () => run(loadWork));
$("reload-admin").addEventListener("click", () => run(loadAdminResources));

function currentClaims() {
  try {
    const raw = accessToken.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    return JSON.parse(atob(raw.padEnd(Math.ceil(raw.length / 4) * 4, "=")));
  } catch { return {}; }
}

async function loadStudyTeams() {
  const teams = await request("/api/v1/study-teams/mine");
  const claims = currentClaims();
  fill("study-team-list", teams, team => {
    const members = team.members.map(m => `${m.studentNumber}: ${m.status}`).join(" · ");
    const box = item(`团队 ${team.teamId}`, members, team.status);
    const mine = team.members.find(m => m.studentNumber === claims.studentNo);
    const leader = team.leaderStudentNumber === claims.studentNo && mine?.status === "ACCEPTED";
    const ops = [];
    if (mine?.status === "INVITED" && team.status === "FORMING") {
      ops.push(action("确认加入", async () => {
        await request(`/api/v1/study-teams/${team.teamId}/accept`, { method: "POST" });
        await loadStudyTeams();
      }));
      ops.push(action("拒绝邀请", async () => {
        await request(`/api/v1/study-teams/${team.teamId}/decline`, { method: "POST" });
        await loadStudyTeams();
      }, "small danger"));
    }
    if (leader && team.status === "FORMING") {
      ops.push(action("用于预约", () => {
        $("study-booking-form").elements.teamId.value = team.teamId;
        show(`已选择团队 ${team.teamId}`);
      }));
      ops.push(action("追加邀请学号", async () => {
        const studentNumber = prompt("请输入被邀请学生的学号");
        if (!studentNumber) return;
        await request(`/api/v1/study-teams/${team.teamId}/invitations`, {
          method: "POST", body: jsonBody({ studentNumber: studentNumber.trim() })
        });
        await loadStudyTeams();
      }));
      ops.push(action("关闭团队", async () => {
        if (!confirm("关闭后不可继续预约，确认关闭？")) return;
        await request(`/api/v1/study-teams/${team.teamId}/close`, { method: "POST" });
        await loadStudyTeams();
      }, "small danger"));
    }
    if (!leader && mine?.status === "ACCEPTED" && team.status === "FORMING") {
      ops.push(action("退出团队", async () => {
        await request(`/api/v1/study-teams/${team.teamId}/members/me`, { method: "DELETE" });
        await loadStudyTeams();
      }, "small danger"));
    }
    return actions(box, ...ops);
  });
}

async function createStudyTeam(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const values = formObject(form);
  const invitedStudentNumbers = values.invitedStudentNumbers.split(",")
    .map(value => value.trim()).filter(Boolean);
  const result = await request("/api/v1/study-teams", {
    method: "POST", body: jsonBody({ invitedStudentNumbers })
  });
  $("created-study-invite-code").textContent = result.inviteCode || "—";
  form.reset();
  show(`团队 ${result.teamId} 已创建；邀请码请现在复制保存`);
  await loadStudyTeams();
}

async function joinStudyTeam(event) {
  event.preventDefault();
  const form = event.currentTarget;
  await request("/api/v1/study-teams/join", {
    method: "POST", body: jsonBody(formObject(form))
  });
  form.reset();
  show("已用当前登录身份确认加入团队");
  await loadStudyTeams();
}

async function loadStudyRooms() {
  const resources = (await request("/api/v1/resources"))
    .filter(resource => resource.resourceType === "STUDY_ROOM");
  fill("study-room-list", resources, room => actions(
    item(room.resourceName,
      `${room.resourceCode} · ${room.minParticipants}–${room.maxParticipants} 人 · ID ${room.resourceId}`,
      room.status),
    action("选择 / 查看窗口", async () => {
      $("study-booking-form").elements.resourceId.value = room.resourceId;
      const windows = await request(`/api/v1/study-rooms/${room.resourceId}/windows`);
      const days = [...new Set(windows.map(window => window.startsAt.slice(0, 10)))];
      const availability = await Promise.all(days.map(day => request(
        `/api/v1/study-rooms/${room.resourceId}/availability?date=${day}`)));
      const occupied = availability.flatMap(day => day.occupiedIntervals);
      fill("study-occupied-list", occupied, interval => item(
        "已被占用", `${date(interval.startsAt)} - ${date(interval.endsAt)}`, "不可预约"));
      fill("study-window-list", windows, window => item(
        `${date(window.startsAt)} – ${date(window.endsAt)}`,
        `该窗口内可自选 60–240 分钟；提交时再次校验`, window.status));
    })
  ));
}

async function createStudyBooking(event) {
  event.preventDefault();
  const data = formObject(event.currentTarget);
  const result = await request("/api/v1/study-rooms/bookings", {
    method: "POST", headers: { "Idempotency-Key": crypto.randomUUID() },
    body: jsonBody(data)
  });
  show(`研讨室预约 ${result.bookingNo}: ${result.status}`);
  await Promise.all([loadStudyBookings(), loadStudyTeams(), loadNotifications()]);
}

async function loadStudyBookings() {
  const bookings = await request("/api/v1/study-rooms/bookings/me");
  fill("study-booking-list", bookings, booking => actions(
    item(booking.bookingNo, `${date(booking.startsAt)} – ${date(booking.endsAt)} · `
      + `研讨室 ${booking.resourceId} · ${booking.studentNumbers.join("、")}`
      + ` · 已到场 ${booking.checkedInCount ?? 0}/${booking.requiredAttendanceCount ?? 1} 人`,
      booking.status),
    ["CONFIRMED", "WAITLISTED"].includes(booking.status)
      && currentClaims().studentNo === booking.leaderStudentNumber
      ? action("取消预约", async () => {
          if (!confirm("确认取消该团队预约？候补可能立即递补。")) return;
          await request(`/api/v1/study-rooms/bookings/${booking.id}`, { method: "DELETE" });
          await Promise.all([loadStudyBookings(), loadStudyTeams()]);
        }, "small danger") : null
  ));
}

async function openStudyWindow(event) {
  event.preventDefault();
  const form = event.currentTarget;
  await request("/api/v1/admin/study-rooms/windows", {
    method: "POST", body: jsonBody(formObject(form))
  });
  show("研讨室开放窗口已保存");
  await loadAdminStudyWindows();
}

async function updateStudyOccupancy(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formObject(form);
  await request(`/api/v1/admin/study-rooms/${data.resourceId}/occupancy`, {
    method: "PATCH", body: jsonBody({
      minParticipants: Number(data.minParticipants), maxParticipants: Number(data.maxParticipants)
    })
  });
  show("研讨室人数要求已保存");
  await loadAdminResources();
}

async function loadAdminStudyWindows() {
  const resources = (await request("/api/v1/admin/resources"))
    .filter(resource => resource.resourceType === "STUDY_ROOM" && resource.status === "PUBLISHED");
  const target = $("admin-study-window-list");
  target.replaceChildren();
  for (const room of resources) {
    const box = item(room.resourceName,
      `${room.minParticipants}–${room.maxParticipants} 人 · ID ${room.resourceId}`, room.status);
    actions(box, action("填入研讨室 ID", () => {
      $("study-window-form").elements.resourceId.value = room.resourceId;
      $("study-occupancy-form").elements.resourceId.value = room.resourceId;
    }));
    const windows = await request(`/api/v1/study-rooms/${room.resourceId}/windows`);
    for (const window of windows) {
      const line = item(`${date(window.startsAt)} – ${date(window.endsAt)}`,
        `窗口 ${window.id}`, window.status);
      actions(line, action("缩短窗口", async () => {
        const startsAt = prompt("新的开始时间（只能比原开始晚）", window.startsAt.slice(0, 16));
        if (startsAt === null) return;
        const endsAt = prompt("新的结束时间（只能比原结束早）", window.endsAt.slice(0, 16));
        if (endsAt === null) return;
        const reason = prompt("对受影响预约的维护取消原因");
        if (!reason?.trim()) return;
        await request(`/api/v1/admin/study-rooms/windows/${window.id}`, {
          method: "PATCH", body: jsonBody({ startsAt, endsAt, reason: reason.trim() })
        });
        await loadAdminStudyWindows();
        show("窗口已缩短，仅受影响的预约被维护取消");
      }));
      actions(line, action("维护关闭", async () => {
        const reason = prompt("维护取消原因（将通知受影响团队）");
        if (!reason?.trim()) return;
        await request(`/api/v1/admin/study-rooms/windows/${window.id}/maintenance-close?reason=${encodeURIComponent(reason.trim())}`,
          { method: "POST" });
        await loadAdminStudyWindows();
        show("窗口已关闭，受影响预约已进入通知 Outbox");
      }, "small danger"));
      box.append(line);
    }
    target.append(box);
  }
}

async function loadStudyWork() {
  const bookings = await request("/api/v1/admin/study-rooms/bookings");
  fill("study-work-list", bookings, booking => {
    const box = item(booking.bookingNo,
      `${date(booking.startsAt)} – ${date(booking.endsAt)} · 房间 ${booking.resourceId}`
      + ` · 成员 ${booking.studentNumbers.join("、")}`,
      booking.status);
    box.append(node("p", `已到场 ${booking.checkedInCount ?? 0}/${booking.requiredAttendanceCount ?? 1} 人`
      + ` · ${booking.checkedInStudentNumbers?.join("、") || "暂无"}`));
    const ops = [];
    if (["CONFIRMED", "CHECKED_IN"].includes(booking.status)) {
      ops.push(action("核验组员签到", async () => {
        const studentNumber = prompt("请输入现场核验的已预约组员学号");
        if (!studentNumber?.trim()) return;
        await request(`/api/v1/admin/study-rooms/bookings/${booking.id}/check-in`, {
          method: "PATCH", body: jsonBody({ studentNumber: studentNumber.trim() })
        });
        await loadStudyWork();
      }));
    }
    if (booking.status === "CONFIRMED") {
      ops.push(action("登记爽约", async () => {
        if (!confirm("请先现场核实。超过该研讨室的宽限时间后才能登记爽约。")) return;
        await request(`/api/v1/admin/study-rooms/bookings/${booking.id}/no-show`, { method: "PATCH" });
        await loadStudyWork();
      }, "small danger"));
    }
    if (booking.status === "CHECKED_IN") {
      ops.push(action("完成使用", async () => {
        await request(`/api/v1/admin/study-rooms/bookings/${booking.id}/complete`, { method: "PATCH" });
        await loadStudyWork();
      }));
    }
    return actions(box, ...ops);
  });
}

$("study-team-create-form").addEventListener("submit", e => run(() => createStudyTeam(e)));
$("study-team-join-form").addEventListener("submit", e => run(() => joinStudyTeam(e)));
$("study-booking-form").addEventListener("submit", e => run(() => createStudyBooking(e)));
$("study-window-form").addEventListener("submit", e => run(() => openStudyWindow(e)));
$("study-occupancy-form").addEventListener("submit", e => run(() => updateStudyOccupancy(e)));
$("reload-teams").addEventListener("click", () => run(loadStudyTeams));
$("reload-study-rooms").addEventListener("click", () => run(loadStudyRooms));
$("reload-study-bookings").addEventListener("click", () => run(loadStudyBookings));
$("reload-study-work").addEventListener("click", () => run(loadStudyWork));
