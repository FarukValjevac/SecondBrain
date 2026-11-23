// Select text in the reading or lines view to highlight the lines it covers. Without this script
// the "Highlight lines by number" form does the same.

const toolbar = document.getElementById('highlight-toolbar');
const area = document.querySelector('.reading, .source-lines');

const lineOf = (node) => {
  const element = node?.nodeType === Node.ELEMENT_NODE ? node : node?.parentElement;
  const line = element?.closest('[id^="line-"]');
  return line && area.contains(line) ? Number(line.id.slice(5)) : null;
};

if (toolbar && area) {
  document.body.append(toolbar); // Positioned against the page, not a card.
  const from = toolbar.querySelector('input[name="from"]');
  const to = toolbar.querySelector('input[name="to"]');
  let timer;

  const hide = () => {
    if (!toolbar.contains(document.activeElement)) toolbar.hidden = true;
  };

  document.addEventListener('selectionchange', () => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      const selection = document.getSelection();
      if (!selection || selection.isCollapsed || selection.rangeCount === 0) return hide();
      const start = lineOf(selection.anchorNode);
      const end = lineOf(selection.focusNode);
      if (start === null || end === null) return hide();
      from.value = Math.min(start, end);
      to.value = Math.max(start, end);
      const box = selection.getRangeAt(0).getBoundingClientRect();
      toolbar.hidden = false;
      const width = toolbar.offsetWidth;
      const left = Math.min(
        Math.max(8, box.left + window.scrollX + box.width / 2 - width / 2),
        document.documentElement.clientWidth - width - 8,
      );
      toolbar.style.left = `${left}px`;
      toolbar.style.top = `${box.bottom + window.scrollY + 10}px`;
    }, 150);
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !toolbar.hidden) toolbar.hidden = true;
  });
}
