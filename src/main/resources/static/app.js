const state = {
  authenticated: false,
  name: "",
  csrfToken: "",
  vocabulary: [],
  vocabularyBooks: [],
  communityBooks: [],
  questions: [],
  category: "all",
  authMode: "login",
  vocabTab: "list",
  cardIndex: 0,
  quizIndex: 0,
  quizOptions: [],
  quizCorrectCount: 0,
  quizAnswered: false,
  quizCompleted: false,
  deckFilter: "",
  pendingView: "",
  pendingCategory: "",
  toastTimer: null,
  dictionarySelectionTimer: null,
  dictionarySelectionHideTimer: null,
  dictionarySelectionRequest: 0,
  dictionarySelectionWord: "",
  dictionarySelectionCache: new Map()
};

const $ = (selector) => document.querySelector(selector);
const escapeHtml = (value = "") => String(value).replace(/[&<>"']/g, (char) => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
})[char]);
const shuffled = (items) => {
  const result = [...items];
  for (let index = result.length - 1; index > 0; index--) {
    const swapIndex = Math.floor(Math.random() * (index + 1));
    [result[index], result[swapIndex]] = [result[swapIndex], result[index]];
  }
  return result;
};

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (state.csrfToken && options.method && !["GET", "HEAD"].includes(options.method.toUpperCase())) {
    headers.set("X-CSRF-TOKEN", state.csrfToken);
  }
  if (options.body && !(options.body instanceof FormData)) headers.set("Content-Type", "application/json");
  let response;
  try {
    response = await fetch(path, { ...options, headers, credentials: "same-origin" });
  } catch (error) {
    if (error instanceof TypeError) {
      throw new Error("Không kết nối được máy chủ. Hãy kiểm tra ứng dụng Spring Boot đang chạy; xem log trong cửa sổ Run/Terminal của backend.");
    }
    throw error;
  }
  const payload = response.status === 204 ? null : await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401) showAuth();
    throw new Error(payload?.error || "Có lỗi xảy ra. Vui lòng thử lại.");
  }
  return payload;
}

function setQuestionCategory(category) {
  state.category = category;
  const chips = $("#question-categories").querySelectorAll(".filter-chip");
  chips.forEach((chip) => chip.classList.toggle("active", chip.dataset.category === category));
  renderQuestions();
}

function toast(message, type = "info", duration = 3200) {
  const element = $("#toast");
  element.textContent = message;
  element.classList.remove("error", "success");
  if (type !== "info") element.classList.add(type);
  element.classList.add("show");
  clearTimeout(state.toastTimer);
  if (duration > 0) state.toastTimer = setTimeout(() => element.classList.remove("show"), duration);
}

function showApplication() {
  $("#landing-page").hidden = true;
  $(".app-shell").hidden = false;
}

function setAuthenticated(session) {
  state.authenticated = Boolean(session.authenticated);
  state.name = session.name || "";
  state.csrfToken = session.csrfToken || "";
  const label = state.authenticated ? state.name : "Khách";
  $("#profile-name").textContent = label;
  $("#profile-subtitle").textContent = state.authenticated ? "Không gian học của bạn" : "Đăng nhập để đồng bộ";
  $("#avatar").textContent = state.authenticated ? label.trim().charAt(0).toUpperCase() : "K";
  $("#welcome-name").textContent = state.authenticated ? label : "người học";
  $("#auth-button").textContent = state.authenticated ? "Đăng xuất" : "Đăng nhập";
  if (state.authenticated) {
    showApplication();
    loadDashboard();
  }
  else {
    $("#landing-page").hidden = false;
    $(".app-shell").hidden = true;
    state.pendingView = "";
    state.pendingCategory = "";
    document.querySelectorAll(".view").forEach((section) => section.classList.toggle("active", section.id === "view-home"));
    document.querySelectorAll(".nav-link").forEach((link) => link.classList.toggle("active", link.dataset.view === "home"));
    $("#breadcrumb-current").textContent = "Tổng quan";
    state.vocabulary = [];
    renderVocabulary();
  }
}

async function init() {
  $("#today-label").textContent = new Intl.DateTimeFormat("vi-VN", { weekday: "long", day: "numeric", month: "long" }).format(new Date());
  initScrollReveal();
  $("#profile-button").addEventListener("click", () => state.authenticated ? logout() : showAuth());
  try {
    const session = await api("/api/session");
    setAuthenticated(session);
    await loadQuestions();
  } catch (error) {
    toast(error.message);
  }
  bindEvents();
}

function initScrollReveal() {
  const targets = document.querySelectorAll("[data-scroll-reveal]");
  if (!("IntersectionObserver" in window) || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    targets.forEach((element) => element.classList.add("is-visible"));
    return;
  }
  const observer = new IntersectionObserver((entries, currentObserver) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      entry.target.classList.add("is-visible");
      currentObserver.unobserve(entry.target);
    });
  }, { threshold: 0.14, rootMargin: "0px 0px -35px 0px" });
  targets.forEach((element) => observer.observe(element));
}

