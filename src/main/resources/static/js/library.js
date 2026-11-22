// Filter-as-you-type over the library cards already on this page. Without this script every card
// stays visible and paging works as before; nothing is sent to the server.

const filter = document.querySelector('[data-filter]');
const input = filter?.querySelector('input');
const cards = [...document.querySelectorAll('#document-cards > li')];
const status = document.querySelector('[data-filter-status]');

if (filter && input && cards.length) {
  filter.hidden = false;
  const names = cards.map((card) =>
    (card.querySelector('[data-filter-name]')?.textContent ?? '').toLocaleLowerCase(),
  );
  input.addEventListener('input', () => {
    const term = input.value.trim().toLocaleLowerCase();
    let shown = 0;
    cards.forEach((card, index) => {
      const match = !term || names[index].includes(term);
      card.hidden = !match;
      if (match) shown += 1;
    });
    // Announce the count, never the typed text.
    status.textContent = term
      ? `${shown} of ${cards.length} documents on this page match.`
      : '';
  });
}

// Drag a document card onto a folder card or a breadcrumb to move it. Optional: the document
// page's Move form does the same without JavaScript or a pointer.
const DOCUMENT = 'application/x-secondbrain-document';
const targets = [...document.querySelectorAll('[data-drop-folder]')];
const moveStatus = document.querySelector('[data-move-status]');
const motion = matchMedia('(prefers-reduced-motion: no-preference)');

async function move(card, folder, label) {
  const id = card.dataset.documentId;
  const name = card.querySelector('[data-filter-name]')?.textContent.trim() ?? 'Document';
  try {
    const response = await fetch(`/documents/${encodeURIComponent(id)}/folder`, {
      method: 'POST',
      body: new URLSearchParams({ folder, return: 'library' }),
      redirect: 'manual',
    });
    if (!(response.ok || response.type === 'opaqueredirect')) throw new Error('Move failed');
    const done = () => card.remove();
    if (motion.matches) {
      card.animate([{ opacity: 1, scale: 1 }, { opacity: 0, scale: 0.9 }], {
        duration: 200,
        easing: 'ease-in',
      }).finished.then(done, done);
    } else {
      done();
    }
    moveStatus.textContent = `Moved ${name} to ${label}.`;
  } catch {
    moveStatus.textContent = `${name} could not be moved. Use Move on the document page.`;
  }
}

if (targets.length && cards.length && moveStatus) {
  document.querySelector('.drag-hint')?.removeAttribute('hidden');
  for (const card of cards) {
    card.draggable = true;
    card.addEventListener('dragstart', (event) => {
      event.dataTransfer.setData(DOCUMENT, card.dataset.documentId);
      event.dataTransfer.effectAllowed = 'move';
      card.dataset.dragging = '';
      document.body.dataset.draggingDocument = '';
    });
    card.addEventListener('dragend', () => {
      delete card.dataset.dragging;
      delete document.body.dataset.draggingDocument;
    });
  }
  for (const target of targets) {
    target.addEventListener('dragover', (event) => {
      if (!event.dataTransfer.types.includes(DOCUMENT)) return;
      event.preventDefault();
      event.dataTransfer.dropEffect = 'move';
      target.dataset.dropActive = '';
    });
    target.addEventListener('dragleave', (event) => {
      if (!target.contains(event.relatedTarget)) delete target.dataset.dropActive;
    });
    target.addEventListener('drop', (event) => {
      if (!event.dataTransfer.types.includes(DOCUMENT)) return;
      event.preventDefault();
      delete target.dataset.dropActive;
      const card = cards.find((item) => item.dataset.documentId === event.dataTransfer.getData(DOCUMENT));
      const label =
        target.querySelector('.folder-card-name')?.textContent.trim() ||
        target.textContent.trim() ||
        'Unfiled';
      if (card) move(card, target.dataset.dropFolder, target.dataset.dropFolder ? label : 'Unfiled');
    });
  }
}
