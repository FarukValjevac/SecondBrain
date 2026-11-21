// Command palette and keyboard shortcuts. Ctrl/Cmd+K opens the palette anywhere; the single-key
// shortcuts "/" and "?" never fire while typing in a field or with a dialog open.

import { modalOpen } from './dialogs.js';

const typing = (target) =>
  target instanceof HTMLElement &&
  (target.isContentEditable || target.closest('input, textarea, select') !== null);

export function setUpPalette() {
  const palette = document.getElementById('command-palette');
  const shortcuts = document.getElementById('shortcuts-dialog');
  const button = document.querySelector('.palette-button');
  if (!palette || !shortcuts || !button) return;

  const input = palette.querySelector('#palette-input');
  const options = [...palette.querySelectorAll('[role="option"]')];
  const empty = palette.querySelector('.palette-empty');
  const labels = options.map((option) => option.textContent.toLocaleLowerCase());
  let active = null;

  const mac = /mac|iphone|ipad/i.test(navigator.userAgentData?.platform ?? navigator.platform);
  if (mac) button.querySelector('.palette-key').textContent = '⌘K';
  button.hidden = false;

  const visible = () => options.filter((option) => !option.hidden);
  const activate = (option) => {
    active?.removeAttribute('aria-selected');
    active = option ?? null;
    if (active) {
      active.setAttribute('aria-selected', 'true');
      input.setAttribute('aria-activedescendant', active.id);
      active.scrollIntoView({ block: 'nearest' });
    } else {
      input.removeAttribute('aria-activedescendant');
    }
  };
  const filter = () => {
    const term = input.value.trim().toLocaleLowerCase();
    options.forEach((option, index) => {
      option.hidden = term !== '' && !labels[index].includes(term);
    });
    const shown = visible();
    empty.textContent = shown.length ? '' : 'No matching pages or actions.';
    activate(shown[0]);
  };
  const choose = (option) => {
    if (!option) return;
    palette.close();
    if (option.dataset.action === 'shortcuts') shortcuts.showModal();
    else if (option.dataset.href) location.assign(option.dataset.href);
  };
  const open = () => {
    if (palette.open) return;
    for (const dialog of document.querySelectorAll('dialog[open]')) dialog.close();
    input.value = '';
    filter();
    palette.showModal();
  };

  button.addEventListener('click', open);
  input.addEventListener('input', filter);
  input.addEventListener('keydown', (event) => {
    if (event.isComposing || event.keyCode === 229) return;
    const shown = visible();
    const index = shown.indexOf(active);
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (!shown.length) return;
      const step = event.key === 'ArrowDown' ? 1 : -1;
      activate(shown[(index + step + shown.length) % shown.length]);
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault();
      activate(event.key === 'Home' ? shown[0] : shown.at(-1));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      choose(active);
    }
  });
  palette.querySelector('[role="listbox"]').addEventListener('click', (event) => {
    choose(event.target.closest('[role="option"]'));
  });

  document.addEventListener('keydown', (event) => {
    if (event.defaultPrevented || event.isComposing) return;
    // A modifier chord inserts no text, so it is safe even while typing.
    if ((event.ctrlKey || event.metaKey) && !event.altKey && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      if (palette.open) palette.close();
      else open();
      return;
    }
    if (event.ctrlKey || event.metaKey || event.altKey) return;
    if (typing(event.target) || modalOpen()) return;
    if (event.key === '/') {
      const box = document.querySelector('#ask, #search-query, #question-text');
      if (box) {
        event.preventDefault();
        box.focus();
      }
    } else if (event.key === '?') {
      event.preventDefault();
      shortcuts.showModal();
    }
  });
}