function bindEvents() {
  document.addEventListener("pointerup", (event) => {
    if (event.target.closest("#dictionary-selection-toast")) return;
    clearTimeout(state.dictionarySelectionTimer);
    state.dictionarySelectionTimer = setTimeout(lookupSelectedJapaneseWord, 80);
  });
  document.addEventListener("keyup", (event) => {
    if (event.target.closest("#dictionary-selection-toast")) return;
    clearTimeout(state.dictionarySelectionTimer);
    state.dictionarySelectionTimer = setTimeout(lookupSelectedJapaneseWord, 80);
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") hideDictionarySelectionToast();
  });
  $("#dictionary-selection-toast").addEventListener("click", (event) => {
    if (event.target.closest("[data-dictionary-toast-close]")) hideDictionarySelectionToast();
  });
  document.querySelectorAll("[data-landing-auth]").forEach((button) => button.addEventListener("click", () => {
    state.pendingView = "";
    state.pendingCategory = "";
    setAuthMode(button.dataset.landingAuth);
    showAuth();
  }));
  const interviewNav = $(".interview-nav-item");
  const interviewNavToggle = $(".interview-nav-toggle");
  interviewNavToggle.addEventListener("click", () => {
    const expanded = interviewNavToggle.getAttribute("aria-expanded") === "true";
    interviewNavToggle.setAttribute("aria-expanded", String(!expanded));
    interviewNav.classList.toggle("open", !expanded);
  });
  $("#interview-nav-menu").addEventListener("click", (event) => {
    const option = event.target.closest("[data-interview-category]");
    if (!option) return;
    const category = option.dataset.interviewCategory;
    interviewNav.classList.remove("open");
    interviewNavToggle.setAttribute("aria-expanded", "false");
    state.pendingView = "interview";
    state.pendingCategory = category;
    if (!state.authenticated) {
      setAuthMode("login");
      showAuth();
      return;
    }
    state.pendingView = "";
    state.pendingCategory = "";
    showApplication();
    navigate("interview");
    setQuestionCategory(category);
  });
  document.addEventListener("click", (event) => {
    if (interviewNav.contains(event.target)) return;
    interviewNav.classList.remove("open");
    interviewNavToggle.setAttribute("aria-expanded", "false");
  });
  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    interviewNav.classList.remove("open");
    interviewNavToggle.setAttribute("aria-expanded", "false");
  });
  document.querySelectorAll(".landing-nav a, .landing-footer a, .landing-hero-actions a").forEach((link) => link.addEventListener("click", (event) => {
    const target = document.querySelector(link.getAttribute("href"));
    if (target) {
      event.preventDefault();
      const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      target.scrollIntoView({ behavior: reducedMotion ? "auto" : "smooth" });
    }
  }));
  document.querySelectorAll(".nav-link").forEach((button) => button.addEventListener("click", () => navigate(button.dataset.view)));
  document.querySelectorAll("[data-go]").forEach((button) => button.addEventListener("click", () => navigate(button.dataset.go)));
  document.querySelectorAll("[data-action='add']").forEach((button) => button.addEventListener("click", () => $("#vocabulary-form [name='japanese']").focus()));
  $("#add-word-button").addEventListener("click", () => $("#vocabulary-form [name='japanese']").focus());
  $("#vocabulary-form").addEventListener("submit", saveVocabulary);
  $("#book-create-form").addEventListener("submit", createVocabularyBook);
  $("#my-books-list").addEventListener("click", updateBookVisibility);
  $("#community-search-form").addEventListener("submit", (event) => {
    event.preventDefault();
    loadCommunityBooks($("#community-search").value);
  });
  $("#community-book-grid").addEventListener("click", (event) => {
    const button = event.target.closest("[data-community-book]");
    if (button) openCommunityBook(button.dataset.communityBook);
  });
  $("#community-book-content").addEventListener("click", saveCommunityBook);
  $("#community-back").addEventListener("click", () => {
    $("#community-book-detail").hidden = true;
    $("#community-library").hidden = false;
  });
  $("#file-input").addEventListener("change", importVocabulary);
  $("#deck-filter").addEventListener("change", (event) => {
    state.deckFilter = event.target.value;
    state.cardIndex = 0;
    resetQuiz();
    renderVocabulary();
  });
  $("#auth-button").addEventListener("click", () => state.authenticated ? logout() : showAuth());
  $("#modal-close").addEventListener("click", hideAuth);
  $("#auth-modal").addEventListener("click", (event) => { if (event.target === $("#auth-modal")) hideAuth(); });
  document.querySelectorAll("[data-auth-mode]").forEach((button) => button.addEventListener("click", () => setAuthMode(button.dataset.authMode)));
  $("#auth-form").addEventListener("submit", submitAuth);
  $("#dictionary-form").addEventListener("submit", lookupDictionary);
  document.querySelectorAll("[data-vocab-back]").forEach((button) => button.addEventListener("click", () => setVocabTab("list")));
  document.querySelectorAll("[data-vocab-tab]").forEach((button) => button.addEventListener("click", () => setVocabTab(button.dataset.vocabTab)));
  $("#flashcard").addEventListener("click", () => {
    const flashcard = $("#flashcard");
    flashcard.classList.toggle("flipped");
    flashcard.querySelector(".card-hint").textContent = flashcard.classList.contains("flipped") ? "NHẤN ĐỂ XEM TỪ" : "NHẤN ĐỂ XEM ĐÁP ÁN";
  });
  $("#card-prev").addEventListener("click", () => moveCard(-1));
  $("#card-next").addEventListener("click", () => moveCard(1));
  $("#quiz-next").addEventListener("click", advanceQuiz);
  $("#quiz-options").addEventListener("click", answerQuiz);
  $("#vocab-list").addEventListener("click", (event) => {
    const button = event.target.closest("[data-analyze-word]");
    if (button) analyzeVocabularyEntry(button);
  });
  $("#question-categories").addEventListener("click", (event) => {
    const button = event.target.closest("[data-category]");
    if (!button) return;
    setQuestionCategory(button.dataset.category);
  });
  $("#question-grid").addEventListener("click", (event) => {
    const button = event.target.closest("[data-question]");
    if (!button) return;
    const card = button.closest(".question-card");
    const answer = card.querySelector(".answer-hint");
    answer.hidden = !answer.hidden;
    button.textContent = answer.hidden ? "Xem gợi ý trả lời →" : "Ẩn gợi ý ↑";
  });
  $("#question-grid").addEventListener("submit", generateInterviewAnswer);
}

