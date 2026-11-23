// Turns the home page form into a conversation thread. Without this script the same form posts
// to /questions and shows the answer page. The thread exists only in this page.

const form = document.querySelector('form[data-chat]');
const thread = document.querySelector('.thread');
const input = form?.querySelector('textarea');
const motion = matchMedia('(prefers-reduced-motion: no-preference)');

function bubble(className, text) {
  const turn = document.createElement('li');
  turn.className = 'turn';
  const question = document.createElement('div');
  question.className = 'bubble bubble-user';
  const paragraph = document.createElement('p');
  paragraph.className = 'answer-text';
  paragraph.textContent = text; // Never parsed as HTML.
  question.append(paragraph);
  const answer = document.createElement('div');
  answer.className = `bubble ${className}`;
  answer.setAttribute('aria-busy', 'true');
  answer.innerHTML = '<p class="thinking">Searching your documents…</p>';
  turn.append(question, answer);
  return turn;
}

// The first saved answer starts a conversation: keep its id for follow-ups, show its address,
// and list it at the top of the sidebar like any saved chat.
function remember(id, question) {
  if (!id || !/^[0-9a-f-]{36}$/.test(id)) return;
  const field = form.querySelector('input[name="conversation"]');
  if (!field || field.value === id) return;
  field.value = id;
  history.replaceState(null, '', `/chats/${id}`);
  const list = document.querySelector('.sidebar .chat-list');
  if (list && !list.querySelector(`[data-chat-id="${id}"]`)) {
    const link = document.createElement('a');
    link.className = 'nav-link nav-chat';
    link.href = `/chats/${id}`;
    link.dataset.chatId = id;
    link.setAttribute('aria-current', 'page');
    const label = document.createElement('span');
    label.textContent = question.length > 80 ? `${question.slice(0, 79)}…` : question;
    link.append(label);
    list.prepend(link);
  }
}

async function ask() {
  const text = input.value.trim();
  // One question at a time: forms.js marks the form while an answer is pending.
  if (!text || form.hasAttribute('data-submitting')) return;
  const body = new URLSearchParams(new FormData(form));
  body.set('action', 'ask');
  const pending = bubble('bubble-assistant', text);
  thread.append(pending);
  document.body.dataset.conversation = '';
  input.value = '';
  pending.scrollIntoView({ block: 'end', behavior: motion.matches ? 'smooth' : 'instant' });
  try {
    // Read the URL from an attribute: form.action would return the button named "action".
    const response = await fetch(form.dataset.turn, {
      method: 'POST',
      body,
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    });
    // The server renders and escapes the turn, including any error message.
    const template = document.createElement('template');
    template.innerHTML = await response.text();
    const turn = template.content.querySelector('.turn');
    if (!turn) throw new Error('Unexpected response');
    pending.replaceWith(turn);
    remember(turn.dataset.conversation, text);
  } catch {
    const answer = pending.querySelector('.bubble-assistant');
    answer.removeAttribute('aria-busy');
    answer.innerHTML = '<p role="alert">The answer could not be loaded. Check your connection and try again.</p>';
  } finally {
    form.removeAttribute('data-submitting');
    input.focus();
  }
}

if (form && thread && input) {
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    ask();
  });
  input.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter' || event.shiftKey) return;
    event.preventDefault();
    // Enter also confirms an input-method candidate; never send mid-composition.
    if (event.isComposing || event.keyCode === 229) return;
    form.requestSubmit();
  });
}
