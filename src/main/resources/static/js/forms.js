// Ignores repeat submissions of the same form, so a double click cannot send a second
// upload or provider request. Forms still work without this script; the server stays
// authoritative for every check.
document.addEventListener('submit', (event) => {
  const form = event.target;
  if (!(form instanceof HTMLFormElement)) return;
  // The submit event fires only after built-in validation passes.
  if (form.hasAttribute('data-submitting')) {
    event.preventDefault();
    return;
  }
  form.setAttribute('data-submitting', '');
});

// A page restored from the back-forward cache must accept submissions again.
window.addEventListener('pageshow', (event) => {
  if (!event.persisted) return;
  for (const form of document.querySelectorAll('form[data-submitting]')) {
    form.removeAttribute('data-submitting');
  }
});