function navigate(view) {
  if (!state.authenticated) {
    state.pendingView = view;
    setAuthMode("login");
    showAuth();
    return;
  }
  const titles = {
    home: "Tổng quan",
    vocabulary: "Từ vựng",
    community: "Cộng đồng sổ từ",
    interview: "Luyện phỏng vấn",
    conversation: "Hội thoại thực tế",
    keigo: "Kính ngữ Keigo"
  };
  if (!titles[view]) view = "home";
  document.querySelectorAll(".view").forEach((section) => section.classList.toggle("active", section.id === `view-${view}`));
  document.querySelectorAll(".nav-link").forEach((link) => link.classList.toggle("active", link.dataset.view === view));
  $("#breadcrumb-current").textContent = titles[view];
  if (view === "vocabulary") loadVocabulary();
  if (view === "community") loadCommunityBooks();
}

function showAuth() {
  $("#auth-modal").hidden = false;
  $("#auth-form [name='email']").focus();
}

function hideAuth() {
  $("#auth-modal").hidden = true;
  if (!state.authenticated) {
    $("#landing-page").hidden = false;
    $(".app-shell").hidden = true;
  }
}

function setAuthMode(mode) {
  state.authMode = mode;
  document.querySelectorAll("[data-auth-mode]").forEach((button) => button.classList.toggle("active", button.dataset.authMode === mode));
  const registering = mode === "register";
  $("#auth-title").textContent = registering ? "Bắt đầu hành trình" : "Chào mừng trở lại";
  $(".auth-description").textContent = registering ? "Tạo tài khoản để lưu từ vựng và tiến độ học tập của bạn." : "Đăng nhập để tiếp tục hành trình học tiếng Nhật.";
  $("#name-field").hidden = !registering;
  $("#auth-form [name='name']").required = registering;
  $("#auth-form [name='password']").autocomplete = registering ? "new-password" : "current-password";
  $("#auth-submit").innerHTML = registering ? "Tạo tài khoản <span>→</span>" : "Đăng nhập <span>→</span>";
}

async function submitAuth(event) {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  const body = Object.fromEntries(form.entries());
  const path = state.authMode === "register" ? "/api/auth/register" : "/api/auth/login";
  try {
    const session = await api(path, { method: "POST", body: JSON.stringify(body) });
    setAuthenticated(session);
    hideAuth();
    if (state.pendingView) {
      const destination = state.pendingView;
      const category = state.pendingCategory;
      state.pendingView = "";
      state.pendingCategory = "";
      navigate(destination);
      if (destination === "interview" && category) setQuestionCategory(category);
    }
    toast(state.authMode === "register" ? "Tài khoản đã sẵn sàng. よろしくお願いします！" : "Đăng nhập thành công.");
  } catch (error) {
    toast(error.message);
  }
}

async function logout() {
  if (!state.authenticated) return showAuth();
  try {
    await api("/api/auth/logout", { method: "POST" });
    navigate("home");
    setAuthenticated({ authenticated: false });
    const session = await api("/api/session");
    state.csrfToken = session.csrfToken;
    toast("Bạn đã đăng xuất.");
  } catch (error) {
    toast(error.message);
  }
}

async function loadDashboard() {
  try {
    const dashboard = await api("/api/dashboard");
    $("#vocabulary-count").textContent = dashboard.vocabularyCount;
    $("#nav-count").textContent = dashboard.vocabularyCount;
    $("#question-count").textContent = dashboard.questions;
    $("#interview-count").textContent = dashboard.questions;
    await loadVocabulary();
  } catch (error) {
    toast(error.message);
  }
}

async function loadVocabulary() {
  if (!state.authenticated) return;
  try {
    state.vocabulary = await api("/api/vocabulary");
    state.vocabularyBooks = await api("/api/vocabulary/books");
    renderVocabulary();
    renderVocabularyBooks();
  } catch (error) {
    toast(error.message);
  }
}

function renderVocabulary() {
  const entries = state.vocabulary;
  const deckNames = [...new Set([
    ...state.vocabularyBooks.map((book) => book.name),
    ...entries.map((entry) => entry.setName || "Từ vựng của tôi")
  ])];
  renderBookSelectors();
  if (!deckNames.includes(state.deckFilter)) state.deckFilter = "";
  $("#deck-filter").innerHTML = `<option value="">Tất cả bộ từ</option>${deckNames.map((name) => `<option value="${escapeHtml(name)}">${escapeHtml(name)}</option>`).join("")}`;
  $("#deck-filter").value = state.deckFilter;
  const visibleEntries = activeVocabulary();
  $("#vocabulary-count").textContent = entries.length;
  $("#nav-count").textContent = entries.length;
  $("#list-count").textContent = visibleEntries.length;
  $("#vocab-empty").hidden = visibleEntries.length > 0;
  $("#vocab-empty").querySelector("h3").textContent = state.deckFilter
    ? `Sổ “${state.deckFilter}” chưa có từ`
    : "Sổ từ của bạn đang chờ";
  $("#vocab-empty").querySelector("p").textContent = state.deckFilter
    ? "Thêm từ hoặc nhập file vào sổ này để bắt đầu ôn tập."
    : "Thêm từ đầu tiên hoặc nhập file để bắt đầu ôn tập.";
  $("#vocab-list").innerHTML = visibleEntries.map((entry) => `
    <article class="vocab-item">
      <span class="word-kanji">${escapeHtml(entry.japanese)}</span>
      <div class="word-copy"><strong>${escapeHtml(entry.japanese)}</strong><span class="word-reading">${escapeHtml(entry.reading || "")}</span>
        <div class="word-set">${escapeHtml(entry.setName || "Từ vựng của tôi")}</div>
        <div class="word-meaning">${escapeHtml(entry.vietnamese || "Chưa thêm nghĩa")}</div>
        ${entry.example ? `<div class="word-example">${escapeHtml(entry.example)}</div>` : ""}
        <div class="word-ai-result" data-ai-result hidden></div>
      </div>
      <div class="word-actions"><button type="button" class="word-ai-button" title="Phân tích từ này bằng AI" data-analyze-word="${entry.id}">✦ AI</button><button type="button" title="Tra từ" data-lookup="${escapeHtml(entry.japanese)}">⌕</button><button type="button" class="delete-word" title="Xóa từ" data-delete="${entry.id}">×</button></div>
    </article>`).join("");
  $("#vocab-list").querySelectorAll("[data-delete]").forEach((button) => button.addEventListener("click", () => deleteVocabulary(button.dataset.delete)));
  $("#vocab-list").querySelectorAll("[data-lookup]").forEach((button) => button.addEventListener("click", () => {
    $("#dictionary-word").value = button.dataset.lookup;
    navigate("home");
    $("#dictionary-word").focus();
    lookupDictionary(new Event("submit", { cancelable: true }));
  }));
  renderFlashcard();
}

