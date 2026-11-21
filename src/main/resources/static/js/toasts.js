// Moves one-time success messages (marked data-toast) into a toast stack in the top layer. They
// stay until dismissed, so nothing disappears before it can be read. Without this script the same
// messages remain ordinary page notices.

import { modalOpen } from './dialogs.js';

function announce(text) {
  if (typeof document.ariaNotify === 'function') {
    document.ariaNotify(text);
    return;
  }
  const announcer = document.getElementById('toast-announcer');
  if (!announcer) return;
  announcer.textContent = '';
  // A fresh text node after clearing makes screen readers announce repeated messages too.
  requestAnimationFrame(() => {
    announcer.textContent = text;
  });
}

export function showToasts() {
  const stack = document.getElementById('toasts');
  const notices = [...document.querySelectorAll('main [data-toast]')];
  if (!stack || !notices.length || !('showPopover' in stack) || modalOpen()) return;

  for (const notice of notices) {
    const toast = document.createElement('div');
    toast.className = `toast ${[...notice.classList].filter((name) => name.startsWith('notice-')).join(' ')}`;
    const body = document.createElement('div');
    body.className = 'toast-body';
    body.append(...notice.childNodes);
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'toast-close';
    close.innerHTML =
      '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>' +
      '<span class="visually-hidden">Dismiss notification</span>';
    close.addEventListener('click', () => {
      const next = toast.nextElementSibling ?? toast.previousElementSibling;
      const hadFocus = toast.contains(document.activeElement);
      toast.remove();
      if (hadFocus) {
        if (next) next.querySelector('.toast-close')?.focus();
        else {
          // No toast left: return focus to the page content rather than losing it.
          const main = document.getElementById('content');
          main?.setAttribute('tabindex', '-1');
          main?.focus({ preventScroll: true });
        }
      }
      if (!stack.children.length) stack.hidePopover();
    });
    toast.append(body, close);
    stack.append(toast);
    notice.remove();
    // Only the message text is announced, not the names of its controls.
    announce(body.textContent.replace(/\s+/g, ' ').trim());
  }
  stack.showPopover();
}
