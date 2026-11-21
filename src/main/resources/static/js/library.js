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
