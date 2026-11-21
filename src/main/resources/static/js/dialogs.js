// Dialogs marked closedby="any" close on a click outside them natively where supported. Elsewhere
// (Safari) emulate it: the press and the release must both land outside the dialog's box, so a
// text selection that ends on the backdrop does not dismiss it.

const outside = (event, dialog) => {
  const box = dialog.getBoundingClientRect();
  return (
    event.clientX < box.left ||
    event.clientX > box.right ||
    event.clientY < box.top ||
    event.clientY > box.bottom
  );
};

export function lightDismissFallback() {
  if ('closedBy' in HTMLDialogElement.prototype) return;
  for (const dialog of document.querySelectorAll('dialog[closedby="any"]')) {
    let pressedOutside = false;
    dialog.addEventListener('pointerdown', (event) => {
      pressedOutside = outside(event, dialog);
    });
    dialog.addEventListener('pointerup', (event) => {
      if (pressedOutside && outside(event, dialog)) dialog.close();
    });
  }
}

/** True while any modal dialog is open, so page shortcuts and toasts stay out of its way. */
export const modalOpen = () => document.querySelector('dialog:modal') !== null;