function renderBookSelectors() {
  const books = state.vocabularyBooks;
  const selectOptions = books.map((book) => `<option value="${escapeHtml(book.name)}">${escapeHtml(book.name)}</option>`).join("");
  const importSelect = $("#import-set-name");
  const importValue = importSelect.value;
  importSelect.innerHTML = `<option value="">Sổ từ mặc định</option>${selectOptions}`;
  if ([...importSelect.options].some((option) => option.value === importValue)) importSelect.value = importValue;

  const vocabularySelect = $("#vocabulary-book-select");
  const selectedValue = vocabularySelect.value;
  vocabularySelect.innerHTML = selectOptions || `<option value="Từ vựng của tôi">Từ vựng của tôi</option>`;
  if ([...vocabularySelect.options].some((option) => option.value === selectedValue)) {
    vocabularySelect.value = selectedValue;
  }
}

function renderVocabularyBooks() {
  renderBookSelectors();
  $("#my-books-list").innerHTML = state.vocabularyBooks.length
    ? state.vocabularyBooks.map((book) => `
      <article class="my-book-card">
        <div class="my-book-icon">言</div>
        <div class="my-book-info"><strong>${escapeHtml(book.name)}</strong><span>${book.entryCount} từ${book.description ? ` · ${escapeHtml(book.description)}` : ""}</span></div>
        <button type="button" class="book-visibility ${book.isPublic ? "is-public" : ""}" data-book-visibility="${book.id}" aria-pressed="${book.isPublic}">
          <span>${book.isPublic ? "◎" : "◉"}</span>${book.isPublic ? "Công khai" : "Riêng tư"}
        </button>
      </article>`).join("")
    : `<p class="book-list-empty">Chưa có sổ từ. Tạo sổ mới hoặc thêm từ để bắt đầu.</p>`;
}

async function createVocabularyBook(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const values = Object.fromEntries(new FormData(form).entries());
  try {
    const book = await api("/api/vocabulary/books", { method: "POST", body: JSON.stringify(values) });
    form.reset();
    state.deckFilter = book.name;
    await loadVocabulary();
    toast("Đã tạo sổ từ mới. Sổ mặc định là riêng tư.", "success");
  } catch (error) {
    toast(error.message, "error");
  }
}

async function updateBookVisibility(event) {
  const button = event.target.closest("[data-book-visibility]");
  if (!button) return;
  button.disabled = true;
  try {
    await api(`/api/vocabulary/books/${encodeURIComponent(button.dataset.bookVisibility)}/visibility`, {
      method: "POST",
      body: JSON.stringify({ isPublic: button.getAttribute("aria-pressed") !== "true" })
    });
    await loadVocabulary();
    toast(button.getAttribute("aria-pressed") === "true"
      ? "Sổ từ đã chuyển sang riêng tư."
      : "Sổ từ đã được chia sẻ công khai.", "success");
  } catch (error) {
    button.disabled = false;
    toast(error.message, "error");
  }
}

async function loadCommunityBooks(query = "") {
  if (!state.authenticated) return;
  $("#community-book-detail").hidden = true;
  $("#community-library").hidden = false;
  $("#community-book-grid").innerHTML = `<div class="community-loading">Đang tìm sổ từ công khai…</div>`;
  $("#community-empty").hidden = true;
  try {
    const result = await api(`/api/community/books?q=${encodeURIComponent(query.trim())}`);
    state.communityBooks = result;
    $("#community-book-grid").innerHTML = result.map((book) => `
      <article class="community-book-card">
        <span class="community-book-mark">共</span><span class="community-book-tag">SỔ TỪ CÔNG KHAI</span>
        <h2>${escapeHtml(book.name)}</h2>
        <p>${escapeHtml(book.description || "Chia sẻ bởi cộng đồng người học Kotoba.")}</p>
        <div class="community-book-meta"><span>✎ ${escapeHtml(book.ownerName)}</span><span>${book.entryCount} từ</span></div>
        <button type="button" class="button button-outline" data-community-book="${book.id}">Xem sổ từ <span>→</span></button>
      </article>`).join("");
    $("#community-empty").hidden = result.length > 0;
  } catch (error) {
    $("#community-book-grid").textContent = "";
    toast(error.message, "error");
  }
}

