// Study enhancements. Without this script the flashcard reveals with its <details> toggle and
// the quiz shows every question in one form; both submit exactly the same fields.

import { modalOpen } from './dialogs.js';

const typing = (target) =>
  target instanceof HTMLElement &&
  (target.isContentEditable || target.closest('input, textarea, select') !== null);

// Flashcards: Space reveals the answer, 1–4 grade it once revealed.
const card = document.querySelector('[data-flashcard]');
if (card) {
  const answer = card.querySelector('details');
  const grades = [...card.querySelectorAll('.flashcard-grades button')];
  card.querySelector('.study-keys').hidden = false;
  for (const key of card.querySelectorAll('.grade kbd')) key.hidden = false;
  let submitted = false;

  document.addEventListener('keydown', (event) => {
    if (event.defaultPrevented || event.isComposing || submitted) return;
    if (event.ctrlKey || event.metaKey || event.altKey) return;
    if (typing(event.target) || modalOpen()) return;
    // A focused button or summary already handles Space itself.
    if (event.key === ' ' && !answer.open && !event.target.closest?.('button, summary, a')) {
      event.preventDefault();
      answer.open = true;
      return;
    }
    const grade = grades[Number(event.key) - 1];
    if (answer.open && grade && /^[1-4]$/.test(event.key)) {
      event.preventDefault();
      submitted = true;
      grade.form.requestSubmit(grade);
    }
  });
}

// Quiz: one question at a time with progress. Hidden steps still submit their answers.
const quiz = document.querySelector('form[data-quiz]');
const steps = quiz ? [...quiz.querySelectorAll('.quiz-step')] : [];
if (quiz && steps.length > 1) {
  const progress = quiz.querySelector('.quiz-progress');
  const count = progress.querySelector('.quiz-count');
  const bar = progress.querySelector('progress');
  const previous = quiz.querySelector('.quiz-previous');
  const next = quiz.querySelector('.quiz-next');
  const submit = quiz.querySelector('.quiz-submit');
  let current = 0;

  const show = (index, focus) => {
    current = index;
    steps.forEach((step, position) => {
      step.hidden = position !== index;
    });
    count.textContent = `Question ${index + 1} of ${steps.length}`;
    bar.max = steps.length;
    bar.value = index + 1;
    previous.hidden = index === 0;
    const last = index === steps.length - 1;
    next.hidden = last;
    submit.hidden = !last;
    if (focus) steps[index].querySelector('textarea')?.focus();
  };

  progress.hidden = false;
  previous.addEventListener('click', () => show(current - 1, true));
  next.addEventListener('click', () => show(current + 1, true));
  show(0, false);
}
