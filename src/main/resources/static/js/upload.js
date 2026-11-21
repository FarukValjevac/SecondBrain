// Lets files be dropped onto the upload card. Dropped files go into the normal file input, so the
// user still presses Upload, and the form works the same way without this script.
const zone = document.querySelector('[data-drop-zone]');
const input = zone?.querySelector('input[type="file"]');
const selection = zone?.querySelector('.upload-selection');

function describe() {
  if (!selection || !input) return;
  const count = input.files.length;
  selection.textContent = count > 1 ? `${count} files selected.` : '';
}

if (zone && input) {
  zone.addEventListener('dragover', (event) => {
    if (!event.dataTransfer?.types.includes('Files')) return;
    event.preventDefault();
    zone.setAttribute('data-dragging', '');
  });
  zone.addEventListener('dragleave', (event) => {
    if (!zone.contains(event.relatedTarget)) zone.removeAttribute('data-dragging');
  });
  zone.addEventListener('drop', (event) => {
    zone.removeAttribute('data-dragging');
    if (!event.dataTransfer?.files.length) return;
    event.preventDefault();
    input.files = event.dataTransfer.files;
    describe();
  });
  input.addEventListener('change', describe);
}