async function openCommunityBook(bookId) {
  try {
    const { book, entries } = await api(`/api/community/books/${encodeURIComponent(bookId)}`);
    $("#community-book-content").innerHTML = `
      <header class="community-detail-heading"><p class="eyebrow">SỔ TỪ CÔNG KHAI</p><h2>${escapeHtml(book.name)}</h2>
        <p>${escapeHtml(book.description || "Chia sẻ bởi cộng đồng người học Kotoba.")}</p>
        <span>Người tạo: ${escapeHtml(book.ownerName)} · ${entries.length} từ</span>
        <button type="button" class="button button-primary community-save-book" data-save-community-book="${book.id}">＋ Lưu bản sao vào sổ từ của tôi</button></header>
      <div class="community-entry-list">${entries.length ? entries.map((entry) => `
        <article class="community-entry"><strong lang="ja">${escapeHtml(entry.japanese)}</strong>
          ${entry.reading ? `<span class="word-reading">${escapeHtml(entry.reading)}</span>` : ""}
          <p>${escapeHtml(entry.vietnamese || "Chưa có nghĩa tiếng Việt.")}</p>
          ${entry.example ? `<small lang="ja">${escapeHtml(entry.example)}</small>` : ""}
        </article>`).join("") : `<p class="book-list-empty">Sổ từ này chưa có từ vựng.</p>`}</div>`;
    $("#community-library").hidden = true;
    $("#community-book-detail").hidden = false;
    $("#community-book-detail").scrollIntoView({ behavior: "smooth", block: "start" });
  } catch (error) {
    toast(error.message, "error");
  }
}

async function saveCommunityBook(event) {
  const button = event.target.closest("[data-save-community-book]");
  if (!button) return;
  button.disabled = true;
  button.textContent = "Đang lưu bản sao…";
  try {
    const result = await api(`/api/community/books/${encodeURIComponent(button.dataset.saveCommunityBook)}/save`, {
      method: "POST",
      body: JSON.stringify({})
    });
    state.deckFilter = result.book.name;
    await loadVocabulary();
    renderVocabulary();
    navigate("vocabulary");
    toast(`Đã lưu "${result.book.name}" vào sổ của bạn (${result.copiedCount} từ${result.duplicatesSkipped ? `, bỏ qua ${result.duplicatesSkipped} từ đã có` : ""}).`, "success");
  } catch (error) {
    button.disabled = false;
    button.textContent = "＋ Lưu bản sao vào sổ từ của tôi";
    toast(error.message, "error");
  }
}

function activeVocabulary() {
  return state.deckFilter
    ? state.vocabulary.filter((entry) => (entry.setName || "Từ vựng của tôi") === state.deckFilter)
    : state.vocabulary;
}

async function saveVocabulary(event) {
  event.preventDefault();
  const formElement = event.currentTarget;
  const submitButton = formElement.querySelector("button[type='submit'], button:not([type])");
  const form = new FormData(formElement);
  const body = Object.fromEntries(form.entries());
  if (!body.setName.trim()) body.setName = "Từ vựng của tôi";
  if (submitButton) submitButton.disabled = true;
  toast("Đang lưu từ vựng…", "info", 0);
  try {
    await api("/api/vocabulary", { method: "POST", body: JSON.stringify(body) });
    formElement.reset();
    state.deckFilter = "";
    await loadVocabulary();
    toast("Đã thêm từ mới vào sổ của bạn.", "success");
  } catch (error) {
    toast(error.message, "error");
  } finally {
    if (submitButton) submitButton.disabled = false;
  }
}

async function deleteVocabulary(id) {
  if (!confirm("Xóa từ vựng này khỏi sổ của bạn?")) return;
  toast("Đang xóa từ vựng…", "info", 0);
  try {
    await api(`/api/vocabulary/${encodeURIComponent(id)}`, { method: "DELETE" });
    await loadVocabulary();
    toast("Đã xóa từ vựng.", "success");
  } catch (error) {
    toast(error.message, "error");
  }
}

async function importVocabulary(event) {
  const fileInput = event.currentTarget;
  const file = fileInput.files[0];
  if (!file) return;
  const form = new FormData();
  form.append("file", file);
  form.append("setName", $("#import-set-name").value);
  form.append("useAi", String($("#file-ai").checked));
  toast("Đang nhập file từ vựng…", "info", 0);
  try {
    const result = await api("/api/vocabulary/import", { method: "POST", body: form });
    await loadVocabulary();
    const skipped = result.duplicatesSkipped || 0;
    toast(`Đã nhập ${result.imported} mục${skipped ? `, bỏ qua ${skipped} từ trùng` : ""}.`, "success");
  } catch (error) {
    toast(error.message, "error");
  } finally {
    fileInput.value = "";
  }
}

function setVocabTab(tab) {
  state.vocabTab = tab;
  $("#view-vocabulary").classList.toggle("flashcard-mode", tab === "cards");
  $("#view-vocabulary").classList.toggle("quiz-mode", tab === "quiz");
  if (tab === "quiz") resetQuiz();
  document.querySelectorAll("[data-vocab-tab]").forEach((button) => button.classList.toggle("active", button.dataset.vocabTab === tab));
  $("#vocab-list-panel").hidden = tab !== "list";
  $("#book-management").hidden = tab !== "list";
  $(".vocab-side").hidden = tab !== "list";
  $(".import-hint").hidden = tab !== "list";
  $(".deck-filter-row").hidden = tab !== "list";
  $("#flashcard-panel").hidden = tab !== "cards";
  $("#quiz-panel").hidden = tab !== "quiz";
  renderFlashcard();
  renderQuiz();
}

function moveCard(direction) {
  const entries = activeVocabulary();
  if (!entries.length) return;
  state.cardIndex = (state.cardIndex + direction + entries.length) % entries.length;
  $("#flashcard").classList.remove("flipped");
  renderFlashcard();
}

function renderFlashcard() {
  $("#flashcard .card-hint").textContent = "NHẤN ĐỂ XEM ĐÁP ÁN";
  const entries = activeVocabulary();
  if (!entries.length) {
    $("#card-progress").textContent = "0 / 0";
    $("#card-front").textContent = "Chưa có từ vựng";
    $("#card-reading").textContent = "Thêm từ vào sổ để bắt đầu";
    $("#card-back").textContent = "";
    $("#card-example").textContent = "";
    return;
  }
  state.cardIndex %= entries.length;
  const entry = entries[state.cardIndex];
  $("#card-progress").textContent = `${state.cardIndex + 1} / ${entries.length}`;
  $("#card-front").textContent = entry.japanese;
  $("#card-reading").textContent = entry.reading || "Thử nhớ cách đọc";
  $("#card-back").textContent = entry.vietnamese || "Chưa thêm nghĩa";
  $("#card-example").textContent = entry.example || "";
}

