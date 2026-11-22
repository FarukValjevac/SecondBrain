// Progressive enhancements shared by every page. Without this script every page still works:
// on narrow screens the navigation stays a scrollable row of links, messages stay in the page,
// and the palette and shortcuts are simply absent.

import { lightDismissFallback } from './dialogs.js';
import { setUpPalette } from './palette.js';
import { showToasts } from './toasts.js';

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
  // On phones the bar shows only the page; Add document moves into the drawer as well.
  const add = document.querySelector('.sidebar .nav-add');
  if (add) drawer.querySelector('.drawer-links').append(add.cloneNode(true));
  menu.hidden = false;
  menu.addEventListener('click', () => {
    drawer.showModal();
    menu.setAttribute('aria-expanded', 'true');
  });
  drawer.addEventListener('close', () => menu.setAttribute('aria-expanded', 'false'));
  drawer.querySelector('.drawer-close').addEventListener('click', () => drawer.close());
}

lightDismissFallback();
setUpPalette();
showToasts();
