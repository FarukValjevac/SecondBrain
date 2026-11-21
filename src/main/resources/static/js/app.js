// Progressive enhancements shared by every page. Without this script every page still works:
// on narrow screens the navigation simply stays a scrollable row of links.

const root = document.documentElement;
root.dataset.js = '';

// Narrow screens: move a copy of the navigation into a native modal dialog. The dialog makes the
// rest of the page inert, handles Escape and returns focus to the menu button on close.
const drawer = document.getElementById('nav-drawer');
const menu = document.querySelector('.menu-button');
const links = document.querySelector('.sidebar .nav');
if (drawer && menu && links) {
  const copy = links.cloneNode(true);
  copy.setAttribute('aria-label', 'Main menu');
  drawer.querySelector('.drawer-links').append(copy);
  menu.hidden = false;
  menu.addEventListener('click', () => {
    drawer.showModal();
    menu.setAttribute('aria-expanded', 'true');
  });
  drawer.addEventListener('close', () => menu.setAttribute('aria-expanded', 'false'));
  drawer.querySelector('.drawer-close').addEventListener('click', () => drawer.close());
  // A click on the dimmed area outside the sheet lands on the dialog itself.
  drawer.addEventListener('click', (event) => {
    if (event.target === drawer) drawer.close();
  });
}