function renderQuiz() {
  const entries = activeVocabulary().filter((entry) => entry.vietnamese?.trim());
  const feedback = $("#quiz-feedback");
  const prompt = $("#quiz-prompt");
  const options = $("#quiz-options");
  const summary = $("#quiz-summary");
  const nextButton = $("#quiz-next");
  feedback.textContent = "";
  if (state.quizCompleted) {
    const percentage = entries.length ? Math.round((state.quizCorrectCount / entries.length) * 100) : 0;
    $("#quiz-progress").textContent = `${entries.length} / ${entries.length}`;
    prompt.hidden = true;
    options.hidden = true;
    feedback.hidden = true;
    summary.hidden = false;
    summary.innerHTML = `<strong>${percentage}%</strong><span>Bạn đã nhớ đúng ${state.quizCorrectCount} / ${entries.length} từ trong lượt này.</span>`;
    nextButton.disabled = false;
    nextButton.textContent = "Làm lại bài kiểm tra";
    return;
  }
  prompt.hidden = false;
  options.hidden = false;
  feedback.hidden = false;
  summary.hidden = true;
  if (entries.length < 2) {
    $("#quiz-progress").textContent = "";
    $("#quiz-word").textContent = entries.length ? entries[0].japanese : "Sổ từ cần thêm nghĩa";
    $("#quiz-reading").textContent = "Thêm nghĩa tiếng Việt cho ít nhất 2 từ để bắt đầu trắc nghiệm.";
    options.innerHTML = "";
    nextButton.disabled = true;
    return;
  }
  state.quizIndex %= entries.length;
  state.quizAnswered = false;
  nextButton.disabled = true;
  nextButton.textContent = state.quizIndex === entries.length - 1 ? "Xem kết quả" : "Câu tiếp theo →";
  const answerIndex = state.quizIndex % entries.length;
  const answer = entries[answerIndex];
  const distractors = shuffled(entries.filter((_, index) => index !== answerIndex)).slice(0, 3);
  state.quizOptions = shuffled([{ entry: answer, correct: true }, ...distractors.map((entry) => ({ entry, correct: false }))]);
  $("#quiz-progress").textContent = `${answerIndex + 1} / ${entries.length}`;
  $("#quiz-word").textContent = answer.japanese;
  $("#quiz-reading").textContent = answer.reading || "Chọn nghĩa tiếng Việt phù hợp";
  options.innerHTML = state.quizOptions.map((option, index) =>
    `<button class="quiz-option" type="button" data-option="${index}">${escapeHtml(option.entry.vietnamese || "Chưa có nghĩa")}</button>`
  ).join("");
}

function answerQuiz(event) {
  const button = event.target.closest("[data-option]");
  if (!button || button.disabled || state.quizAnswered || state.quizCompleted) return;
  state.quizAnswered = true;
  const option = state.quizOptions[Number(button.dataset.option)];
  const buttons = [...$("#quiz-options").querySelectorAll(".quiz-option")];
  buttons.forEach((item) => { item.disabled = true; });
  button.classList.add(option.correct ? "correct" : "incorrect");
  if (option.correct) state.quizCorrectCount++;
  if (!option.correct) {
    const correctIndex = state.quizOptions.findIndex((item) => item.correct);
    buttons[correctIndex]?.classList.add("correct");
  }
  const correct = state.quizOptions.find((item) => item.correct).entry;
  $("#quiz-feedback").textContent = option.correct
    ? `Chính xác! ${correct.example || "Tiếp tục ôn tập để ghi nhớ lâu hơn."}`
    : `Chưa đúng — đáp án là: ${correct.vietnamese || correct.japanese}`;
  $("#quiz-next").disabled = false;
}

function advanceQuiz() {
  if (state.quizCompleted) {
    resetQuiz();
  } else if (state.quizAnswered) {
    const entries = activeVocabulary().filter((entry) => entry.vietnamese?.trim());
    if (state.quizIndex >= entries.length - 1) state.quizCompleted = true;
    else state.quizIndex++;
  } else {
    return;
  }
  renderQuiz();
}

function resetQuiz() {
  state.quizIndex = 0;
  state.quizCorrectCount = 0;
  state.quizAnswered = false;
  state.quizCompleted = false;
}

async function analyzeVocabularyEntry(button) {
  const entry = state.vocabulary.find((item) => String(item.id) === button.dataset.analyzeWord);
  if (!entry) return toast("Không tìm thấy từ cần phân tích.", "error");
  const card = button.closest(".vocab-item");
  const result = card.querySelector("[data-ai-result]");
  button.disabled = true;
  button.textContent = "Đang phân tích…";
  result.hidden = false;
  result.textContent = "Đang phân tích riêng từ này theo ngữ cảnh JLPT…";
  try {
    const response = await api("/api/ai/analyze", {
      method: "POST",
      body: JSON.stringify({
        terms: [[entry.japanese, entry.reading, entry.vietnamese].filter(Boolean).join(" — ")]
      })
    });
    const item = response.items?.[0];
    if (!item) throw new Error("AI không trả về nội dung phân tích cho từ này.");
    result.innerHTML = `
      <strong lang="ja">${escapeHtml(item.term)} <span>${escapeHtml(item.reading)}</span></strong>
      <dl>
        <div><dt>Hán Việt</dt><dd>${escapeHtml(item.hanViet)}</dd></div>
        <div><dt>Âm On</dt><dd>${escapeHtml(item.onYomi)}</dd></div>
        <div><dt>Âm Kun</dt><dd>${escapeHtml(item.kunYomi)}</dd></div>
      </dl>
      <p lang="ja">${escapeHtml(item.exampleJa)}</p>
      <p>${escapeHtml(item.exampleVi)}</p>
      <small><b>Ngữ cảnh Mondai JLPT:</b> ${escapeHtml(item.jlptContext)}</small>`;
    toast(`Đã phân tích từ ${entry.japanese}.`, "success");
  } catch (error) {
    result.textContent = error.message;
    toast(error.message, "error");
  } finally {
    button.disabled = false;
    button.textContent = "✦ Phân tích lại";
  }
}

