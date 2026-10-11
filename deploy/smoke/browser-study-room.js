/* Real Chrome page: student forms -> Gateway -> four services -> result card. */
const gateway = process.env.CAMPUS_RESERVE_E2E_GATEWAY;
const studentName = process.env.CAMPUS_RESERVE_E2E_STUDENT_NAME;
const studentPassword = process.env.CAMPUS_RESERVE_E2E_STUDENT_PASSWORD;
const adminName = process.env.CAMPUS_RESERVE_E2E_ADMIN_NAME;
const adminPassword = process.env.CAMPUS_RESERVE_E2E_ADMIN_PASSWORD;
const roomId = process.env.CAMPUS_RESERVE_E2E_RESOURCE_ID;
const startsAt = process.env.CAMPUS_RESERVE_E2E_STARTS_AT;
const endsAt = process.env.CAMPUS_RESERVE_E2E_ENDS_AT;
const reason = process.env.CAMPUS_RESERVE_E2E_REASON;
const port = Number(process.env.CAMPUS_RESERVE_E2E_CDP_PORT || 19223);

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let target;
for (let attempt = 0; attempt < 40; attempt++) {
  try {
    const response = await fetch(`http://127.0.0.1:${port}/json/list`);
    target = (await response.json()).find(item => item.type === "page");
    if (target) break;
  } catch { /* Chrome is starting. */ }
  await sleep(300);
}
if (!target) throw new Error("Chrome CDP unavailable");
const socket = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.addEventListener("open", resolve, { once: true });
  socket.addEventListener("error", reject, { once: true });
});
let sequence = 0;
const pending = new Map();
function command(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
socket.addEventListener("message", event => {
  const message = JSON.parse(event.data);
  if (message.method === "Page.javascriptDialogOpening") {
    void command("Page.handleJavaScriptDialog", { accept: true, promptText: reason });
  }
  if (!message.id) return;
  const waiter = pending.get(message.id);
  if (!waiter) return;
  pending.delete(message.id);
  if (message.error) waiter.reject(new Error(message.error.message));
  else waiter.resolve(message.result);
});
async function evaluate(expression) {
  const response = await command("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
  if (response.exceptionDetails) throw new Error(response.exceptionDetails.text || "Browser evaluation failed");
  return response.result.value;
}
async function until(expression, label, timeout = 16000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await evaluate(expression)) return;
    await sleep(250);
  }
  const feedback = await evaluate("document.querySelector('#feedback')?.textContent || ''");
  throw new Error(`${label} timed out; feedback=${feedback}`);
}
async function login(username, password) {
  await evaluate(`(() => {
    const form = document.querySelector('#login-form');
    form.elements.username.value = ${JSON.stringify(username)};
    form.elements.password.value = ${JSON.stringify(password)};
    form.requestSubmit();
  })()`);
  await until("document.querySelector('#workspace')?.hidden === false", "login");
}

try {
  await command("Page.enable");
  await command("Runtime.enable");
  await command("Page.navigate", { url: gateway });
  await until("document.readyState === 'complete' && typeof loadStudyTeams === 'function'", "page");
  await login(studentName, studentPassword);
  await evaluate("document.querySelector('#study-team-create-form').requestSubmit()");
  await until("document.querySelector('#study-team-list')?.textContent.includes('FORMING')", "team creation");
  const teams = await evaluate("request('/api/v1/study-teams/mine')");
  const team = teams.find(item => item.status === "FORMING");
  if (!team) throw new Error("Team missing after browser creation");
  await until(`Array.from(document.querySelectorAll('#study-room-list article'))
    .some(item => item.textContent.includes(${JSON.stringify(roomId)}))`, "study room card");
  await evaluate(`(() => {
    const form = document.querySelector('#study-booking-form');
    form.elements.resourceId.value = ${JSON.stringify(roomId)};
    form.elements.teamId.value = ${JSON.stringify(String(team.teamId))};
    form.elements.startsAt.value = ${JSON.stringify(startsAt)};
    form.elements.endsAt.value = ${JSON.stringify(endsAt)};
    form.requestSubmit();
  })()`);
  await until("document.querySelector('#study-booking-list')?.textContent.includes('CONFIRMED')", "booking");
  const bookings = await evaluate("request('/api/v1/study-rooms/bookings/me')");
  const booking = bookings.find(item => String(item.resourceId) === roomId);
  if (!booking || booking.status !== "CONFIRMED") throw new Error("Confirmed booking not visible in browser");
  await evaluate(`Array.from(document.querySelectorAll('#study-room-list article'))
    .find(item => item.textContent.includes(${JSON.stringify(roomId)}))
    .querySelector('button').click()`);
  await until("document.querySelector('#study-occupied-list')?.textContent.includes('不可预约')",
    "privacy-safe occupied interval view");
  const availability = await evaluate(`request('/api/v1/study-rooms/${roomId}/availability?date=${startsAt.slice(0, 10)}')`);
  if (availability.occupiedIntervals.length !== 1
      || Object.keys(availability.occupiedIntervals[0]).some(key => /student|user|team|leader/i.test(key))) {
    throw new Error("Availability must show one occupied interval without student identities");
  }

  await evaluate("document.querySelector('#logout-button').click()");
  await login(adminName, adminPassword);
  await until(`Array.from(document.querySelectorAll('#admin-study-window-list article'))
    .some(item => item.textContent.includes(${JSON.stringify(roomId)})
      && Array.from(item.querySelectorAll('button')).some(button => button.textContent.includes('维护关闭')))`,
    "admin maintenance action");
  await evaluate(`Array.from(document.querySelectorAll('#admin-study-window-list article'))
    .filter(item => item.textContent.includes(${JSON.stringify(roomId)}))
    .flatMap(item => Array.from(item.querySelectorAll('button')))
    .find(button => button.textContent.includes('维护关闭')).click()`);
  await until(`!Array.from(document.querySelectorAll('#admin-study-window-list article'))
    .some(item => item.textContent.includes(${JSON.stringify(roomId)})
      && Array.from(item.querySelectorAll('button')).some(button => button.textContent.includes('维护关闭')))`,
    "maintenance closure", 20000);

  await evaluate("document.querySelector('#logout-button').click()");
  await login(studentName, studentPassword);
  await until("document.querySelector('#study-booking-list')?.textContent.includes('CANCELLED_BY_ADMIN')",
    "student cancelled booking");
  const notificationTitle = "Study room booking cancelled for maintenance";
  for (let attempt = 0; attempt < 35; attempt++) {
    if (await evaluate(`document.querySelector('#notification-list')?.textContent.includes(
      ${JSON.stringify(notificationTitle)})`)) break;
    await evaluate("document.querySelector('#reload-notifications').click()");
    await sleep(450);
  }
  if (!(await evaluate(`document.querySelector('#notification-list')?.textContent.includes(
      ${JSON.stringify(notificationTitle)})`))) throw new Error("Student notification not visible");
  process.stdout.write(JSON.stringify({ bookingId: booking.id }) + "\n");
} finally {
  try { await command("Browser.close"); } catch { /* Already closed. */ }
  socket.close();
}
