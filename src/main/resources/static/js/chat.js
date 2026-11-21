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
    const response = await fetch(form.action.replace(/\/questions$/, '/questions/turn'), {
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

  // Feedback inside the thread is sent in the background so the conversation stays.
  thread.addEventListener('submit', async (event) => {
    const feedback = event.target.closest('form.answer-feedback');
    if (!feedback) return;
    event.preventDefault();
    const data = new URLSearchParams(new FormData(feedback));
    if (event.submitter?.name) data.set(event.submitter.name, event.submitter.value);
    const status = document.createElement('p');
    status.setAttribute('role', 'status');
    try {
      const response = await fetch(feedback.action, {
        method: 'POST',
        body: data,
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      });
      status.textContent = response.ok
        ? 'Thanks, your feedback was saved on this computer.'
        : 'Feedback could not be saved.';
    } catch {
      status.textContent = 'Feedback could not be saved.';
    }
    feedback.replaceWith(status);
  });
}