async function lookupDictionary(event) {
  event?.preventDefault();
  const word = $("#dictionary-word").value.trim();
  if (!word) return toast("Nhập từ tiếng Nhật bạn muốn tra.");
  const output = $("#dictionary-result");
  output.textContent = "Đang tra từ…";
  try {
    const result = await api(`/api/dictionary?word=${encodeURIComponent(word)}`);
    if (!result.found) {
      output.textContent = result.message;
      return;
    }
    const definitions = (result.definitions || []).map((definition) => `<li>${escapeHtml(definition)}</li>`).join("");
    const metadata = [
      result.partsOfSpeech?.length ? `Loại từ: ${result.partsOfSpeech.map(escapeHtml).join(", ")}` : "",
      result.jlpt?.length ? `JLPT: ${result.jlpt.map(escapeHtml).join(", ")}` : "",
      result.isCommon ? "Từ thông dụng" : ""
    ].filter(Boolean).join(" · ");
    output.innerHTML = `<div class="dictionary-entry">
      <strong class="dictionary-word-result">${escapeHtml(result.word)}${result.reading ? `（${escapeHtml(result.reading)}）` : ""}</strong>
      ${result.hanViet ? `<div><b>Hán Việt:</b> ${escapeHtml(result.hanViet)}</div>` : ""}
      <div><b>Nghĩa tiếng Việt:</b> ${escapeHtml(result.meaningVi || "Chưa có bản dịch tiếng Việt.")}</div>
      ${definitions ? `<div><b>Nghĩa gốc (EN):</b><ul>${definitions}</ul></div>` : ""}
      ${metadata ? `<small>${metadata}</small>` : ""}
      ${result.exampleJa ? `<div class="dictionary-example"><b>Ví dụ:</b> ${escapeHtml(result.exampleJa)}<br>${escapeHtml(result.exampleVi || "")}</div>` : ""}
      ${result.translationNote ? `<small>${escapeHtml(result.translationNote)}</small>` : ""}
      <a href="${escapeHtml(result.sourceUrl)}" target="_blank" rel="noopener noreferrer">Nguồn: ${escapeHtml(result.source)}</a>
      <small>${escapeHtml(result.attribution || "")}</small>
    </div>`;
  } catch (error) {
    output.textContent = error.message;
  }
}

function selectedJapaneseText() {
  const activeElement = document.activeElement;
  if (activeElement?.matches("input, textarea, [contenteditable='true']")) return null;
  const selection = window.getSelection();
  const text = selection?.toString().trim();
  if (!text || text.length > 40 || /\s/u.test(text) || !/[\u3040-\u30ff\u3400-\u9fff々〆ヵヶ]/u.test(text)) {
    return null;
  }
  const range = selection.getRangeAt(0);
  const rect = range.getBoundingClientRect();
  if (!rect.width && !rect.height) return null;
  return { text, rect };
}

function positionDictionarySelectionToast(rect) {
  const popup = $("#dictionary-selection-toast");
  const margin = 12;
  const popupRect = popup.getBoundingClientRect();
  const left = Math.max(margin, Math.min(
    rect.left + rect.width / 2 - popupRect.width / 2,
    window.innerWidth - popupRect.width - margin
  ));
  const top = rect.bottom + popupRect.height + margin <= window.innerHeight
    ? rect.bottom + 8
    : Math.max(margin, rect.top - popupRect.height - 8);
  popup.style.left = `${left}px`;
  popup.style.top = `${top}px`;
}

function hideDictionarySelectionToast() {
  state.dictionarySelectionRequest++;
  state.dictionarySelectionWord = "";
  clearTimeout(state.dictionarySelectionHideTimer);
  $("#dictionary-selection-toast").hidden = true;
}

async function lookupSelectedJapaneseWord() {
  const selected = selectedJapaneseText();
  if (!selected) {
    hideDictionarySelectionToast();
    return;
  }
  if (selected.text === state.dictionarySelectionWord) return;

  state.dictionarySelectionWord = selected.text;
  const requestId = ++state.dictionarySelectionRequest;
  const popup = $("#dictionary-selection-toast");
  popup.innerHTML = `<span class="dictionary-toast-kicker">TRA TỪ NHANH</span><button type="button" class="dictionary-toast-close" data-dictionary-toast-close aria-label="Đóng">×</button><strong lang="ja">${escapeHtml(selected.text)}</strong><span>Đang tra từ điển…</span>`;
  popup.hidden = false;
  positionDictionarySelectionToast(selected.rect);
  clearTimeout(state.dictionarySelectionHideTimer);

  try {
    let result = state.dictionarySelectionCache.get(selected.text);
    if (!result) {
      result = await api(`/api/dictionary?word=${encodeURIComponent(selected.text)}&quick=true`);
      state.dictionarySelectionCache.set(selected.text, result);
      if (state.dictionarySelectionCache.size > 60) {
        state.dictionarySelectionCache.delete(state.dictionarySelectionCache.keys().next().value);
      }
    }
    if (requestId !== state.dictionarySelectionRequest) return;
    const currentSelection = selectedJapaneseText();
    if (!currentSelection || currentSelection.text !== selected.text) {
      hideDictionarySelectionToast();
      return;
    }
    if (!result.found) {
      popup.innerHTML = `<span class="dictionary-toast-kicker">TRA TỪ NHANH</span><button type="button" class="dictionary-toast-close" data-dictionary-toast-close aria-label="Đóng">×</button><strong lang="ja">${escapeHtml(selected.text)}</strong><span>${escapeHtml(result.message || "Chưa tìm thấy từ này trong từ điển.")}</span>`;
    } else {
      const meaning = result.meaningVi || result.meaning || result.definitions?.join("; ") || "Chưa có nghĩa.";
      const meaningLabel = result.meaningVi ? "Nghĩa tiếng Việt" : "Nghĩa gốc (EN)";
      popup.innerHTML = `<span class="dictionary-toast-kicker">TRA TỪ NHANH</span><button type="button" class="dictionary-toast-close" data-dictionary-toast-close aria-label="Đóng">×</button><strong lang="ja">${escapeHtml(result.word || selected.text)}${result.reading ? ` <small>${escapeHtml(result.reading)}</small>` : ""}</strong><small>${meaningLabel}</small><span>${escapeHtml(meaning)}</span>${result.partsOfSpeech?.length ? `<small>${escapeHtml(result.partsOfSpeech.join(", "))}</small>` : ""}`;
    }
    positionDictionarySelectionToast(currentSelection.rect);
    state.dictionarySelectionHideTimer = setTimeout(hideDictionarySelectionToast, 8000);
  } catch (error) {
    if (requestId !== state.dictionarySelectionRequest) return;
    popup.innerHTML = `<span class="dictionary-toast-kicker">TRA TỪ NHANH</span><button type="button" class="dictionary-toast-close" data-dictionary-toast-close aria-label="Đóng">×</button><strong lang="ja">${escapeHtml(selected.text)}</strong><span>${escapeHtml(error.message)}</span>`;
    positionDictionarySelectionToast(selected.rect);
    state.dictionarySelectionHideTimer = setTimeout(hideDictionarySelectionToast, 8000);
  }
}

async function loadQuestions() {
  try {
    state.questions = await api("/api/questions");
    const categories = [...new Set(state.questions.map((question) => question.category))];
    $("#question-categories").innerHTML = `<button class="filter-chip active" data-category="all">Tất cả</button>${categories.map((category) => `<button class="filter-chip" data-category="${escapeHtml(category)}">${escapeHtml(category)}</button>`).join("")}`;
    $("#question-count").textContent = state.questions.length;
    $("#interview-count").textContent = state.questions.length;
    renderQuestions();
  } catch (error) {
    toast(error.message);
  }
}

function renderQuestions() {
  const questions = state.category === "all" ? state.questions : state.questions.filter((question) => question.category === state.category);
  $("#question-grid").innerHTML = questions.length ? questions.map((question, index) => `
    <article class="question-card">
      <div class="question-card-top"><span class="category-tag">${escapeHtml(question.category)}</span><span class="question-number">Q.${String(index + 1).padStart(2, "0")}</span></div>
      <h3 lang="ja">${escapeHtml(question.japanese)}</h3><p class="question-meaning">${escapeHtml(question.vietnamese)}</p>
      <div class="answer-hint" hidden>${escapeHtml(question.tip)}</div>
      <div class="question-card-bottom"><span class="question-number">面接の質問</span><button type="button" data-question>Xem gợi ý trả lời →</button></div>
      <form class="answer-generator" data-answer-form>
        <label>Kinh nghiệm thực tế của bạn<textarea name="experience" minlength="30" maxlength="2500" required placeholder="Mô tả ngắn tình huống, vai trò của bạn, việc bạn đã làm và kết quả. AI sẽ chỉ dựa trên thông tin này."></textarea></label>
        <button class="button button-primary" type="submit">✦ Soạn câu trả lời với AI</button>
        <div class="generated-answer" data-answer-result hidden></div>
      </form>
    </article>`).join("") : `<div class="no-results">Chưa có câu hỏi trong chủ đề này.</div>`;
}

async function generateInterviewAnswer(event) {
  const formElement = event.target.closest("[data-answer-form]");
  if (!formElement) return;
  event.preventDefault();
  const card = formElement.closest(".question-card");
  const button = formElement.querySelector("button[type='submit']");
  const output = formElement.querySelector("[data-answer-result]");
  const experience = new FormData(formElement).get("experience").toString().trim();
  button.disabled = true;
  button.textContent = "Đang soạn câu trả lời…";
  output.hidden = true;
  toast("Đang tạo câu trả lời dựa trên trải nghiệm của bạn…", "info", 0);
  try {
    const answer = await api("/api/interview/answer", {
      method: "POST",
      body: JSON.stringify({
        question: card.querySelector("h3").textContent,
        category: card.querySelector(".category-tag").textContent,
        experience
      })
    });
    output.innerHTML = `<span class="answer-label">CÂU TRẢ LỜI TIẾNG NHẬT</span><p class="answer-japanese" lang="ja">${escapeHtml(answer.answerJapanese)}</p>
      <p class="answer-translation"><strong>Dịch nghĩa:</strong> ${escapeHtml(answer.answerVietnamese)}</p>
      <div class="answer-breakdown"><p><strong>Nguyên nhân / lý do</strong>${escapeHtml(answer.reason)}</p><p><strong>Cách xử lý</strong>${escapeHtml(answer.handling)}</p><p><strong>Câu chuyện thực tế</strong>${escapeHtml(answer.story)}</p></div>`;
    output.hidden = false;
    toast("Đã soạn xong câu trả lời.", "success");
  } catch (error) {
    toast(error.message, "error");
  } finally {
    button.disabled = false;
    button.innerHTML = "✦ Soạn câu trả lời với AI";
  }
}

init();
